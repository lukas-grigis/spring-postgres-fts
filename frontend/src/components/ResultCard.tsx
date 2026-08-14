import type { SearchMode, SearchResultDto } from '../api/types';
import { sanitizeHeadline } from '../lib/sanitizeHeadline';
import { RankProvenance } from './RankProvenance';

/** What the number next to a result actually is, which differs per retriever. */
const SCORE_LABELS: Record<SearchMode, string> = {
  LEXICAL: 'ts_rank_cd',
  FUZZY: 'similarity',
  SYNONYM: 'ts_rank',
  FUSED: 'RRF',
};

interface Props {
  result: SearchResultDto;
  mode: SearchMode;
}

export function ResultCard({ result, mode }: Props) {
  const provenance = result.rankContributions;
  // FUZZY matches on title or author, never the excerpt, so ts_headline has nothing to mark and
  // the snippet comes back without a highlight. Saying so beats letting it look broken.
  const unhighlighted = mode === 'FUZZY' && !result.headline.includes('<mark>');

  return (
    <li className="py-6">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 className="text-lg font-semibold text-fg">{result.title}</h2>
        <span className="text-xs text-fg-muted">{result.author}</span>
      </div>

      <div className="mt-2 flex items-center gap-2 text-2xs uppercase text-fg-muted">
        <span className="font-medium">{result.genre}</span>
        <span aria-hidden="true" className="h-[0.8em] w-px bg-rule" />
        <span className="tabular-nums">
          {SCORE_LABELS[mode]} {result.score.toFixed(mode === 'FUSED' ? 4 : 3)}
        </span>
      </div>

      <p
        className="mt-3 max-w-[62ch] text-base text-fg"
        dangerouslySetInnerHTML={{ __html: sanitizeHeadline(result.headline) }}
      />

      {unhighlighted && (
        <p className="mt-1 text-2xs uppercase text-fg-muted">
          matched on title or author, not the excerpt
        </p>
      )}

      {provenance.length > 0 && (
        <div className="mt-4">
          <RankProvenance contributions={provenance} />
        </div>
      )}
    </li>
  );
}
