package dev.lukasgrigis.booksearch.search.hibernate;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.BasicTypeRegistry;
import org.hibernate.type.StandardBasicTypes;

/**
 * Teaches Hibernate the two Postgres OPERATORS the Criteria API otherwise cannot reach.
 * <p>
 * Only operators need this. {@code cb.function("ts_rank", ...)} works with no registration at all
 * — Hibernate renders an unknown name straight through as {@code ts_rank(?, ?)}, and Postgres is
 * happy to resolve it. So {@code ts_rank}, {@code ts_rank_cd}, {@code ts_headline},
 * {@code similarity}, {@code to_tsvector} and {@code websearch_to_tsquery} are all absent here on
 * purpose; registering them bought nothing and was removed after the whole suite passed without
 * them.
 * <p>
 * {@code @@} and {@code %} are different in kind. They are operators, so there is no
 * {@code fts(a, b)} for Hibernate to fall through to — Postgres has no function by that name, and
 * the query fails at the database. {@code registerPattern} is the escape hatch: it maps a callable
 * name onto an arbitrary SQL template with positional placeholders, so {@code cb.function("fts",
 * ...)} emits {@code ?1 @@ ?2} instead of a function call.
 * <p>
 * Registered through {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}, which
 * Hibernate reads while building the SessionFactory — before the Spring context exists, which is
 * why this class is not a bean and cannot be injected into.
 */
public class SearchFunctionContributor implements FunctionContributor {

    /**
     * User space starts at 1000 per the {@link FunctionContributor#ordinal()} javadoc.
     */
    private static final int ORDINAL = 1000;

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        SqmFunctionRegistry registry = functionContributions.getFunctionRegistry();
        BasicTypeRegistry basicTypes = functionContributions.getTypeConfiguration().getBasicTypeRegistry();

        registry.registerPattern("fts", "?1 @@ ?2", basicTypes.resolve(StandardBasicTypes.BOOLEAN));

        // pg_trgm's similarity operator. Same operator the native RRF query's fuzzy CTE uses, so
        // both share pg_trgm.similarity_threshold and a typo found by FUZZY survives into FUSED.
        registry.registerPattern("trgmSimilar", "?1 % ?2", basicTypes.resolve(StandardBasicTypes.BOOLEAN));
    }

    @Override
    public int ordinal() {
        return ORDINAL;
    }

}
