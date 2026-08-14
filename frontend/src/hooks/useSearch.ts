import { useCallback, useEffect, useRef, useState } from 'react';
import { search } from '../api/client';
import type { SearchMode, SearchResponseDto, SearchResultDto } from '../api/types';

const PAGE_SIZE = 10;
const DEBOUNCE_MS = 300;

export interface UseSearchState {
  q: string;
  setQ: (q: string) => void;
  mode: SearchMode;
  setMode: (mode: SearchMode) => void;
  genre: string;
  setGenre: (genre: string) => void;
  page: number;
  setPage: (page: number) => void;
  /** Sets query and mode together, so an example chip can demonstrate the mode that suits it. */
  setExample: (q: string, mode: SearchMode) => void;
  results: SearchResultDto[];
  totalElements: number;
  totalPages: number;
  loading: boolean;
  error: string | null;
  hasSearched: boolean;
}

export function useSearch(): UseSearchState {
  const [q, setQInternal] = useState('');
  // FUSED is the article's thesis and the backend's own default (SearchController) — opening on
  // LEXICAL would hide the rank-provenance strip (only rendered for FUSED) until a reader clicks.
  const [mode, setModeInternal] = useState<SearchMode>('FUSED');
  const [genre, setGenreInternal] = useState('');
  const [page, setPage] = useState(0);
  const [response, setResponse] = useState<SearchResponseDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const requestSeq = useRef(0);

  const runSearch = useCallback(
    (params: { q: string; mode: SearchMode; genre: string; page: number }) => {
      // An empty box means "no search", not "a search that failed". Bumping the sequence
      // abandons any in-flight response, so clearing the field also clears the spinner and a
      // previous error instead of leaving them stranded on screen.
      if (!params.q.trim()) {
        requestSeq.current++;
        setResponse(null);
        setHasSearched(false);
        setError(null);
        setLoading(false);
        return;
      }
      const seq = ++requestSeq.current;
      setLoading(true);
      setError(null);
      search({
        q: params.q,
        mode: params.mode,
        genre: params.genre || undefined,
        page: params.page,
        size: PAGE_SIZE,
      })
        .then((res) => {
          if (seq !== requestSeq.current) return; // stale response from a superseded request
          setResponse(res);
          setHasSearched(true);
        })
        .catch((err: unknown) => {
          if (seq !== requestSeq.current) return;
          setError(err instanceof Error ? err.message : 'Search failed.');
          setHasSearched(true);
        })
        .finally(() => {
          if (seq === requestSeq.current) setLoading(false);
        });
    },
    [],
  );

  const typedRef = useRef(false);
  useEffect(() => {
    const delay = typedRef.current ? DEBOUNCE_MS : 0;
    typedRef.current = false;
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runSearch({ q, mode, genre, page }), delay);
    return () => clearTimeout(debounceRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, mode, genre, page]);

  const setQ = (value: string) => {
    typedRef.current = true;
    setPage(0);
    setQInternal(value);
  };
  const setMode = (value: SearchMode) => {
    setPage(0);
    setModeInternal(value);
  };
  const setGenre = (value: string) => {
    setPage(0);
    setGenreInternal(value);
  };
  const setExample = (value: string, exampleMode: SearchMode) => {
    setPage(0);
    setQInternal(value);
    setModeInternal(exampleMode);
  };

  return {
    q,
    setQ,
    mode,
    setMode,
    genre,
    setGenre,
    page,
    setPage,
    setExample,
    results: response?.content ?? [],
    totalElements: response?.page.totalElements ?? 0,
    totalPages: response?.page.totalPages ?? 0,
    loading,
    error,
    hasSearched,
  };
}
