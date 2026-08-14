package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.domain.BookEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/**
 * The Postgres full-text-search surface, reached through the Criteria API.
 * <p>
 * One method per thing a retriever needs — match, rank, headline — each switching on
 * {@link SearchMode}. FUSED is absent from all three on purpose: it needs CTEs and window
 * functions, which Criteria cannot express, so it lives in {@link BookRepository#findFused}
 * instead. That absence is the boundary this repository exists to show.
 */
public final class BookSearchExpressions {

    private static final String ENGLISH = "english";
    private static final String SYNONYM = "book_synonym_search";
    private static final String HEADLINE_OPTIONS = "StartSel=<mark>,StopSel=</mark>,MaxWords=35,MinWords=15";

    private BookSearchExpressions() {
    }

    public static Specification<BookEntity> matches(SearchMode mode, String query) {
        return switch (mode) {
            case LEXICAL -> (root, cq, cb) -> cb.isTrue(
                    fts(cb, root.get("searchVector"), tsQuery(cb, ENGLISH, query))
            );
            case SYNONYM -> (root, cq, cb) -> cb.isTrue(
                    fts(cb, synonymVector(root, cb), tsQuery(cb, SYNONYM, query))
            );
            case FUZZY -> (root, cq, cb) -> cb.or(
                    cb.isTrue(similarEnough(cb, root.get("title"), query)),
                    cb.isTrue(similarEnough(cb, root.get("author").get("name"), query))
            );
            case FUSED -> throw fusedIsNative();
        };
    }

    public static Expression<Double> rank(
            SearchMode mode,
            Root<BookEntity> root,
            CriteriaBuilder cb,
            String query
    ) {
        return switch (mode) {
            case LEXICAL -> cb.function(
                    "ts_rank_cd", Double.class, root.get("searchVector"), tsQuery(cb, ENGLISH, query));
            case SYNONYM -> cb.function(
                    "ts_rank", Double.class, synonymVector(root, cb), tsQuery(cb, SYNONYM, query));
            case FUZZY -> cb.function(
                    "greatest", Double.class,
                    similarity(cb, root.get("title"), query),
                    similarity(cb, root.get("author").get("name"), query)
            );
            case FUSED -> throw fusedIsNative();
        };
    }

    /**
     * FUZZY reuses the English headline: it matches on title or author, so the excerpt has nothing
     * to mark, and marking it with the fuzzy term would misrepresent why the row matched.
     */
    public static Expression<String> headline(
            SearchMode mode,
            Root<BookEntity> root,
            CriteriaBuilder cb,
            String query
    ) {
        String config = switch (mode) {
            case SYNONYM -> SYNONYM;
            case LEXICAL, FUZZY -> ENGLISH;
            case FUSED -> throw fusedIsNative();
        };
        return cb.function(
                "ts_headline",
                String.class,
                cb.literal(config),
                escapedExcerpt(root, cb),
                tsQuery(cb, config, query),
                cb.literal(HEADLINE_OPTIONS)
        );
    }

    public static Specification<BookEntity> hasGenre(String genreName) {
        if (genreName == null || genreName.isBlank()) {
            return Specification.unrestricted();
        }
        return (root, cq, cb) -> cb.equal(root.get("genre").get("name"), genreName);
    }

    /**
     * Frequency ranking, kept alongside the cover-density ranking {@link #rank} uses for LEXICAL so
     * RankOrderingIT can show the two disagree. Package-private: evidence for a claim, not part of
     * the search API.
     */
    static Expression<Double> lexicalRankByFrequency(Root<BookEntity> root, CriteriaBuilder cb, String query) {
        return cb.function("ts_rank", Double.class, root.get("searchVector"), tsQuery(cb, ENGLISH, query));
    }

    /**
     * {@code @@} is an operator with no function-call syntax — see SearchFunctionContributor.
     */
    private static Expression<Boolean> fts(CriteriaBuilder cb, Expression<?> vector, Expression<?> query) {
        return cb.function("fts", Boolean.class, vector, query);
    }

    /**
     * {@code %}, likewise an operator. Its threshold is pg_trgm.similarity_threshold.
     */
    private static Expression<Boolean> similarEnough(CriteriaBuilder cb, Expression<?> column, String query) {
        return cb.function("trgmSimilar", Boolean.class, column, bind(cb, query));
    }

    private static Expression<Double> similarity(CriteriaBuilder cb, Expression<?> column, String query) {
        return cb.function("similarity", Double.class, column, bind(cb, query));
    }

    private static Expression<Object> tsQuery(CriteriaBuilder cb, String config, String query) {
        return cb.function("websearch_to_tsquery", Object.class, cb.literal(config), bind(cb, query));
    }

    /**
     * Not backed by a stored column — the synonym config is a secondary retriever, computed on the fly.
     */
    private static Expression<Object> synonymVector(Root<BookEntity> root, CriteriaBuilder cb) {
        Expression<String> titleAndExcerpt = cb.concat(
                cb.concat(root.get("title"), cb.literal(" ")),
                root.get("excerpt")
        );
        return cb.function("to_tsvector", Object.class, cb.literal(SYNONYM), titleAndExcerpt);
    }

    /**
     * ts_headline inserts StartSel/StopSel but does not escape the source text, so a hostile
     * excerpt would come back as live markup. Escaping first means the only real tags in the
     * output are the {@code <mark>}s we asked for. See docs/POSTGRES-FTS.md.
     */
    private static Expression<String> escapedExcerpt(Root<BookEntity> root, CriteriaBuilder cb) {
        Expression<String> escaped = root.get("excerpt");
        for (String[] pair : new String[][]{{"&", "&amp;"}, {"<", "&lt;"}, {">", "&gt;"}}) {
            escaped = cb.function("replace", String.class, escaped, cb.literal(pair[0]), cb.literal(pair[1]));
        }
        return escaped;
    }

    /**
     * The user's text as a real JDBC bind parameter. cb.literal renders the value into the
     * statement text; HibernateCriteriaBuilder.value binds it, and binds it self-containedly, so
     * findAll(Specification) needs no parameter-setting step. Config names stay literals because
     * they are regconfig constants and a bound varchar will not resolve the overload.
     */
    private static Expression<String> bind(CriteriaBuilder cb, String value) {
        return ((HibernateCriteriaBuilder) cb).value(value);
    }

    private static IllegalArgumentException fusedIsNative() {
        return new IllegalArgumentException("FUSED has no Criteria form — see BookRepository.findFused");
    }

}
