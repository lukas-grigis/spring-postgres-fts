package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim: {@code ts_rank} (frequency-only) and {@code ts_rank_cd} (cover density — rewards terms
 * appearing close together) produce a visibly different order for the same match set. The
 * counter-check IS the assertion itself: if the corpus were too thin for the two algorithms to
 * disagree, this test fails rather than silently passing on a query where they happen to agree.
 */
class RankOrderingIT extends AbstractPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void tsRankAndTsRankCdOrderDifferForTheSameQuery() {
        // Verified directly against the seeded corpus (11 matches; ts_rank and ts_rank_cd
        // genuinely disagree on their order here) — not every AND-query does, since cover
        // density only bites when term proximity actually varies across the match set.
        String query = "old time";

        List<Long> byTsRank = orderedIdsFor(
                query,
                (root, cb) -> BookSearchExpressions.lexicalRankByFrequency(root, cb, query)
        );
        List<Long> byTsRankCd = orderedIdsFor(
                query,
                (root, cb) -> BookSearchExpressions.rank(SearchMode.LEXICAL, root, cb, query)
        );

        assertThat(byTsRank).as("query must match enough of the corpus to make ranking meaningful")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(byTsRankCd).hasSameSizeAs(byTsRank);
        assertThat(byTsRankCd)
                .as("ts_rank_cd (cover density) must not just reproduce the same order as ts_rank (plain frequency) on real prose")
                .isNotEqualTo(byTsRank);
    }

    private List<Long> orderedIdsFor(
            String query,
            BiFunction<Root<BookEntity>, CriteriaBuilder, Expression<Double>> rankFactory
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<Double> rank = rankFactory.apply(root, cb);
        cq.select(cb.tuple(root.get("id"), rank));
        cq.where(BookSearchExpressions.matches(SearchMode.LEXICAL, query).toPredicate(root, cq, cb));
        cq.orderBy(cb.desc(rank), cb.asc(root.get("id")));
        return entityManager.createQuery(cq).getResultList().stream()
                .map(row -> row.get(0, Long.class))
                .toList();
    }

}
