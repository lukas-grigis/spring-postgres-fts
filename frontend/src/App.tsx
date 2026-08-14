import { GenreFilter } from './components/GenreFilter';
import { ModeToggle } from './components/ModeToggle';
import { ResultList } from './components/ResultList';
import { SearchBar } from './components/SearchBar';
import { ThemeToggle } from './components/ThemeToggle';
import { EXAMPLE_QUERIES, MODE_DESCRIPTIONS } from './constants';
import { useSearch } from './hooks/useSearch';

export function App() {
  const s = useSearch();

  return (
    <div className="mx-auto flex min-h-screen max-w-3xl flex-col px-6 py-12">
      <header className="mb-10 flex items-baseline justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-fg">Book search</h1>
          <p className="mt-1.5 max-w-[52ch] text-base text-fg-muted">
            Four ways to search 77 public-domain classics in Postgres.
          </p>
        </div>
        <ThemeToggle />
      </header>

      {/* One control block: field, retriever, filter. Bounded by a rule so the results below read
          as a separate region without needing a heading. */}
      <section className="border-b border-rule pb-6">
        <SearchBar onChange={s.setQ} value={s.q} />

        <div className="mt-4 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
          <ModeToggle mode={s.mode} onChange={s.setMode} />
          <GenreFilter onChange={s.setGenre} value={s.genre} />
        </div>

        <p className="mt-3 min-h-[1.55em] max-w-[62ch] text-base text-fg-muted" id="mode-help">
          {MODE_DESCRIPTIONS[s.mode]}
        </p>

        {/* Each chip sets the query AND the retriever that makes it worth seeing. Kept on screen
            after the first search on purpose: moving between them back to back is the fastest way
            to feel how the four retrievers differ, which is the whole point of the page. */}
        <div className="mt-5 flex flex-wrap items-baseline gap-x-2 gap-y-2 text-xs">
          <span className="text-fg-muted">Try</span>
          {EXAMPLE_QUERIES.map((e) => {
            const active = s.q === e.q && s.mode === e.mode;
            return (
              <button
                aria-pressed={active}
                className={`group rounded-sm border px-2.5 py-1 transition-colors
                  focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                  focus-visible:outline-accent ${
                    active
                      ? 'border-accent bg-accent text-accent-on'
                      : 'border-rule-strong text-fg hover:border-accent hover:text-accent'
                  }`}
                key={e.q}
                onClick={() => s.setExample(e.q, e.mode)}
                type="button"
              >
                {e.q}
                <span
                  className={
                    active ? 'ml-2 text-accent-on/80' : 'ml-2 text-fg-muted group-hover:text-accent'
                  }
                >
                  {e.hint}
                </span>
              </button>
            );
          })}
        </div>
      </section>

      <main className="mt-6 flex-1">
        <ResultList
          error={s.error}
          hasSearched={s.hasSearched}
          loading={s.loading}
          mode={s.mode}
          onPageChange={s.setPage}
          page={s.page}
          results={s.results}
          totalElements={s.totalElements}
          totalPages={s.totalPages}
        />
      </main>

      <footer className="mt-16 border-t border-rule pt-5 text-xs text-fg-muted">
        Companion demo for{' '}
        <a
          className="text-fg underline underline-offset-2 transition-colors hover:text-accent
            focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          href="https://lukasgrigis.dev/blog/spring-boot-postgres-full-text-search/"
          rel="noreferrer"
          target="_blank"
        >
          the blog post
        </a>
        .
      </footer>
    </div>
  );
}
