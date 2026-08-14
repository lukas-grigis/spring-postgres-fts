package dev.lukasgrigis.booksearch.web;

import dev.lukasgrigis.booksearch.AbstractPostgresTest;
import dev.lukasgrigis.booksearch.search.SearchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP contract, over MockMvc rather than a real server: nothing here depends on a socket, so
 * there is no port to inject and no servlet container to start. The database is real, because the
 * endpoint's answers are.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerIT extends AbstractPostgresTest {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void searchRespondsWithResultsForEveryMode(SearchMode mode) {
        assertThat(mvc.get().uri("/api/search?q=time&mode={mode}", mode))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.content")
                .asArray()
                .as("mode=%s must return real hits for a common word", mode)
                .isNotEmpty();
    }

    @Test
    void responseCarriesPagedModelMetadata() {
        MvcTestResult result = mvc.get().uri("/api/search?q=time&size=5").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.page.totalElements")
                .asNumber()
                .as("PagedModel must report a real total, which means the count query ran")
                .isNotNull();
    }

    @Test
    void fusedResultsCarryPerRetrieverRankContributions() {
        assertThat(mvc.get().uri("/api/search?q=time&mode=FUSED"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.content[0].rankContributions")
                .asArray()
                .as("FUSED must always report all three retrievers, hit or not")
                .hasSize(3);
    }

    @Test
    void secondPageIsDisjointFromTheFirst() throws Exception {
        MvcTestResult first = mvc.get().uri("/api/search?q=time&mode=LEXICAL&size=2&page=0").exchange();
        MvcTestResult second = mvc.get().uri("/api/search?q=time&mode=LEXICAL&size=2&page=1").exchange();

        assertThat(first).hasStatusOk();
        assertThat(second).hasStatusOk();
        assertThat(second.getResponse().getContentAsString())
                .as("page 1 must not repeat page 0 — the offset has to reach the query")
                .isNotEqualTo(first.getResponse().getContentAsString());
    }

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void absurdlyDeepPageIsRejectedAsBadRequest(SearchMode mode) {
        assertThat(mvc.get().uri("/api/search?q=time&mode={mode}&page=300000000", mode))
                .as("mode=%s: an unreachable page index is a 400, never a 500", mode)
                .hasStatus(400)
                .hasContentType("application/problem+json");
    }

    /**
     * The endpoint takes a {@code Pageable}, so Spring binds a {@code sort} parameter whether or not
     * this API wants one — and Swagger UI renders a box for it. Ranking is the retriever's job, so
     * the value has to be dropped rather than passed on: FUSED is a native query, and Spring Data
     * appends a sort it cannot parse straight into the SQL text, which the database then rejects.
     */
    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void sortParameterIsIgnoredRatherThanReachingTheQuery(SearchMode mode) {
        assertThat(mvc.get().uri("/api/search?q=time&mode={mode}&sort=not_a_column,asc", mode))
                .as("mode=%s: an unusable sort must be ignored, never reach the database as SQL", mode)
                .hasStatusOk();
    }

    @Test
    void blankQueryIsRejectedAsBadRequest() {
        assertThat(mvc.get().uri("/api/search?q=&mode=LEXICAL"))
                .hasStatus(400)
                .hasContentType("application/problem+json");
    }

    @Test
    void overlongQueryIsRejectedAsBadRequest() {
        assertThat(mvc.get().uri("/api/search?q={q}", "a".repeat(250)))
                .hasStatus(400)
                .hasContentType("application/problem+json");
    }

    @Test
    void genresEndpointReturnsTheSeededGenreNames() {
        assertThat(mvc.get().uri("/api/genres"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$")
                .asArray()
                .as("must be the actual seeded genres, not a hand-copied list")
                .contains("Novel", "Gothic", "Adventure");
    }

}
