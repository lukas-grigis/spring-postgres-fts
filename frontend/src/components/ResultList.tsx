import type { SearchMode, SearchResultDto } from '../api/types';
import { MODE_LABELS } from '../constants';
import { ResultCard } from './ResultCard';

interface Props {
  results: SearchResultDto[];
  mode: SearchMode;
  loading: boolean;
  error: string | null;
  hasSearched: boolean;
  totalElements: number;
  totalPages: number;
  page: number;
  onPageChange: (page: number) => void;
}

export function ResultList({
  results,
  mode,
  loading,
  error,
  hasSearched,
  totalElements,
  totalPages,
  page,
  onPageChange,
}: Props) {
  // Always mounted. Rendering the live region only after a search meant it did not exist when the
  // first result set arrived, so a screen reader announced nothing on the search that matters most.
  const status = error
    ? null
    : loading
      ? 'Searching…'
      : !hasSearched
        ? 'Type a query, or pick an example above.'
        : totalElements === 0
          ? 'No results. Another mode may reach it — lexical will not catch a typo, fuzzy will not catch a synonym.'
          : `${totalElements} ${totalElements === 1 ? 'result' : 'results'} · ${MODE_LABELS[mode].toLowerCase()}`;

  return (
    <div>
      <p aria-live="polite" className="min-h-[1.55em] text-base text-fg-muted">
        {status}
      </p>

      {error && (
        <p className="mt-2 border-l-2 border-danger py-1 pl-4 text-base text-danger" role="alert">
          {error}
        </p>
      )}

      {/* The formula, once. Per-card arithmetic would print the same lesson ten times. */}
      {!error && hasSearched && results.length > 0 && mode === 'FUSED' && (
        <p className="mt-1 max-w-[62ch] text-xs text-fg-muted">
          Score is <span className="tabular-nums">1/(60 + rank)</span> summed over the retrievers
          that found the book; the bars show how highly each ranked it.
        </p>
      )}

      <ul aria-busy={loading} className={`divide-y divide-rule ${loading ? 'opacity-60' : ''}`}>
        {results.map((r) => (
          <ResultCard key={r.id} mode={mode} result={r} />
        ))}
      </ul>

      {totalPages > 1 && (
        <nav
          aria-label="Search results pages"
          className="mt-8 flex items-center gap-4 border-t border-rule pt-5 text-xs"
        >
          <button
            className="rounded-sm border border-rule-strong px-3 py-1.5 text-fg transition-colors
              enabled:hover:border-accent enabled:hover:text-accent disabled:opacity-40
              focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            disabled={page === 0}
            onClick={() => onPageChange(page - 1)}
            type="button"
          >
            Previous
          </button>
          <span className="tabular-nums text-fg-muted">
            Page {page + 1} of {totalPages}
          </span>
          <button
            className="rounded-sm border border-rule-strong px-3 py-1.5 text-fg transition-colors
              enabled:hover:border-accent enabled:hover:text-accent disabled:opacity-40
              focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            disabled={page >= totalPages - 1}
            onClick={() => onPageChange(page + 1)}
            type="button"
          >
            Next
          </button>
        </nav>
      )}
    </div>
  );
}
