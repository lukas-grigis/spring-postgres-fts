package dev.lukasgrigis.booksearch;

import dev.lukasgrigis.booksearch.search.SearchService;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Base for everything that asserts Postgres behaviour: the retrievers, the ranking functions, the
 * synonym dictionary, the generated column.
 * <p>
 * {@code @DataJpaTest} loads only the JPA slice — entities, repositories, Liquibase — rather than
 * the whole application, and wraps each test in a transaction that rolls back, so fixture rows
 * clean themselves up. {@code replace = NONE} is what stops it swapping the real Postgres for an
 * embedded database, which would take the Postgres-specific behaviour under test with it.
 * <p>
 * {@link SearchService} is imported because it is a {@code @Service} and therefore outside the
 * slice, but it is the natural way for a test to ask a question of the search API.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchService.class)
public abstract class AbstractPersistenceTest extends AbstractPostgresTest {

}
