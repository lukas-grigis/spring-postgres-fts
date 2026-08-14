package dev.lukasgrigis.booksearch.search;

/**
 * One row of {@link BookRepository#findFused}, as a Spring Data <em>interface projection</em>.
 * <p>
 * There is no implementation class and none is needed: Spring Data proxies this interface and
 * matches each getter to a column of the native query by name, so {@code getBookId()} reads the
 * {@code book_id} alias and {@code getLexicalRank()} reads {@code lexical_rank}. The aliases in
 * {@link BookRepository#FUSED_ROWS} are therefore load-bearing — renaming one there without
 * renaming the getter here breaks the binding at runtime, not at compile time.
 * <p>
 * The three rank getters are boxed {@code Integer} rather than {@code int} on purpose. A null
 * means that retriever did not return this book at all, which is different from returning it in
 * last place, and is exactly what FUSED exists to show. {@code SearchService.contributionsOf}
 * turns a null rank into a null contribution so the UI can render "not retrieved".
 */
public interface FusedSearchRow {

    Long getBookId();

    String getTitle();

    String getAuthorName();

    String getGenreName();

    String getHeadline();

    Double getScore();

    Integer getLexicalRank();

    Integer getFuzzyRank();

    Integer getSynonymRank();

}
