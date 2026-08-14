package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.domain.BookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookRepository extends JpaRepository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity> {

    /**
     * Three ranked candidate sets, one CTE each. Shared by the fusion query and its count query so
     * the retrievers are defined once — both constants below are compile-time concatenations of
     * this block, which is the only way an annotation can reuse it.
     */
    String CANDIDATES = """
            WITH lexical AS (
                SELECT b.id,
                       ROW_NUMBER() OVER (ORDER BY ts_rank_cd(b.search_vector, websearch_to_tsquery('english', :q)) DESC, b.id) AS rank
                FROM book b
                JOIN genre g ON g.id = b.genre_id
                WHERE b.search_vector @@ websearch_to_tsquery('english', :q)
                  AND (:genre IS NULL OR g.name = :genre)
                ORDER BY rank
                LIMIT :limitPerRetriever
            ),
            fuzzy AS (
                SELECT b.id,
                       ROW_NUMBER() OVER (ORDER BY GREATEST(similarity(b.title, :q), similarity(a.name, :q)) DESC, b.id) AS rank
                FROM book b
                JOIN author a ON a.id = b.author_id
                JOIN genre g ON g.id = b.genre_id
                WHERE (b.title % :q OR a.name % :q)
                  AND (:genre IS NULL OR g.name = :genre)
                ORDER BY rank
                LIMIT :limitPerRetriever
            ),
            synonym AS (
                SELECT b.id,
                       ROW_NUMBER() OVER (ORDER BY ts_rank(
                           to_tsvector('book_synonym_search', b.title || ' ' || b.excerpt),
                           websearch_to_tsquery('book_synonym_search', :q)
                       ) DESC, b.id) AS rank
                FROM book b
                JOIN genre g ON g.id = b.genre_id
                WHERE to_tsvector('book_synonym_search', b.title || ' ' || b.excerpt) @@ websearch_to_tsquery('book_synonym_search', :q)
                  AND (:genre IS NULL OR g.name = :genre)
                ORDER BY rank
                LIMIT :limitPerRetriever
            ),
            /* The three-way FULL OUTER JOIN keeps a book that only one retriever found, and the
               COALESCE in the second ON clause is what makes that work: after the first join an
               id lives in l.id or in f.id but not reliably in either, so joining synonym on l.id
               alone would drop every fuzzy-only match. COALESCE(l.id, f.id) is also unique across
               that intermediate result (an id appears at most once per CTE), so no row is
               duplicated. The OR form `s.id = l.id OR s.id = f.id` is not equivalent and Postgres
               rejects it outright: FULL JOIN needs a hashable or mergeable condition.
            
               Two constraints on editing this comment. Use block syntax, never a double dash,
               because the string is collapsed onto one line before it reaches the driver. And
               use no apostrophes: Spring Data scans this text for quoted ranges before the
               database ever sees it, and a single unpaired one fails query creation at startup. */
            fused AS (
                SELECT COALESCE(l.id, f.id, s.id) AS book_id,
                       l.rank AS lexical_rank,
                       f.rank AS fuzzy_rank,
                       s.rank AS synonym_rank,
                       COALESCE(1.0 / (:k + l.rank), 0) + COALESCE(1.0 / (:k + f.rank), 0) + COALESCE(1.0 / (:k + s.rank), 0) AS fused_score
                FROM lexical l
                FULL OUTER JOIN fuzzy f ON f.id = l.id
                FULL OUTER JOIN synonym s ON s.id = COALESCE(l.id, f.id)
            )
            """;

    String FUSED_ROWS = """
            SELECT
                b.id AS book_id,
                b.title AS title,
                a.name AS author_name,
                g.name AS genre_name,
                CASE WHEN fused.lexical_rank IS NULL AND fused.synonym_rank IS NOT NULL
                     THEN ts_headline('book_synonym_search',
                              replace(replace(replace(b.excerpt, '&', '&amp;'), '<', '&lt;'), '>', '&gt;'),
                              websearch_to_tsquery('book_synonym_search', :q),
                              'StartSel=<mark>,StopSel=</mark>,MaxWords=35,MinWords=15')
                     ELSE ts_headline('english',
                              replace(replace(replace(b.excerpt, '&', '&amp;'), '<', '&lt;'), '>', '&gt;'),
                              websearch_to_tsquery('english', :q),
                              'StartSel=<mark>,StopSel=</mark>,MaxWords=35,MinWords=15')
                END AS headline,
                fused.fused_score AS score,
                fused.lexical_rank AS lexical_rank,
                fused.fuzzy_rank AS fuzzy_rank,
                fused.synonym_rank AS synonym_rank
            FROM fused
            JOIN book b ON b.id = fused.book_id
            JOIN author a ON a.id = b.author_id
            JOIN genre g ON g.id = b.genre_id
            ORDER BY fused.fused_score DESC, b.id
            """;

    String FUSED_COUNT = "SELECT count(*) FROM fused";

    /**
     * Reciprocal Rank Fusion across the three retrievers, {@code Σ 1/(k + rank)}, in ONE native
     * query. This is the one place the Criteria API genuinely runs out: CTEs, {@code ROW_NUMBER()}
     * window functions and a {@code FULL OUTER JOIN} across three ranked candidate sets have no
     * Criteria representation. Fusing three full candidate lists in Java instead would mean
     * dragging every retriever's whole hit set over the wire just to add fractions.
     * <p>
     * Paging is Spring Data's: it appends the limit and offset from {@link Pageable} and runs
     * {@code countQuery} for the total. The count is meaningful here — the candidate sets are
     * capped at {@code limitPerRetriever} each, so the fused set is bounded and countable.
     */
    @Query(value = CANDIDATES + FUSED_ROWS, countQuery = CANDIDATES + FUSED_COUNT, nativeQuery = true)
    Page<FusedSearchRow> findFused(
            @Param("q") String query,
            @Param("genre") String genre,
            @Param("k") int k,
            @Param("limitPerRetriever") int limitPerRetriever,
            Pageable pageable
    );

}
