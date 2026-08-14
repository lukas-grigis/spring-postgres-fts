import type { SearchMode } from '../api/types';
import { SEARCH_MODES } from '../api/types';
import { MODE_LABELS } from '../constants';

interface Props {
  mode: SearchMode;
  onChange: (mode: SearchMode) => void;
}

export function ModeToggle({ mode, onChange }: Props) {
  return (
    <fieldset>
      <legend className="sr-only">Search mode</legend>
      <div className="inline-flex overflow-hidden rounded-sm border border-rule-strong">
        {SEARCH_MODES.map((m, i) => (
          <label
            key={m}
            className={`cursor-pointer px-3 py-2 text-base font-medium transition-colors sm:px-4 ${
              i > 0 ? 'border-l border-rule-strong' : ''
            } ${
              m === mode
                ? 'bg-accent text-accent-on'
                : 'text-fg-muted hover:bg-surface hover:text-fg'
            } has-[:focus-visible]:outline has-[:focus-visible]:outline-2
              has-[:focus-visible]:-outline-offset-2 has-[:focus-visible]:outline-accent`}
          >
            <input
              aria-describedby="mode-help"
              checked={m === mode}
              className="sr-only"
              name="mode"
              onChange={() => onChange(m)}
              type="radio"
              value={m}
            />
            {MODE_LABELS[m]}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
