package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.domain.BookEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs one retriever at a time through the Criteria API, or hands FUSED to the single native RRF
 * query.
 * <p>
 * The Criteria path builds its own {@code CriteriaQuery} rather than going through
 * {@code JpaSpecificationExecutor}, and that is not a preference. Score and headline are computed
 * SQL expressions ({@code ts_rank_cd}, {@code ts_headline}), not entity state, and a Spring Data
 * projection can only project mapped attributes — there is nothing on {@link BookEntity} for it to
 * read them from. Ranking has the same problem: it is an {@code ORDER BY} over one of those
 * expressions, and only a {@code CriteriaQuery} can carry one.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /**
     * The RRF damping constant in {@code 1/(k + rank)}, from the paper that introduced the method.
     * Bound into the native query as {@code :k}, so this is the only place the number appears.
     */
    static final int RRF_K = 60;
    private static final int RRF_LIMIT_PER_RETRIEVER = 50;
    private final EntityManager entityManager;
    private final BookRepository bookRepository;

    public SearchService(EntityManager entityManager, BookRepository bookRepository) {
        this.entityManager = entityManager;
        this.bookRepository = bookRepository;
    }

    private static SearchResult toResult(FusedSearchRow row) {
        return new SearchResult(
                row.getBookId(),
                row.getTitle(),
                row.getAuthorName(),
                row.getGenreName(),
                row.getHeadline(),
                row.getScore(),
                contributionsOf(row)
        );
    }

    /**
     * Always three entries, one per retriever, so the UI can render "not retrieved" for a null rank
     * rather than a missing row — the absence of a hit is exactly what FUSED exists to show.
     */
    private static List<RankContribution> contributionsOf(FusedSearchRow row) {
        return List.of(
                new RankContribution(SearchMode.LEXICAL, row.getLexicalRank(), contribution(row.getLexicalRank())),
                new RankContribution(SearchMode.FUZZY, row.getFuzzyRank(), contribution(row.getFuzzyRank())),
                new RankContribution(SearchMode.SYNONYM, row.getSynonymRank(), contribution(row.getSynonymRank()))
        );
    }

    private static Double contribution(Integer rank) {
        return rank == null ? null : 1.0 / (RRF_K + rank);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    public Page<SearchResult> search(String query, SearchMode mode, String genre, Pageable pageable) {
        return mode == SearchMode.FUSED
                ? fused(query, genre, pageable)
                : retrieve(query, mode, genre, pageable);
    }

    private Page<SearchResult> retrieve(String query, SearchMode mode, String genre, Pageable pageable) {
        Specification<BookEntity> spec = BookSearchExpressions.matches(mode, query)
                .and(BookSearchExpressions.hasGenre(genre));

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<SearchResult> cq = cb.createQuery(SearchResult.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        Expression<Double> rank = BookSearchExpressions.rank(mode, root, cb, query);

        cq.select(cb.construct(
                SearchResult.class,
                root.get("id"),
                root.get("title"),
                root.get("author").get("name"),
                root.get("genre").get("name"),
                BookSearchExpressions.headline(mode, root, cb, query),
                rank
        ));
        cq.where(spec.toPredicate(root, cq, cb));
        cq.orderBy(cb.desc(rank), cb.asc(root.get("id"))); // deterministic paging across rank ties

        List<SearchResult> results = entityManager.createQuery(cq)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // Skips the count entirely when the page is provably the whole result set.
        return PageableExecutionUtils.getPage(results, pageable, () -> count(spec));
    }

    private Page<SearchResult> fused(String query, String genre, Pageable pageable) {
        return bookRepository
                .findFused(query, blankToNull(genre), RRF_K, RRF_LIMIT_PER_RETRIEVER, pageable)
                .map(SearchService::toResult);
    }

    private long count(Specification<BookEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<BookEntity> root = cq.from(BookEntity.class);
        cq.select(cb.count(root)).where(spec.toPredicate(root, cq, cb));
        return entityManager.createQuery(cq).getSingleResult();
    }

}
