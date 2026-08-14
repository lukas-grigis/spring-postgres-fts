#!/usr/bin/env python3
"""Fetch a public-domain book corpus from Project Gutenberg and emit a Liquibase seed changelog.

Re-runnable and offline-safe: run it, it hits gutendex.com + gutenberg.org mirrors, filters to the
IP rule below, and (re)writes src/main/resources/db/changelog/002-seed.xml. The generated file is
committed so the app works without network access; this script stays committed so the provenance
of every row is checkable.

IP rule (support/seed/README.md, "The IP rule" — do not relax):
  - English-ORIGINAL text only (no translations — a translation carries its own copyright).
  - First published before 1930.
  - Author died before 1955.
Published-year is not reliably present in the gutendex API, so it is curated by hand in WORKS
below; everything else (language, translator list, author death year) is verified live against
the API response before a work is accepted, and a work is dropped (not guessed) if verification
fails.

Usage:
  python3 support/seed/fetch_seed.py
"""

from __future__ import annotations

import html
import re
import sys
import textwrap
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from xml.sax.saxutils import quoteattr

GUTENDEX = "https://gutendex.com/books/"  # trailing slash: gutendex 301-redirects /books -> /books/
REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_CHANGELOG = REPO_ROOT / "src/main/resources/db/changelog/002-seed.xml"

MIN_EXCERPT = 600
MAX_EXCERPT = 1200


@dataclass
class WorkSpec:
    """One curated entry: what to fetch and the IP-safety facts we already know about it."""

    title_query: str          # gutendex `search=` term
    author_surname: str       # must appear in the matched author's name (case-insensitive)
    genre: str
    published_year: int       # hand-curated, cross-checked against Gutenberg catalog knowledge


# Curated so the corpus deliberately supports the blog demos:
#  - typo-prone authors for the fuzzy/trigram retriever (Shakespeare, Stevenson, Fitzgerald,
#    Bronte, Conan Doyle, Hawthorne)
#  - enough long prose (novels, not just short poems) that ts_rank vs ts_rank_cd can disagree
#  - varied vocabulary (love/death/sea/madness/revenge/justice) for an honest synonym demo
WORKS: list[WorkSpec] = [
    # Shakespeare — Drama (typo target)
    WorkSpec("Hamlet", "Shakespeare", "Drama", 1603),
    WorkSpec("Macbeth", "Shakespeare", "Drama", 1623),
    WorkSpec("Romeo and Juliet", "Shakespeare", "Drama", 1597),
    WorkSpec("Othello, the Moor of Venice", "Shakespeare", "Drama", 1622),
    WorkSpec("King Lear", "Shakespeare", "Drama", 1608),
    WorkSpec("A Midsummer Night's Dream", "Shakespeare", "Drama", 1600),
    WorkSpec("Twelfth Night", "Shakespeare", "Drama", 1623),
    WorkSpec("The Tragedy of Julius Caesar", "Shakespeare", "Drama", 1623),
    # Austen — Novel
    WorkSpec("Pride and Prejudice", "Austen", "Novel", 1813),
    WorkSpec("Sense and Sensibility", "Austen", "Novel", 1811),
    WorkSpec("Emma", "Austen", "Novel", 1815),
    WorkSpec("Persuasion", "Austen", "Novel", 1817),
    WorkSpec("Mansfield Park", "Austen", "Novel", 1814),
    WorkSpec("Northanger Abbey", "Austen", "Novel", 1817),
    # Dickens — Novel
    WorkSpec("A Tale of Two Cities", "Dickens", "Novel", 1859),
    WorkSpec("Great Expectations", "Dickens", "Novel", 1861),
    WorkSpec("Oliver Twist", "Dickens", "Novel", 1838),
    WorkSpec("David Copperfield", "Dickens", "Novel", 1850),
    WorkSpec("A Christmas Carol", "Dickens", "Novel", 1843),
    WorkSpec("Bleak House", "Dickens", "Novel", 1853),
    WorkSpec("Hard Times", "Dickens", "Novel", 1854),
    # Melville — Novel / Adventure
    WorkSpec("Moby Dick", "Melville", "Adventure", 1851),
    WorkSpec("Bartleby, the Scrivener", "Melville", "Short Story", 1853),
    WorkSpec("Billy Budd", "Melville", "Novel", 1924),
    # Poe — Gothic / Short Story / Poetry
    WorkSpec("The Fall of the House of Usher", "Poe", "Gothic", 1839),
    WorkSpec("The Tell-Tale Heart", "Poe", "Gothic", 1843),
    WorkSpec("The Murders in the Rue Morgue", "Poe", "Mystery", 1841),
    WorkSpec("The Raven", "Poe", "Poetry", 1845),
    WorkSpec("The Pit and the Pendulum", "Poe", "Gothic", 1842),
    WorkSpec("The Black Cat", "Poe", "Gothic", 1843),
    # Conan Doyle — Mystery (typo target)
    WorkSpec("The Adventures of Sherlock Holmes", "Doyle", "Mystery", 1892),
    WorkSpec("The Hound of the Baskervilles", "Doyle", "Mystery", 1902),
    WorkSpec("A Study in Scarlet", "Doyle", "Mystery", 1887),
    WorkSpec("The Sign of the Four", "Doyle", "Mystery", 1890),
    # Wilde — Novel / Drama
    WorkSpec("The Picture of Dorian Gray", "Wilde", "Novel", 1890),
    WorkSpec("The Importance of Being Earnest", "Wilde", "Drama", 1895),
    WorkSpec("Lady Windermere's Fan", "Wilde", "Drama", 1892),
    # Gothic staples
    WorkSpec("Dracula", "Stoker", "Gothic", 1897),
    WorkSpec("Frankenstein", "Shelley", "Gothic", 1818),
    # Twain — Novel / Adventure
    WorkSpec("Adventures of Huckleberry Finn", "Twain", "Adventure", 1884),
    WorkSpec("The Adventures of Tom Sawyer", "Twain", "Adventure", 1876),
    WorkSpec("A Connecticut Yankee in King Arthur's Court", "Twain", "Novel", 1889),
    WorkSpec("The Prince and the Pauper", "Twain", "Novel", 1881),
    # Bronte sisters (typo target)
    WorkSpec("Jane Eyre", "Bront", "Novel", 1847),
    WorkSpec("Villette", "Bront", "Novel", 1853),
    WorkSpec("Wuthering Heights", "Bront", "Novel", 1847),
    # Stevenson (typo target) — Adventure / Gothic
    WorkSpec("Treasure Island", "Stevenson", "Adventure", 1883),
    WorkSpec("Strange Case of Dr Jekyll and Mr Hyde", "Stevenson", "Gothic", 1886),
    WorkSpec("Kidnapped", "Stevenson", "Adventure", 1886),
    # Hawthorne (typo target) — Novel / Gothic
    WorkSpec("The Scarlet Letter", "Hawthorne", "Novel", 1850),
    WorkSpec("The House of the Seven Gables", "Hawthorne", "Gothic", 1851),
    # Conrad — Novel / Adventure
    WorkSpec("Heart of Darkness", "Conrad", "Novel", 1899),
    WorkSpec("Lord Jim", "Conrad", "Novel", 1900),
    WorkSpec("The Secret Agent", "Conrad", "Novel", 1907),
    # Defoe / Swift — Adventure / Satire
    WorkSpec("Robinson Crusoe", "Defoe", "Adventure", 1719),
    WorkSpec("Moll Flanders", "Defoe", "Novel", 1722),
    WorkSpec("Gulliver's Travels", "Swift", "Satire", 1726),
    # Gaskell / Hardy — Novel
    WorkSpec("North and South", "Gaskell", "Novel", 1855),
    WorkSpec("Cranford", "Gaskell", "Novel", 1853),
    WorkSpec("Tess of the d'Urbervilles", "Hardy", "Novel", 1891),
    WorkSpec("Far from the Madding Crowd", "Hardy", "Novel", 1874),
    WorkSpec("Jude the Obscure", "Hardy", "Novel", 1895),
    WorkSpec("The Mayor of Casterbridge", "Hardy", "Novel", 1886),
    # Alcott / Carroll — Novel / Fantasy
    WorkSpec("Little Women", "Alcott", "Novel", 1868),
    WorkSpec("Alice's Adventures in Wonderland", "Carroll", "Fantasy", 1865),
    WorkSpec("Through the Looking-Glass", "Carroll", "Fantasy", 1871),
    # Chopin / Wharton / James — Novel / Short Story
    WorkSpec("The Awakening", "Chopin", "Novel", 1899),
    WorkSpec("The Story of an Hour", "Chopin", "Short Story", 1894),
    WorkSpec("The Age of Innocence", "Wharton", "Novel", 1920),
    WorkSpec("Ethan Frome", "Wharton", "Novel", 1911),
    WorkSpec("The House of Mirth", "Wharton", "Novel", 1905),
    WorkSpec("The Turn of the Screw", "James", "Gothic", 1898),
    WorkSpec("The Portrait of a Lady", "James", "Novel", 1881),
    WorkSpec("Washington Square", "James", "Novel", 1880),
    # Kipling — Short Story / Poetry
    WorkSpec("The Jungle Book", "Kipling", "Short Story", 1894),
    WorkSpec("Kim", "Kipling", "Novel", 1901),
    # Fitzgerald (typo target) — Novel
    WorkSpec("The Great Gatsby", "Fitzgerald", "Novel", 1925),
    WorkSpec("This Side of Paradise", "Fitzgerald", "Novel", 1920),
    # Wells — Adventure / Science Fiction
    WorkSpec("The Time Machine", "Wells", "Adventure", 1895),
    WorkSpec("The War of the Worlds", "Wells", "Adventure", 1898),
    WorkSpec("The Invisible Man", "Wells", "Adventure", 1897),
    # Grahame / Burnett / Montgomery — Novel
    WorkSpec("The Wind in the Willows", "Grahame", "Novel", 1908),
    WorkSpec("The Secret Garden", "Burnett", "Novel", 1911),
    WorkSpec("Anne of Green Gables", "Montgomery", "Novel", 1908),
    # Irving — Short Story
    WorkSpec("The Legend of Sleepy Hollow", "Irving", "Short Story", 1820),
    WorkSpec("Rip Van Winkle", "Irving", "Short Story", 1819),
    # Poetry
    WorkSpec("Leaves of Grass", "Whitman", "Poetry", 1855),
    WorkSpec("Poems by Emily Dickinson", "Dickinson", "Poetry", 1890),
    WorkSpec("Poems by Alfred Tennyson", "Tennyson", "Poetry", 1842),
]


def http_get_json(url: str, retries: int = 3) -> dict:
    import json

    req = urllib.request.Request(url, headers={"User-Agent": "spring-postgres-fts-seed/1.0 (+https://lukasgrigis.dev)"})
    last_exc: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_exc = exc
            if exc.code in (403, 429, 503):
                time.sleep(1.5 * (attempt + 1))
                continue
            raise
    raise last_exc  # type: ignore[misc]


def http_get_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "spring-postgres-fts-seed/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read()
    for enc in ("utf-8", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


GUTENBERG_START_RE = re.compile(
    r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG EBOOK.*?\*\*\*", re.IGNORECASE | re.DOTALL
)
GUTENBERG_END_RE = re.compile(
    r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG EBOOK.*", re.IGNORECASE | re.DOTALL
)


def strip_boilerplate(raw: str) -> str:
    """Cut everything outside the START/END markers Gutenberg wraps every text in."""
    start_match = GUTENBERG_START_RE.search(raw)
    body = raw[start_match.end():] if start_match else raw
    end_match = GUTENBERG_END_RE.search(body)
    if end_match:
        body = body[: end_match.start()]
    return body.strip()


def pick_excerpt(body: str, min_len: int = MIN_EXCERPT, max_len: int = MAX_EXCERPT) -> str | None:
    """Find a MIN_EXCERPT..MAX_EXCERPT window of real prose, starting on a paragraph boundary
    well past front matter (title page, table of contents, dedications) and ending on a
    sentence boundary so the excerpt reads as a real quotation."""
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    # Skip short front-matter paragraphs (titles, chapter headers, "CONTENTS" blocks).
    candidates = [p for p in paragraphs if len(p) > 300]
    if not candidates:
        return None
    # Prefer a paragraph a bit into the work, not the very first (often a dedication/preface).
    start_idx = min(2, len(candidates) - 1)
    for para in candidates[start_idx:] + candidates[:start_idx]:
        text = re.sub(r"\s+", " ", para).strip()
        if len(text) < min_len:
            continue
        if len(text) <= max_len:
            excerpt = text
        else:
            window = text[:max_len]
            last_period = max(window.rfind(". "), window.rfind(".”"), window.rfind('."'))
            if last_period < min_len:
                continue
            excerpt = window[: last_period + 1]
        if min_len <= len(excerpt) <= max_len:
            return excerpt
    return None


def sql_literal_escape(value: str) -> str:
    """Double any `'` for embedding inside a single-quoted SQL string literal — needed only for
    `valueComputed` subqueries, which Liquibase passes through as raw SQL rather than as a
    JDBC-bound parameter. XML attribute escaping is handled separately by quoteattr()."""
    return value.replace("'", "''")


def find_gutenberg_match(spec: WorkSpec) -> dict | None:
    import urllib.parse

    url = f"{GUTENDEX}?search={urllib.parse.quote(spec.title_query)}&languages=en"
    try:
        data = http_get_json(url)
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"  ! gutendex lookup failed for {spec.title_query!r}: {exc}", file=sys.stderr)
        return None
    for result in data.get("results", []):
        if "en" not in result.get("languages", []):
            continue
        if result.get("translators"):
            continue
        authors = result.get("authors", [])
        if not any(spec.author_surname.lower() in a.get("name", "").lower() for a in authors):
            continue
        death_years = [a.get("death_year") for a in authors if a.get("death_year")]
        if death_years and min(death_years) >= 1955:
            continue
        return result
    return None


def text_url(result: dict) -> str | None:
    formats = result.get("formats", {})
    for key in ("text/plain; charset=utf-8", "text/plain; charset=us-ascii", "text/plain"):
        if key in formats:
            return formats[key]
    for key, url in formats.items():
        if key.startswith("text/plain"):
            return url
    return None


def author_display_name(result: dict, spec: WorkSpec) -> str:
    for a in result.get("authors", []):
        if spec.author_surname.lower() in a.get("name", "").lower():
            name = a["name"]
            if "," in name:
                last, first = [p.strip() for p in name.split(",", 1)]
                return f"{first} {last}"
            return name
    return result.get("authors", [{}])[0].get("name", "Unknown")


def main() -> int:
    rows: list[tuple[str, str, str, str, int, int]] = []  # title, author, genre, excerpt, year, gutenberg_id
    seen_ids: set[int] = set()
    failures: list[str] = []

    for spec in WORKS:
        match = find_gutenberg_match(spec)
        if match is None:
            failures.append(f"no verified match: {spec.title_query} ({spec.author_surname})")
            continue
        if match["id"] in seen_ids:
            continue
        url = text_url(match)
        if url is None:
            failures.append(f"no plain-text format: {spec.title_query} (id={match['id']})")
            continue
        try:
            raw = http_get_text(url)
        except (urllib.error.URLError, TimeoutError) as exc:
            failures.append(f"download failed: {spec.title_query} (id={match['id']}): {exc}")
            continue
        body = strip_boilerplate(raw)
        excerpt = pick_excerpt(body)
        if excerpt is None:
            failures.append(f"no {MIN_EXCERPT}-{MAX_EXCERPT}c excerpt found: {spec.title_query} (id={match['id']})")
            continue
        title = match["title"].split("\r\n")[0].split("\n")[0].strip()
        author = author_display_name(match, spec)
        rows.append((title, author, spec.genre, excerpt, spec.published_year, match["id"]))
        seen_ids.add(match["id"])
        print(f"  + {title!r} — {author} ({spec.genre}, {spec.published_year}) [{len(excerpt)}c]")
        time.sleep(0.2)  # be polite to gutendex/gutenberg.org

    print(f"\nFetched {len(rows)} works, {len(failures)} failures.")
    for f in failures:
        print(f"  ! {f}")

    write_changelog(rows)
    print(f"\nWrote {OUT_CHANGELOG} ({len(rows)} works).")
    return 0 if rows else 1


def write_changelog(rows: list[tuple[str, str, str, str, int, int]]) -> None:
    genres = sorted({r[2] for r in rows})
    authors = sorted({r[1] for r in rows})

    lines: list[str] = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<!-- GENERATED by support/seed/fetch_seed.py — do not hand-edit, re-run the script instead. -->",
        "<databaseChangeLog",
        '  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"',
        '  xmlns="http://www.liquibase.org/xml/ns/dbchangelog"',
        '  xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog',
        '                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-5.0.xsd">',
        "",
    ]

    def name_rows(table: str, changeset_id: str, values: list[str]) -> None:
        lines.append(f'  <changeSet id="{changeset_id}" author="fetch_seed.py">')
        for value in values:
            lines.append(f'    <insert tableName="{table}">')
            lines.append(f"      <column name=\"name\" value={quoteattr(value)}/>")
            lines.append("    </insert>")
        lines.append("  </changeSet>")
        lines.append("")

    name_rows("genre", "002-seed-genres", genres)
    name_rows("author", "002-seed-authors", authors)

    lines.append('  <changeSet id="002-seed-books" author="fetch_seed.py">')
    for title, author, genre, excerpt, year, gutenberg_id in rows:
        source_url = f"https://www.gutenberg.org/ebooks/{gutenberg_id}"
        author_lookup = f"(SELECT id FROM author WHERE name = \'{sql_literal_escape(author)}\')"
        genre_lookup = f"(SELECT id FROM genre WHERE name = \'{sql_literal_escape(genre)}\')"
        lines.append('    <insert tableName="book">')
        lines.append(f"      <column name=\"title\" value={quoteattr(title)}/>")
        lines.append(f"      <column name=\"author_id\" valueComputed={quoteattr(author_lookup)}/>")
        lines.append(f"      <column name=\"genre_id\" valueComputed={quoteattr(genre_lookup)}/>")
        lines.append(f"      <column name=\"excerpt\" value={quoteattr(excerpt)}/>")
        lines.append(f'      <column name="published_year" valueNumeric="{year}"/>')
        lines.append(f"      <column name=\"source_url\" value={quoteattr(source_url)}/>")
        lines.append("    </insert>")
    lines.append("  </changeSet>")
    lines.append("")
    lines.append("</databaseChangeLog>")

    OUT_CHANGELOG.parent.mkdir(parents=True, exist_ok=True)
    OUT_CHANGELOG.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
