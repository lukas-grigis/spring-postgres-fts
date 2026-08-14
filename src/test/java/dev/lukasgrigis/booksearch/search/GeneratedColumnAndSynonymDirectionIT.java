package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedColumnAndSynonymDirectionIT extends AbstractPersistenceTest {

    @Autowired
    private DataSource dataSource;

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @Test
    void virtualGeneratedColumnRejectsGinIndex() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS virtual_column_scratch");
            statement.execute("CREATE TABLE virtual_column_scratch (id serial PRIMARY KEY, body text)");
            // No STORED: Postgres 18 defaults generated columns to VIRTUAL.
            statement.execute("""
                    ALTER TABLE virtual_column_scratch ADD COLUMN sv tsvector
                        GENERATED ALWAYS AS (TO_TSVECTOR('english', COALESCE(body, '')))
                    """);

            assertThatThrownBy(() -> statement.execute(
                    "CREATE INDEX idx_virtual_column_scratch_sv ON virtual_column_scratch USING gin (sv)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("indexes on virtual generated columns are not supported");
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS virtual_column_scratch");
            }
        }
    }

    @Test
    void bidirectionalSynonymPairsAreSelfCancelling() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TEXT SEARCH CONFIGURATION IF EXISTS bidirectional_scratch CASCADE");
            statement.execute("DROP TEXT SEARCH DICTIONARY IF EXISTS bidirectional_scratch_dict");
            // Every pair listed in both directions — the exact shape book_synonym.syn had before the fix.
            writeSynFile("bidirectional_scratch", "sleuth detective\ndetective sleuth\n");
            statement.execute("""
                    CREATE TEXT SEARCH DICTIONARY bidirectional_scratch_dict
                        (TEMPLATE = synonym, SYNONYMS = bidirectional_scratch)
                    """);
            statement.execute("CREATE TEXT SEARCH CONFIGURATION bidirectional_scratch (COPY = english)");
            statement.execute("""
                    ALTER TEXT SEARCH CONFIGURATION bidirectional_scratch
                        ALTER MAPPING FOR asciiword, asciihword, hword_asciipart, word, hword, hword_part
                        WITH bidirectional_scratch_dict, english_stem
                    """);

            assertThat(matches(statement, "bidirectional_scratch", "The detective solved it.", "sleuth"))
                    .as("bidirectional pair: query 'sleuth' must NOT find a document that says 'detective' — this is the bug")
                    .isFalse();
            assertThat(matches(statement, "bidirectional_scratch", "The sleuth solved it.", "detective"))
                    .as("bidirectional pair: query 'detective' must NOT find a document that says 'sleuth' — this is the bug")
                    .isFalse();
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TEXT SEARCH CONFIGURATION IF EXISTS bidirectional_scratch CASCADE");
                statement.execute("DROP TEXT SEARCH DICTIONARY IF EXISTS bidirectional_scratch_dict");
            }
        }

        // Counter-check: the same pair, one direction only, is exactly what book_synonym.syn now uses,
        // and it lets a query for either word find a document containing the other.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TEXT SEARCH CONFIGURATION IF EXISTS onedirection_scratch CASCADE");
            statement.execute("DROP TEXT SEARCH DICTIONARY IF EXISTS onedirection_scratch_dict");
            writeSynFile("onedirection_scratch", "detective sleuth\n");
            statement.execute("""
                    CREATE TEXT SEARCH DICTIONARY onedirection_scratch_dict
                        (TEMPLATE = synonym, SYNONYMS = onedirection_scratch)
                    """);
            statement.execute("CREATE TEXT SEARCH CONFIGURATION onedirection_scratch (COPY = english)");
            statement.execute("""
                    ALTER TEXT SEARCH CONFIGURATION onedirection_scratch
                        ALTER MAPPING FOR asciiword, asciihword, hword_asciipart, word, hword, hword_part
                        WITH onedirection_scratch_dict, english_stem
                    """);

            assertThat(matches(statement, "onedirection_scratch", "The sleuth solved it.", "detective"))
                    .as("one-directional pair: query 'detective' must find a document that only says 'sleuth'")
                    .isTrue();
            assertThat(matches(statement, "onedirection_scratch", "The detective solved it.", "sleuth"))
                    .as("one-directional pair: query 'sleuth' must find a document that only says 'detective'")
                    .isTrue();
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TEXT SEARCH CONFIGURATION IF EXISTS onedirection_scratch CASCADE");
                statement.execute("DROP TEXT SEARCH DICTIONARY IF EXISTS onedirection_scratch_dict");
            }
        }
    }

    /**
     * Writes a scratch .syn file straight into the already-running container's tsearch_data dir.
     */
    private void writeSynFile(String name, String contents) throws SQLException {
        try {
            POSTGRES.execInContainer(
                    "bash", "-c",
                    "cat > /usr/share/postgresql/18/tsearch_data/" + name + ".syn <<'EOF'\n" + contents + "EOF\n"
            );
        } catch (Exception e) {
            throw new SQLException("failed to write scratch .syn file into the container", e);
        }
    }

    private boolean matches(Statement statement, String config, String documentText, String queryWord) throws
            SQLException {
        try (var rs = statement.executeQuery("""
                SELECT to_tsvector('%s', %s) @@ websearch_to_tsquery('%s', %s)
                """.formatted(config, quoteLiteral(documentText), config, quoteLiteral(queryWord)))) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

}
