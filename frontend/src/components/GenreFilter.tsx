import { useGenres } from '../hooks/useGenres';

interface Props {
  value: string;
  onChange: (value: string) => void;
}

export function GenreFilter({ value, onChange }: Props) {
  const genres = useGenres();
  return (
    <div className="flex items-center gap-2">
      <label className="text-base text-fg-muted" htmlFor="genre-select">
        Genre
      </label>
      <select
        id="genre-select"
        onChange={(e) => onChange(e.target.value)}
        value={value}
        className="rounded-sm border border-rule-strong bg-surface px-3 py-2 text-base text-fg
          focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        <option value="">All genres</option>
        {genres.map((g) => (
          <option key={g} value={g}>
            {g}
          </option>
        ))}
      </select>
    </div>
  );
}
