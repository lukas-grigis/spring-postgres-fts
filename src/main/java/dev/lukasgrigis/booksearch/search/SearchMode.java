package dev.lukasgrigis.booksearch.search;

/**
 * The three retrievers, plus FUSED — the Reciprocal Rank Fusion of all three.
 */
public enum SearchMode {
    LEXICAL,
    FUZZY,
    SYNONYM,
    FUSED
}
