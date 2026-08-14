package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.AbstractPersistenceTest;
import dev.lukasgrigis.booksearch.domain.BookEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim: {@code websearch_to_tsquery} gives quote-for-phrase and {@code -}-for-negation semantics
 * safe to hand straight to a UI search box (unlike raw {@code to_tsquery}, which throws on
 * unbalanced operators). Both sub-claims carry their own counter-check against the real corpus
 * rather than a fixed expected count, so the test does not silently pass if the seed changes.
 */
class LexicalSearchQuerySyntaxIT extends AbstractPersistenceTest {

    @Autowired
    private BookRepository bookRepository;

    @Test
    void quotedPhraseIsStricterThanBareWords() {
        List<BookEntity> bareWords = bookRepository.findAll(BookSearchExpressions.matches(
                SearchMode.LEXICAL,
                "old man"
        ));
        List<BookEntity> phrase = bookRepository.findAll(BookSearchExpressions.matches(
                SearchMode.LEXICAL,
                "\"old man\""
        ));

        assertThat(phrase).as("the phrase must actually match at least one book in the corpus").isNotEmpty();
        assertThat(phrase.size())
                .as("a quoted phrase (adjacency required) must match strictly fewer books than the same words unquoted (AND, any position)")
                .isLessThan(bareWords.size());

        Set<Long> phraseIds = phrase.stream().map(BookEntity::getId).collect(Collectors.toSet());
        Set<Long> bareWordIds = bareWords.stream().map(BookEntity::getId).collect(Collectors.toSet());
        assertThat(bareWordIds).as("every phrase hit is necessarily also a bare-words hit").containsAll(phraseIds);
    }

    @Test
    void negationExcludesDocumentsContainingTheNegatedWord() {
        List<BookEntity> manOnly = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.LEXICAL, "man"));
        List<BookEntity> manWithoutOld = bookRepository.findAll(BookSearchExpressions.matches(
                SearchMode.LEXICAL,
                "man -old"
        ));
        List<BookEntity> oldOnly = bookRepository.findAll(BookSearchExpressions.matches(SearchMode.LEXICAL, "old"));

        assertThat(manWithoutOld).as("'man -old' must still match something in this corpus").isNotEmpty();
        assertThat(manWithoutOld.size())
                .as("'-old' must strictly shrink the 'man' result set")
                .isLessThan(manOnly.size());

        Set<Long> oldIds = oldOnly.stream().map(BookEntity::getId).collect(Collectors.toSet());
        boolean anyNegatedResultAlsoMatchesOld = manWithoutOld.stream()
                .map(BookEntity::getId)
                .anyMatch(oldIds::contains);
        assertThat(anyNegatedResultAlsoMatchesOld)
                .as("none of the 'man -old' results may also match a plain 'old' query")
                .isFalse();
    }

}
