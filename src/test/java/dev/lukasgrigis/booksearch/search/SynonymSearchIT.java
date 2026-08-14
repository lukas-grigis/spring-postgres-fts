package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.AuthorEntity;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import dev.lukasgrigis.booksearch.domain.GenreEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SynonymSearchIT extends AbstractPersistenceTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BookRepository bookRepository;

    private Long windowBookId;

    @BeforeEach
    void insertWindowBook() {
        AuthorEntity author = new AuthorEntity("Synonym Test Author");
        GenreEntity genre = entityManager.createQuery(
                "select g from GenreEntity g where g.name = :name",
                GenreEntity.class
        ).setParameter("name", "Gothic").getSingleResult();
        entityManager.persist(author);
        BookEntity book = new BookEntity(
                "The Synonym Fixture",
                "A candle burned in the window all night, and no one came to the house.",
                1900,
                "https://example.invalid/fixture",
                author,
                genre
        );
        entityManager.persist(book);
        entityManager.flush();
        windowBookId = book.getId();
    }

    @Test
    void synonymConfigFindsTheFixtureByItsSynonymPlainEnglishDoesNot() {
        List<BookEntity> synonymHits = bookRepository.findAll(BookSearchExpressions.matches(
                SearchMode.SYNONYM,
                "casement"
        ));
        List<BookEntity> lexicalHits = bookRepository.findAll(BookSearchExpressions.matches(
                SearchMode.LEXICAL,
                "casement"
        ));

        assertThat(lexicalHits).as("counter-check: plain english must not match a document that never says 'casement'")
                .noneMatch(book -> book.getId().equals(windowBookId));
        assertThat(synonymHits).as("the synonym config must match the fixture via casement -> window")
                .extracting(BookEntity::getId)
                .contains(windowBookId);
    }

    @Test
    void fusedRowFoundOnlyBySynonymStillCarriesAMarkHighlight() {
        List<FusedSearchRow> fused = bookRepository.findFused(
                "casement",
                null,
                SearchService.RRF_K,
                50,
                PageRequest.of(0, 10)
        ).getContent();

        FusedSearchRow row = fused.stream()
                .filter(r -> r.getBookId().equals(windowBookId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture row must be reachable through FUSED via the synonym retriever"));

        assertThat(row.getLexicalRank()).as("fixture sanity: not a lexical hit").isNull();
        assertThat(row.getSynonymRank()).as("fixture sanity: must be a synonym hit").isNotNull();
        assertThat(row.getHeadline()).as("synonym-only rows must still be highlighted").contains("<mark>");
    }

}
