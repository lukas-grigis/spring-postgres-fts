import type { SearchMode } from './api/types';

// Genres are NOT hardcoded here — a fixed list drifted from the seed before (SSOT violation:
// 8 of 12 entries matched no row, and the genre holding a third of the corpus was unreachable).
// GenreFilter fetches the real list from GET /api/genres via useGenres.

export const MODE_LABELS: Record<SearchMode, string> = {
  LEXICAL: 'Lexical',
  FUZZY: 'Fuzzy',
  SYNONYM: 'Synonym',
  FUSED: 'Fused',
};

/** Plain sentence first, Postgres mechanism second — a reader should not need the jargon to choose. */
export const MODE_DESCRIPTIONS: Record<SearchMode, string> = {
  LEXICAL: 'Matches words and their stems. Ranked by ts_rank_cd over a GIN-indexed tsvector.',
  FUZZY:
    'Matches misspellings by letter overlap, so a typo still finds the book. pg_trgm similarity.',
  SYNONYM:
    'Matches books that never contain your word, via a synonym dictionary in the text-search config.',
  FUSED: 'Blends all three by Reciprocal Rank Fusion, and shows what each retriever contributed.',
};

/**
 * Each example is chosen to make ONE retriever visibly earn its place, and ships with the mode
 * that shows it off. Every query here is asserted against the seeded corpus by
 * SynonymDictionaryCorpusIT (the synonym one) and by hand via `mise run check` — a demo that
 * suggests a query returning nothing is worse than one that suggests nothing at all.
 */
export const EXAMPLE_QUERIES: ReadonlyArray<{ q: string; mode: SearchMode; hint: string }> = [
  { q: 'whale', mode: 'LEXICAL', hint: 'plain word match' },
  { q: 'Shakespere', mode: 'FUZZY', hint: 'misspelled on purpose' },
  { q: 'casement', mode: 'SYNONYM', hint: 'no book says it' },
  { q: 'great house', mode: 'FUSED', hint: 'all three, blended' },
];
