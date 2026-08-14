package dev.lukasgrigis.booksearch;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * The one real Postgres every test runs against, started once per JVM and never stopped — Ryuk
 * reaps it at exit. Held in a static field rather than a {@code @Bean} so the two test slices
 * below it share a single container instead of starting one per application context.
 * <p>
 * {@code @ServiceConnection} points the datasource at it, so there is no property wiring to keep
 * in step. The synonym dictionary must be in the server's $SHAREDIR/tsearch_data before Liquibase
 * runs CREATE TEXT SEARCH DICTIONARY; this is the Testcontainers equivalent of the bind mount in
 * support/compose.yaml.
 */
public abstract class AbstractPostgresTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-trixie")
            .withDatabaseName("booksearch")
            .withUsername("booksearch")
            .withPassword("booksearch")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("support/tsearch_data/book_synonym.syn")),
                    "/usr/share/postgresql/18/tsearch_data/book_synonym.syn"
            );

    static {
        POSTGRES.start();
    }

}
