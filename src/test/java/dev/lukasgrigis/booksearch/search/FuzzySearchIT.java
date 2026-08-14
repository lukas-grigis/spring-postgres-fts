package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzySearchIT extends AbstractPersistenceTest {

    @Autowired
    private BookRepository bookRepository;

    @Test
    void trigramFindsTheTypoLexicalDoesNot() {
        String typo = "Shakespere"; // Shakespeare

        List<BookEntity> fuzzyHits = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.FUZZY, typo));
        List<BookEntity> lexicalHits = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.LEXICAL, typo));

        assertThat(lexicalHits)
                .as("the stemmer must NOT match a misspelled name it has never seen — this is the counter-check")
                .isEmpty();
        assertThat(fuzzyHits)
                .as("pg_trgm must find the real author despite the typo")
                .isNotEmpty()
                .allSatisfy(book -> assertThat(book.getAuthor().getName()).contains("Shakespeare"));
    }

    @Test
    void trigramFindsPartialNames() {
        String partial = "Fitzgera";

        List<BookEntity> fuzzyHits = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.FUZZY, partial));

        assertThat(fuzzyHits)
                .as("a partial surname must still surface the real author via trigram similarity")
                .isNotEmpty()
                .allSatisfy(book -> assertThat(book.getAuthor().getName()).contains("Fitzgerald"));
    }

    @Test
    void trigramFindsTheTypoThroughFusedToo() {
        String typo = "Stevensen"; // Robert Louis Stevenson

        List<BookEntity> fuzzyHits = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.FUZZY, typo));
        assertThat(fuzzyHits)
                .as("fixture sanity: the standalone FUZZY retriever must find the typo")
                .isNotEmpty()
                .allSatisfy(book -> assertThat(book.getAuthor().getName()).contains("Stevenson"));

        List<FusedSearchRow> fused = bookRepository.findFused(
                typo,
                null,
                SearchService.RRF_K,
                50,
                PageRequest.of(0, 10)
        ).getContent();
        assertThat(fused)
                .as("FUSED must surface the same typo via the fuzzy CTE — same operator, same threshold as the Criteria path")
                .isNotEmpty()
                .anySatisfy(row -> assertThat(row.getAuthorName()).contains("Stevenson"));
    }

}
