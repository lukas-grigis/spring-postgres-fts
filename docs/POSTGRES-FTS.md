# Postgres full-text search, in plain English

Reference for the Postgres machinery this repo uses, with a link to every primary source. If a claim in
the [companion blog post](https://lukasgrigis.dev/blog/spring-boot-postgres-full-text-search/)
or in this code needs backing, it is here. Nothing below is from memory: each statement links to the manual page or
paper it paraphrases, and the behavioral claims were checked against a real
`postgres:18-trixie` container by the integration tests named alongside them.

None of it is new, either. Full-text search has been in core Postgres
since [8.3](https://www.postgresql.org/docs/release/8.3.0/), and `pg_trgm` is older still — Postgres 18 and Spring Boot
4.1 are what this repo was built and verified on, not what makes any of it work.

## The three types

Full-text search in Postgres is a pipeline between two data types.

**`tsvector`** is a document after processing: a sorted list of _lexemes_ (normalized word roots)
with their positions. `to_tsvector('english', 'The cats were running')` gives `'cat':2 'run':4` — stop words dropped,
the rest stemmed. **`tsquery`** is a search condition over lexemes, with `&`,
`|`, `!` and `<->` (followed-by). The `@@` operator asks whether a `tsvector` satisfies a
`tsquery`. That is the whole model.

A **text search configuration** controls the processing: which parser splits the text, and which _dictionaries_ map each
token to a lexeme. `english` is the built-in one. This repo adds a second,
`book_synonym_search`, which is where the synonym behavior comes from.

Sources: [Introduction](https://www.postgresql.org/docs/18/textsearch-intro.html),
[Text search types](https://www.postgresql.org/docs/18/datatype-textsearch.html).

## Storing the vector: generated columns

Computing `to_tsvector(...)` on every query means re-parsing every document on every search. The fix is a stored
generated column, which Postgres recomputes only when the source columns change (`001-schema.xml`, changeset 006):

```sql
ALTER TABLE book
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        SETWEIGHT(TO_TSVECTOR('english', COALESCE(title, '')), 'A') ||
        SETWEIGHT(TO_TSVECTOR('english', COALESCE(excerpt, '')), 'B')
        ) STORED;
```

Two things are load-bearing.

`setweight` labels lexemes A–D, and the ranking functions weight those labels differently by default, which is how a
title match outranks an excerpt match without any application code.

`STORED` must be written explicitly. **Postgres 18 changed the default for generated columns to
`VIRTUAL`**, and a virtual column cannot be indexed — `CREATE INDEX ... USING GIN (search_vector)`
on one fails with `indexes on virtual generated columns are not supported`. Omitting the keyword on Postgres 18
therefore breaks the index, not the column.
[
`GeneratedColumnAndSynonymDirectionIT`](../src/test/java/dev/lukasgrigis/booksearch/search/GeneratedColumnAndSynonymDirectionIT.java)
asserts this against a live server.

Source: [Generated columns](https://www.postgresql.org/docs/18/ddl-generated-columns.html) — which documents the
`VIRTUAL` default but, notably, not the index restriction; the error text above, reproduced live, is the authority for
that half.

## Parsing user input: use `websearch_to_tsquery`

Four functions turn text into a `tsquery`, and the difference matters when the text comes from a search box:

| Function               | Behavior on `foo bar`                   | Safe for raw user input                |
| ---------------------- | --------------------------------------- | -------------------------------------- |
| `to_tsquery`           | syntax error — needs explicit operators | no, it throws                          |
| `plainto_tsquery`      | `foo & bar`                             | yes, but no phrase or negation support |
| `phraseto_tsquery`     | `foo <-> bar`                           | yes, everything becomes a phrase       |
| `websearch_to_tsquery` | `foo & bar`                             | yes                                    |

`websearch_to_tsquery` accepts the syntax people already type into search engines: quoted phrases,
`or`, and a leading `-` for negation. It never throws on malformed input, which is why it is the only sensible choice
for a text field.
[`LexicalSearchQuerySyntaxIT`](../src/test/java/dev/lukasgrigis/booksearch/search/LexicalSearchQuerySyntaxIT.java)
covers the phrase and negation cases.

Source: [Parsing queries](https://www.postgresql.org/docs/18/textsearch-controls.html#TEXTSEARCH-PARSING-QUERIES).

## Ranking: `ts_rank` vs `ts_rank_cd`

Neither function is a relevance score in the abstract. Both take a vector and a query and return a float that is only
meaningful _relative to other rows for the same query_.

- **`ts_rank`** weights by term frequency.
- **`ts_rank_cd`** computes _cover density_: how close the query terms sit to one another in the document. For a
  multi-word query it prefers documents where the words appear together over ones where they are scattered.

This repo uses `ts_rank_cd` for LEXICAL and `ts_rank` for SYNONYM, and
[`RankOrderingIT`](../src/test/java/dev/lukasgrigis/booksearch/search/RankOrderingIT.java) asserts the two genuinely
disagree on the seeded corpus. Not assumed.

Both accept an optional `normalization` argument, which is a **bit mask, not a scale**: 0 is no normalization (the
default here), 1 divides by `1 + log(length)`, 2 divides by the length, 8 by the number of unique words, 32 maps the
rank to `rank/(rank+1)`, and the bits combine. Turning it on is reasonable for a corpus with wildly varying document
sizes; it is left as a documented seam, not an unexplained magic number in the query.

Source: [Ranking search results](https://www.postgresql.org/docs/18/textsearch-controls.html#TEXTSEARCH-RANKING).

## Highlighting: `ts_headline` does not escape — so the input is escaped first

`ts_headline` returns an excerpt with the matching terms wrapped in `StartSel`/`StopSel` (by default `<b>`/`</b>`). It
is the source of the highlighted snippets in the UI, and it carries a security caveat that is easy to miss. The manual
is blunt about it, under a heading titled
"Cross-site Scripting (XSS) Safety": the output "is not guaranteed to be safe for direct inclusion in web pages", and an
application should either strip HTML from the input document or run a sanitizer over the output.

This repo works on the input side, but escapes instead of strips — stricter than the manual's first option, because the
text survives verbatim — in SQL, before `ts_headline` ever runs:

```sql
ts_headline
('english',
    REPLACE (REPLACE (REPLACE (b.excerpt, '&', '&amp;'), '<', '&lt;'), '>', '&gt;'),
    websearch_to_tsquery('english', :q),
    'StartSel=<mark>,StopSel=</mark>,MaxWords=35,MinWords=15')
```

The excerpt is neutralized first, so the only live markup in the result is the `<mark>` this application asked for.

The frontend is the second layer, and the two divide the work rather than repeating it: the server owns `&`, and
`sanitizeHeadline.ts` escapes `<` and `>` then restores exactly the two literals `<mark>` and `</mark>`. Escaping `&`
on both sides would be the obvious-looking choice and is wrong — it renders a server-escaped `Tom &amp; Jerry` as the
visible text `Tom &amp; Jerry`. The defense still holds if the server step were ever bypassed: only those two exact
literals come back, so anything carrying an attribute (which needs a space after the tag name) stays inert, and the
worst a hostile excerpt can produce is a bare, attribute-free `<mark>`.
[`HeadlineIT`](../src/test/java/dev/lukasgrigis/booksearch/search/HeadlineIT.java) asserts the SQL property on both the
Criteria and the native path;
[`sanitizeHeadline.test.ts`](../frontend/src/lib/sanitizeHeadline.test.ts) asserts the client half.

Source: [Highlighting results](https://www.postgresql.org/docs/18/textsearch-controls.html#TEXTSEARCH-HEADLINE).

## Typo tolerance: `pg_trgm`

Stemming cannot help with a misspelling: `Shakespere` and `Shakespeare` have different stems, so
`@@` will not match them. Trigram similarity does, by breaking both strings into three-character runs and comparing the
sets.

`pg_trgm` provides `similarity(a, b)` returning 0–1, and the `%` operator, which is true when similarity exceeds
`pg_trgm.similarity_threshold` (default 0.3; this repo sets 0.2 as a session GUC in `application.yaml`). The two forms
are not interchangeable: **`%` can be answered from a
`gin_trgm_ops` index, and `similarity() >= 0.2` cannot**, which is why the predicate uses the operator and only the
ranking uses the function.

Source: [pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html).

## Synonyms: one direction per pair

A synonym dictionary maps one lexeme to another. The file is `source target`, one pair per line, and it must sit in the
server's `$SHAREDIR/tsearch_data` before `CREATE TEXT SEARCH DICTIONARY`
runs. Both `support/compose.yaml` and the Testcontainers setup put it there.

The part that surprises people: **the substitution runs at index time as well as at query time**, because both go
through the same configuration. So listing a pair in both directions is self-cancelling — though not in the way it first
looks. Nothing is mapped back to itself; both sides are swapped to the _other_ word. With `sleuth detective`
and `detective sleuth` both present, the document "the detective solved it" indexes as `sleuth`, and the query
`sleuth` is rewritten to `detective`. Both moved, and they still never meet: a perfectly symmetric dictionary is a no-op
that costs you the feature.
[
`GeneratedColumnAndSynonymDirectionIT`](../src/test/java/dev/lukasgrigis/booksearch/search/GeneratedColumnAndSynonymDirectionIT.java)
proves this directly on that exact pair: with both directions present the match fails, with one direction it succeeds.

It also means a pair only does something if its **target** occurs in the corpus. A pair whose target appears nowhere
silently matches nothing, which looks identical to the feature being broken.
[`SynonymDictionaryCorpusIT`](../src/test/java/dev/lukasgrigis/booksearch/search/SynonymDictionaryCorpusIT.java)
fails the build if that happens.

**Known limitation, not fixed here.** The mapping is `WITH book_synonym, english_stem`, so only the dictionary's exact
key reaches the synonym dictionary. An inflected form such as `apothecaries`
falls through to `english_stem` and never reaches the synonym entry for `apothecary`. The fix is a
[thesaurus dictionary](https://www.postgresql.org/docs/18/textsearch-dictionaries.html#TEXTSEARCH-THESAURUS), which runs
a subdictionary over its input and so handles inflected forms. It is deliberately not implemented: the synonym template
is what makes the index-time/query-time symmetry above visible, and that is the point being demonstrated.

Sources: [Dictionaries](https://www.postgresql.org/docs/18/textsearch-dictionaries.html),
[Configuration example](https://www.postgresql.org/docs/18/textsearch-configuration.html).

## Combining retrievers: Reciprocal Rank Fusion

Three retrievers produce three ranked lists whose scores are not comparable — a `ts_rank_cd` of 0.08 and a trigram
similarity of 0.62 mean nothing to each other. RRF sidesteps that by discarding the scores and using only the positions:

```
RRFscore(d) = Σ  1 / (k + r(d))
             r∈R
```

where `r(d)` is the document's rank in retriever `r`. The paper that introduced the method fixed **k = 60** "during a
pilot investigation and not altered during subsequent validation", and reports that the value was near-optimal but "the
choice was not critical."

A large `k` flattens the difference between the top positions: rank 1 contributes 1/61 = 0.0164 and rank 5 contributes
1/65 = 0.0154. That damping is the mechanism, not a side effect — it stops one retriever's confident top hit from
dominating a document that several retrievers agree on. It is also why the UI draws its provenance bars from rank
position, not from the contribution term, which barely varies.

Source: Cormack, Clarke & Buettcher, [_Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning
Methods_](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf), SIGIR 2009.

## Indexes: GIN, and where each index stops helping

GIN is the index type for `tsvector`; it stores each lexeme once with a posting list of the rows containing it, which is
exactly the lookup `@@` performs.

Two honest caveats about this repo's indexes:

**The functional synonym index is fragile, in two directions.** `idx_book_synonym_vector` indexes
`to_tsvector('book_synonym_search', title || ' ' || excerpt)`. A functional index is only usable if the query's
expression matches it _exactly_, so changing how that expression is built in Java silently drops the index without
breaking any test.

The other direction is worse, because it changes answers, not speed. That index stores lexemes produced by the synonym
dictionary _as it was when the index was built_ — and a database session reads a `.syn` file **once, the first time it
uses the dictionary in that session**. Edit `book_synonym.syn` on a live database and any session that already used the
dictionary keeps the old contents, while a session touching it for the first time sees the new — two connections to the
same database, disagreeing. The manual's trick for the sessions is a dummy
`ALTER TEXT SEARCH DICTIONARY book_synonym ( dummy )` — an option removal that is allowed to remove nothing — which
forces a reload; the index still needs a `REINDEX` on top, because its stored lexemes came from the old dictionary.
Until both have happened, an index scan and a sequential scan of the same table can return different rows for the same
query, with no error anywhere. For this demo the blunt reset does both at once: `mise run infra:down && mise run demo`.

**The trigram index is not used by FUZZY.** The predicate is `book.title % :q OR author.name % :q`, and no single-table
index can answer an `OR` that spans two tables, so the planner falls back to scanning both. The index is kept because it
does serve a single-column trigram match, which is what most readers adapting this will write. On 77 rows none of this
is measurable; at real scale, FUZZY is the retriever that needs rethinking first.

Source: [GIN and GiST index types](https://www.postgresql.org/docs/18/textsearch-indexes.html),
[GIN internals](https://www.postgresql.org/docs/18/gin.html),
[Dictionaries](https://www.postgresql.org/docs/18/textsearch-dictionaries.html) (the per-session read and the dummy
`ALTER` are documented there).

## Appendix: why `PredicateSpecification` cannot rank

Spring Data JPA 4 ships `PredicateSpecification` alongside the classic `Specification`, and it does not replace it for
ranked search. The reason is in the signature, not in the docs: `toPredicate`
never receives the `CriteriaQuery`, and `ORDER BY` lives on the `CriteriaQuery`. Ranking is an
`ORDER BY`, so the newer interface structurally cannot express one.

Checked against the real jar rather than assumed, with static and default members elided:

```
$ javap -cp spring-data-jpa-4.1.0.jar \
    org.springframework.data.jpa.domain.PredicateSpecification
Compiled from "PredicateSpecification.java"
  ...
  public abstract jakarta.persistence.criteria.Predicate toPredicate(jakarta.persistence.criteria.From<?, T>, jakarta.persistence.criteria.CriteriaBuilder);

$ javap -cp spring-data-jpa-4.1.0.jar \
    org.springframework.data.jpa.domain.Specification
Compiled from "Specification.java"
  ...
  public abstract jakarta.persistence.criteria.Predicate toPredicate(jakarta.persistence.criteria.Root<T>, jakarta.persistence.criteria.CriteriaQuery<?>, jakarta.persistence.criteria.CriteriaBuilder);
```

Two arguments versus three. `SearchService` therefore builds its `CriteriaQuery` around the classic three-argument
`Specification`.
