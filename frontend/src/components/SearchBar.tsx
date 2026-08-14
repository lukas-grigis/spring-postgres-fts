interface Props {
  value: string;
  onChange: (value: string) => void;
}

export function SearchBar({ value, onChange }: Props) {
  return (
    <div>
      <label className="sr-only" htmlFor="search-input">
        Search the catalogue
      </label>
      <input
        autoComplete="off"
        autoFocus
        id="search-input"
        maxLength={200}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Search titles, authors and excerpts"
        spellCheck={false}
        type="search"
        value={value}
        className="w-full rounded-sm border border-rule-strong bg-surface px-4 py-4 text-xl text-fg
          placeholder:text-fg-muted focus-visible:border-accent focus-visible:outline
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      />
    </div>
  );
}
