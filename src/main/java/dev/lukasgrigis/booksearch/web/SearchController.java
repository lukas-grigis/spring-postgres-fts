package dev.lukasgrigis.booksearch.web;

import dev.lukasgrigis.booksearch.search.GenreRepository;
import dev.lukasgrigis.booksearch.search.SearchMode;
import dev.lukasgrigis.booksearch.search.SearchResult;
import dev.lukasgrigis.booksearch.search.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final GenreRepository genreRepository;

    public SearchController(SearchService searchService, GenreRepository genreRepository) {
        this.searchService = searchService;
        this.genreRepository = genreRepository;
    }

    @GetMapping("/api/genres")
    @Operation(summary = "List genre names present in the catalogue, for populating a filter")
    public List<String> genres() {
        return genreRepository.findAllNamesOrderByName();
    }

    /**
     * Returns {@link PagedModel} rather than {@link org.springframework.data.domain.Page}: Page's
     * JSON shape is its internal representation and Spring warns that serialising it directly is
     * unstable across versions. PagedModel is the DTO meant for the wire, with the paging facts
     * under a {@code page} object.
     * <p>
     * Sorting comes from the retriever, not the request — a search result set is ordered by
     * relevance, so any {@code sort} parameter is dropped here rather than merely unused. Passing
     * one on would not be harmless: FUSED is a native query, and Spring Data appends an unparsed
     * sort straight into that SQL text, where Postgres rejects it as a syntax error.
     */
    @GetMapping("/api/search")
    @Operation(summary = "Search the book catalogue", description =
            "LEXICAL ranks by ts_rank_cd over a websearch_to_tsquery match on the stored tsvector. "
                    + "FUZZY ranks by pg_trgm similarity — finds typos the stemmer cannot. "
                    + "SYNONYM matches via a custom text-search configuration so a query word can hit "
                    + "a document that never contains it. FUSED combines all three by Reciprocal Rank "
                    + "Fusion and reports each retriever's contribution per result.")
    public PagedModel<SearchResult> search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(defaultValue = "FUSED") SearchMode mode,
            @RequestParam(required = false) String genre,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable
    ) {
        // JPA's setFirstResult takes an int, so an offset past Integer.MAX_VALUE cannot be
        // expressed at all — Spring Data throws for it. A page index nobody can reach is a bad
        // request, not a server error, and ResponseStatusException renders it as problem+json
        // through the same Boot mechanism as the validation failures above.
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page index is out of range");
        }
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return new PagedModel<>(searchService.search(q, mode, genre, unsorted));
    }

}
