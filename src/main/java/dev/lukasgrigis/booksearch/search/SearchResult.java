package dev.lukasgrigis.booksearch.search;

import java.util.List;

public record SearchResult(
        Long id,
        String title,
        String author,
        String genre,
        String headline,
        double score,
        List<RankContribution> rankContributions
) {

    public SearchResult(Long id, String title, String author, String genre, String headline, double score) {
        this(id, title, author, genre, headline, score, List.of());
    }

}
