package dev.lukasgrigis.booksearch.search.hibernate;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import dev.lukasgrigis.booksearch.search.BookSearchExpressions;
import dev.lukasgrigis.booksearch.search.SearchMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claim: {@code SearchFunctionContributor} is picked up by Hibernate's SQM function registry, so a
 * Criteria query reaches the {@code @@} operator and executes against real Postgres.
 * <p>
 * The second test is what gives the first one meaning. Hibernate does NOT reject an unregistered
 * function name — it renders it straight through as {@code name(args)} and lets the database
 * decide. That is precisely why {@code @@} needs {@code registerPattern}: rendered as a function
 * call, {@code fts(a, b)} would reach Postgres as a call to a function that does not exist, and
 * fail exactly as the bogus name below does.
 */
class FunctionContributorIT extends AbstractPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void ftsPatternFunctionIsRegisteredAndExecutes() {
        List<BookEntity> seeded = entityManager
                .createQuery("select b from BookEntity b", BookEntity.class)
                .setMaxResults(1)
                .getResultList();
        assertThat(seeded).as("corpus must be seeded for this test to mean anything").isNotEmpty();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(cb.tuple(root.get("id")));
        cq.where(BookSearchExpressions.matches(SearchMode.LEXICAL, "time").toPredicate(root, cq, cb));

        assertThat(entityManager.createQuery(cq).getResultList())
                .as("search_vector @@ websearch_to_tsquery('english','time') must execute and return rows")
                .isNotEmpty();
    }

    /**
     * Its own test method because the failure aborts the surrounding transaction, and every method
     * here runs in one that rolls back.
     */
    @Test
    void anUnregisteredNameIsRenderedAsAFunctionCallAndRejectedByPostgres() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(cb.tuple(root.get("id")));
        cq.where(cb.isTrue(cb.function("definitely_not_a_registered_function", Boolean.class, root.get("id"))));

        assertThatThrownBy(() -> entityManager.createQuery(cq).getResultList())
                .as("Hibernate passes the name through; Postgres is what rejects it")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("definitely_not_a_registered_function");
    }

}
