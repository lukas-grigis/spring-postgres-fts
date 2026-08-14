package dev.lukasgrigis.booksearch.search;

public record RankContribution(
        SearchMode retriever,
        Integer rank,
        Double contribution
) {

}
