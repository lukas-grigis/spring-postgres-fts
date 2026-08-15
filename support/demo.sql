-- Every retriever in this repo, demonstrated in plain SQL against the seeded corpus.
--
-- No application involved: the three retrievers are Postgres features, and this file proves it
-- before any Java enters the picture. The companion blog post shows the output of this exact file.
--
--   mise run infra:up && mise run app     # once, so Liquibase creates the schema and seeds it
--   mise run sql                          # or: psql -h localhost -U booksearch -d booksearch \
--                                         --        -e -f support/demo.sql
--
-- Run with -e so psql echoes each statement above its result, which is how the output is quoted.

\echo
'=== 1. LEXICAL: stemmed match over the stored tsvector, ranked by cover density ==='

SELECT b.title, round(ts_rank_cd(b.search_vector, websearch_to_tsquery('english', 'whale'))::NUMERIC, 4) AS rank
FROM book b
WHERE b.search_vector @@ websearch_to_tsquery('english', 'whale')
ORDER BY rank DESC, b.id;

\echo
'=== 2. LEXICAL: websearch_to_tsquery is the parser you can hand a search box ==='

-- Quoted phrases become the followed-by operator, a leading - becomes negation, and malformed
-- input never throws. to_tsquery would raise a syntax error on the same string.
SELECT websearch_to_tsquery('english', 'whale "white sea" -captain');

\echo
'=== 3. LEXICAL: ts_rank and ts_rank_cd disagree on the same match set ==='

-- Frequency ranking puts 20, 21, 3 in positions three to five; cover density makes it 3, 20, 21,
-- because Romeo and Juliet mentions the terms rarely but close together.
SELECT b.id,
       b.title,
       round(ts_rank(b.search_vector, websearch_to_tsquery('english', 'old time'))::NUMERIC, 5)    AS ts_rank,
       round(ts_rank_cd(b.search_vector, websearch_to_tsquery('english', 'old time'))::NUMERIC, 5) AS ts_rank_cd
FROM book b
WHERE b.search_vector @@ websearch_to_tsquery('english', 'old time')
ORDER BY ts_rank DESC, b.id;

\echo
'=== 4. FUZZY: the stemmer cannot reach a misspelling, trigrams can ==='

-- Nothing: Stevensen and Stevenson stem to different lexemes, so @@ will never connect them.
SELECT b.title
FROM book b
WHERE b.search_vector @@ websearch_to_tsquery('english', 'Stevensen');

-- The % operator compares against pg_trgm.similarity_threshold, which defaults to 0.3. The pair
-- scores 0.2692, so without this SET the next query returns nothing. The application sets the same
-- value per pooled connection via spring.datasource.hikari.connection-init-sql.
SET
pg_trgm.similarity_threshold = 0.2;

SELECT b.title, round(similarity(a.name, 'Stevensen')::NUMERIC, 4) AS sim
FROM book b
         JOIN author a ON a.id = b.author_id
WHERE a.name % 'Stevensen'
ORDER BY sim DESC, b.id;

\echo
'=== 5. SYNONYM: a query word that appears in no book still returns six ==='

-- book_synonym.syn maps `casement window` in one direction only. The substitution runs at index
-- time as well as at query time, which is why one direction is enough and two would cancel out.
SELECT b.title
FROM book b
WHERE to_tsvector('book_synonym_search', b.title || ' ' || b.excerpt)
                  @
    @ websearch_to_tsquery('book_synonym_search'
    , 'casement');

-- Counter-check: the word itself occurs nowhere in the corpus.
SELECT COUNT(*) AS rows_containing_the_literal_word
FROM book
WHERE title ILIKE '%casement%' OR excerpt ILIKE '%casement%';
