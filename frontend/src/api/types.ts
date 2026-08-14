export type SearchMode = 'LEXICAL' | 'FUZZY' | 'SYNONYM' | 'FUSED';

export const SEARCH_MODES: readonly SearchMode[] = ['LEXICAL', 'FUZZY', 'SYNONYM', 'FUSED'];

/** One retriever's contribution to a FUSED row: its rank (1-based) and its RRF term 1/(60+rank). */
export interface RankContribution {
  retriever: 'LEXICAL' | 'FUZZY' | 'SYNONYM';
  rank: number | null;
  contribution: number | null;
}

export interface SearchResultDto {
  id: number;
  title: string;
  author: string;
  genre: string;
  /**
   * Raw `ts_headline` HTML — the excerpt is escaped in SQL before ts_headline runs, so the only
   * live markup is `<mark>`. See docs/POSTGRES-FTS.md § "Highlighting: ts_headline does not
   * escape". Passed through `sanitizeHeadline` and rendered via dangerouslySetInnerHTML.
   */
  headline: string;
  score: number;
  /** Always present; empty unless mode=FUSED. `SearchResult` defaults the list to `List.of()`. */
  rankContributions: RankContribution[];
}

/** Spring Data's PagedModel: content plus a `page` object of paging facts. */
export interface PageMetadata {
  size: number;
  number: number;
  totalElements: number;
  totalPages: number;
}

export interface SearchResponseDto {
  content: SearchResultDto[];
  page: PageMetadata;
}

export interface SearchParams {
  q: string;
  mode: SearchMode;
  genre?: string;
  page: number;
  size: number;
}
