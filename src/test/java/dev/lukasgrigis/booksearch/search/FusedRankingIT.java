package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.AuthorEntity;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import dev.lukasgrigis.booksearch.domain.GenreEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class FusedRankingIT extends AbstractPersistenceTest {

    private static final String QUERY = "beacon";
    private static final String GENRE = "ZZZ Fusion Test Fixture";

    @Autowired
    private SearchService searchService;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BookRepository bookRepository;

    private Long specialistLexicalId;
    private Long specialistFuzzyId;
    private Long generalistId;

    @BeforeEach
    void insertFixtureRows() {
        GenreEntity genre = new GenreEntity(GENRE);
        entityManager.persist(genre);
        AuthorEntity author = new AuthorEntity("Fixture Author");
        entityManager.persist(author);

        BookEntity specialistLexical = new BookEntity(
                "Coastal Notes",
                "A beacon, beacon, beacon, beacon, beacon signalled across the water every night.",
                1900, "https://example.invalid/lex", author, genre
        );
        BookEntity specialistFuzzy = new BookEntity(
                "Beacon",
                "The lamplighter climbed the tower stairs at dusk and lit the lamp for the ships.",
                1900, "https://example.invalid/fuzzy", author, genre
        );
        BookEntity generalist = new BookEntity(
                "Old Beacon Light",
                "A beacon flashed twice, its beacon reflected far out over the dark water.",
                1900, "https://example.invalid/gen", author, genre
        );

        entityManager.persist(specialistLexical);
        entityManager.persist(specialistFuzzy);
        entityManager.persist(generalist);
        entityManager.flush();

        specialistLexicalId = specialistLexical.getId();
        specialistFuzzyId = specialistFuzzy.getId();
        generalistId = generalist.getId();
    }

    @Test
    void fusedWinnerIsNotRankOneInAnySingleRetriever() {
        Long lexicalTop = topId(
                BookSearchExpressions.matches(SearchMode.LEXICAL, QUERY),
                (root, cb) -> BookSearchExpressions.rank(SearchMode.LEXICAL, root, cb, QUERY)
        );
        Long fuzzyTop = topId(
                BookSearchExpressions.matches(SearchMode.FUZZY, QUERY),
                (root, cb) -> BookSearchExpressions.rank(SearchMode.FUZZY, root, cb, QUERY)
        );
        Long synonymTop = topId(
                BookSearchExpressions.matches(SearchMode.SYNONYM, QUERY),
                (root, cb) -> BookSearchExpressions.rank(SearchMode.SYNONYM, root, cb, QUERY)
        );

        assertThat(lexicalTop).as(
                "fixture sanity: specialistLexical must lead LEXICAL").isEqualTo(specialistLexicalId);
        assertThat(fuzzyTop).as(
                "fixture sanity: specialistFuzzy must lead FUZZY").isEqualTo(specialistFuzzyId);
        assertThat(synonymTop).as(
                        "fixture sanity: specialistLexical must lead SYNONYM too (no synonym mapping for 'beacon')")
                .isEqualTo(specialistLexicalId);

        List<FusedSearchRow> fused = bookRepository.findFused(
                QUERY,
                GENRE,
                SearchService.RRF_K,
                50,
                PageRequest.of(0, 10)
        ).getContent();

        assertThat(fused).as("all three fixture rows must be reachable through fusion").isNotEmpty();
        Long fusedWinner = fused.get(0).getBookId();

        assertThat(fusedWinner).as(
                        "fused winner must be the generalist, not either specialist")
                .isEqualTo(generalistId);
        assertThat(fusedWinner)
                .as("the fused #1 result must not be the #1 result of any single retriever")
                .isNotEqualTo(lexicalTop)
                .isNotEqualTo(fuzzyTop)
                .isNotEqualTo(synonymTop);
    }

    @Test
    void reportedContributionsSumToTheScoreComputedInSql() {
        List<SearchResult> results = searchService.search(QUERY, SearchMode.FUSED, GENRE, PageRequest.of(0, 10))
                .getContent();
        assertThat(results).as("the fixture rows must be reachable through FUSED").isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            double summed = result.rankContributions().stream()
                    .map(RankContribution::contribution)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .sum();
            assertThat(summed)
                    .as("displayed contributions must reconstruct the SQL-computed score for '%s'", result.title())
                    .isCloseTo(result.score(), org.assertj.core.data.Offset.offset(1e-9));
        });
    }

    private Long topId(
            Specification<BookEntity> matches,
            BiFunction<Root<BookEntity>, CriteriaBuilder, Expression<Double>> rankFactory
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<Double> rank = rankFactory.apply(root, cb);
        Specification<BookEntity> predicate = matches.and(BookSearchExpressions.hasGenre(GENRE));
        cq.select(cb.tuple(root.get("id"), rank));
        cq.where(predicate.toPredicate(root, cq, cb));
        cq.orderBy(cb.desc(rank));
        List<Tuple> rows = entityManager.createQuery(cq).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0).get(0, Long.class);
    }

}
