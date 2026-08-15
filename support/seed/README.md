# Seed corpus — where the books come from

The search demo is only honest if the text it searches is real. This directory holds the script that builds the corpus
and the rule that decides what is allowed into it.

## The IP rule

Only works that are unambiguously public domain in both the US and the EU:

- **English original.** No translations — a translation carries its own copyright. Kafka died in 1924 and is public
  domain; the Muir translation of _Die Verwandlung_ is not. This rule is why the canon here is Anglophone rather than
  the wider school-reading list.
- **First published before 1930.**
- **Author died before 1955** (life + 70 clears the EU term).

Excerpts are the **real work text**, not a description of it. That matters twice over: a publisher's blurb is
copyrighted, and a Wikipedia plot summary is CC BY-SA, whose share-alike term would infect this repo's MIT license.

## What the script does

```bash
mise run seed          # or: python3 support/seed/fetch_seed.py
```

`fetch_seed.py` reads its curated work list, resolves each entry against the
[Gutendex](https://gutendex.com) API, downloads the plain text from a Project Gutenberg mirror, strips the Gutenberg
header and footer boilerplate, cuts a 600–1,200 character excerpt at a sentence boundary, and writes
`src/main/resources/db/changelog/002-seed.xml`.

Verification is live, not assumed: language, translator list and author death year are checked against the API response
before a work is accepted, and a work that fails verification is **dropped rather than guessed**. The published year is
the one field Gutendex does not carry reliably, so it is curated by hand in the `WORKS` list.

## Why the generated file is committed

`002-seed.xml` is committed so `mise run demo` works with no network access and every reader gets byte-identical
results. The script is committed so the provenance of any single row can be checked, and so the corpus can be
regenerated or extended without reverse-engineering the generated changelog.

## Why these particular works

The corpus is curated so the three retrievers have something honest to prove, not so the demo looks good:

- **Fuzzy** needs author names that are genuinely misspelled in the wild — Shakespeare, Stevenson, Fitzgerald, Brontë,
  Conan Doyle. `Shakespere` and `Stevensen` are the kind of typo no stemmer can ever reach.
- **Lexical ranking** needs long, dense prose, or `ts_rank` and `ts_rank_cd` never disagree and the ranking test would
  pass vacuously.
- **Synonym** needs varied period vocabulary, so a dictionary entry has real work to do.

77 works across 9 genres. Big enough that ranking differences are not coincidence, small enough that the whole thing
seeds in under a second.

## Excerpt length and `ts_headline`

The 600–1,200 character band is not cosmetic. `ts_headline` returns a _snippet_; hand it a one-line blurb and it returns
the whole thing, which looks like a bug. The corpus is built to a length where highlighting is visibly doing something —
and `HeadlineIT` asserts exactly that.
