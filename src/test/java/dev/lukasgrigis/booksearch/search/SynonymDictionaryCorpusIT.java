package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SynonymDictionaryCorpusIT extends AbstractPersistenceTest {

    private static final Path SYNONYM_DICTIONARY = Path.of("support/tsearch_data/book_synonym.syn");

    @Autowired
    private BookRepository bookRepository;

    @Test
    void everyShippedSynonymPairFindsRealSeededBooks() throws IOException {
        List<String[]> pairs = Files.readAllLines(SYNONYM_DICTIONARY).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("\\s+"))
                .toList();

        assertThat(pairs).as("the dictionary must actually ship pairs").isNotEmpty();

        for (String[] pair : pairs) {
            String source = pair[0];
            String target = pair[1];

            assertThat(bookRepository.findAll(BookSearchExpressions.matches(SearchMode.SYNONYM, source)))
                    .as("querying '%s' must reach seeded books that say '%s'", source, target)
                    .isNotEmpty();
        }
    }

    /**
     * The counter-check that makes the test above mean something: the source words must be absent
     * from the corpus, so a synonym hit cannot be explained by the plain lexical match that any
     * configuration would find.
     */
    @Test
    void noSourceWordAppearsInTheCorpusSoEveryHitIsEarnedByTheDictionary() throws IOException {
        List<String> sources = Files.readAllLines(SYNONYM_DICTIONARY).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("\\s+")[0])
                .toList();

        for (String source : sources) {
            assertThat(bookRepository.findAll(BookSearchExpressions.matches(SearchMode.LEXICAL, source)))
                    .as("'%s' must not occur literally in the corpus, or the synonym demo proves nothing", source)
                    .isEmpty();
        }
    }

}
