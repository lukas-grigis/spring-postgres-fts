package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.AuthorEntity;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import dev.lukasgrigis.booksearch.domain.GenreEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim: {@code ts_headline} returns a {@code <mark>}-wrapped snippet, not the whole excerpt —
 * the counter-check compares the headline's length against the source excerpt's length for the
 * same row, so the test fails if {@code MaxWords}/{@code MinWords} ever stopped trimming.
 */
class HeadlineIT extends AbstractPersistenceTest {

    // ts_headline preserves the source word's original casing/inflection ("Time", "times", ...)
    // inside <mark> — the literal StartSel/StopSel tags themselves are fixed-case (we chose them).
    private static final Pattern MARKED_TIME_WORD = Pattern.compile("<mark>[Tt]ime\\w*</mark>");

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BookRepository bookRepository;

    private static void assertEscapedAndOnlyMarkTags(String headline) {
        assertThat(headline).as("script tag must be escaped, not live markup").contains("&lt;script&gt;");
        assertThat(headline).as("& must be escaped").contains("&amp;");
        assertThat(headline).as("no raw script tag may survive").doesNotContain("<script");
        String withoutMarkTags = headline.replace("<mark>", "").replace("</mark>", "");
        assertThat(withoutMarkTags).as("<mark> must be the only real tag in the output")
                .doesNotContain("<")
                .doesNotContain(">");
    }

    /**
     * Claim: {@code ts_headline} does not escape the source text, so {@link
     * BookSearchExpressions#escapedExcerpt} and the equivalent {@code replace(replace(replace(...)))}
     * chain in {@link BookRepository#findFused} must both neutralise HTML metacharacters before
     * {@code ts_headline} ever sees them — otherwise a hostile excerpt is live markup in the
     * response. Exercised through both retrieval paths since the escaping is duplicated in two
     * places (see docs/POSTGRES-FTS.md, "ts_headline does not escape").
     */
    @Test
    void headlineEscapesHtmlMetacharactersInBothRetrievalPaths() {
        AuthorEntity author = new AuthorEntity("XSS Fixture Author");
        GenreEntity genre = entityManager.createQuery(
                        "select g from GenreEntity g where g.name = :name", GenreEntity.class)
                .setParameter("name", "Gothic")
                .getSingleResult();
        entityManager.persist(author);
        BookEntity book = new BookEntity(
                "The XSS Fixture",
                "A hollowxss <script>alert(1)</script> & <b>bold</b> claim about the old house.",
                1900, "https://example.invalid/xss", author, genre
        );
        entityManager.persist(book);
        entityManager.flush();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(cb.tuple(BookSearchExpressions.headline(SearchMode.LEXICAL, root, cb, "hollowxss")));
        cq.where(BookSearchExpressions.matches(SearchMode.LEXICAL, "hollowxss").toPredicate(root, cq, cb));
        String criteriaHeadline = entityManager.createQuery(cq).getSingleResult().get(0, String.class);
        assertEscapedAndOnlyMarkTags(criteriaHeadline);

        List<FusedSearchRow> fused = bookRepository.findFused(
                "hollowxss",
                null,
                SearchService.RRF_K,
                50,
                PageRequest.of(0, 10)
        ).getContent();
        assertThat(fused).isNotEmpty();
        assertEscapedAndOnlyMarkTags(fused.get(0).getHeadline());
    }

    @Test
    void headlineIsMarkedAndShorterThanTheSourceExcerpt() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(cb.tuple(root.get("excerpt"), BookSearchExpressions.headline(SearchMode.LEXICAL, root, cb, "time")));
        // search_vector is title+excerpt combined, but ts_headline here only scans the excerpt —
        // a title-only match ("Hard Times" contains "time") would legitimately produce an
        // unmarked headline, which is a real gap, not something to launder in the test. Scope to
        // rows where the excerpt itself contains the word, so this test verifies the marking
        // claim on the case it actually applies to.
        cq.where(cb.and(
                BookSearchExpressions.matches(SearchMode.LEXICAL, "time").toPredicate(root, cq, cb),
                cb.like(cb.lower(root.get("excerpt")), "%time%")
        ));

        List<Tuple> rows = entityManager.createQuery(cq).setMaxResults(20).getResultList();
        assertThat(rows).isNotEmpty();

        for (Tuple row : rows) {
            String excerpt = row.get(0, String.class);
            String headline = row.get(1, String.class);
            assertThat(MARKED_TIME_WORD.matcher(headline).find())
                    .as("ts_headline must wrap the matched word in <mark>: " + headline)
                    .isTrue();
            assertThat(headline.length())
                    .as("headline must be a snippet, strictly shorter than the full excerpt it was cut from")
                    .isLessThan(excerpt.length());
        }
    }

}
