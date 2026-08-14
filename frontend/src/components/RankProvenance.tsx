import type { RankContribution } from '../api/types';

const RETRIEVER_LABELS: Record<RankContribution['retriever'], string> = {
  LEXICAL: 'Lexical',
  FUZZY: 'Fuzzy',
  SYNONYM: 'Synonym',
};

const RANK_FLOOR = 10;

/** Per-retriever provenance for a FUSED row — the demo's visual argument for why RRF works. */
export function RankProvenance({ contributions }: { contributions: RankContribution[] }) {
  return (
    <dl
      aria-label="Rank provenance across retrievers"
      className="grid grid-cols-[4.5rem_2.25rem_1fr] items-center gap-x-3 gap-y-1.5 text-2xs"
    >
      {contributions.map((c) => {
        const hit = c.rank !== null;
        // Floor a genuine hit at 8% so a deep rank still reads as a hit rather than as an empty
        // track, which is what "not retrieved" looks like.
        const percent = hit
          ? Math.max(8, (1 - (Math.min(c.rank ?? 1, RANK_FLOOR) - 1) / RANK_FLOOR) * 100)
          : 0;
        return (
          <div className="contents" key={c.retriever}>
            <dt className="font-medium uppercase text-fg-muted">{RETRIEVER_LABELS[c.retriever]}</dt>
            <dd className={`tabular-nums ${hit ? 'text-fg' : 'text-fg-muted'}`}>
              {hit ? `#${c.rank}` : '—'}
            </dd>
            {/* An empty track reads as "not retrieved" more directly than the words did, and
                costs less vertical room. The numbers carry the meaning for assistive tech. */}
            <dd aria-hidden="true" className="h-[3px] w-full bg-rule">
              <div className="h-full bg-accent" style={{ width: `${percent}%` }} />
            </dd>
          </div>
        );
      })}
    </dl>
  );
}
