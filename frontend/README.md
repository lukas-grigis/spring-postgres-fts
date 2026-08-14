# React frontend

Single-page search UI for the book catalogue: one search box, four retriever modes
(Lexical · Fuzzy · Synonym · Fused), a genre filter, `<mark>`-highlighted snippets, and — in
FUSED mode — a per-result rank-provenance strip showing what each retriever contributed to the
RRF sum. React 19 + Vite + Tailwind 4, no component library.

## Run it

```bash
npm install
npm run dev        # http://localhost:5173, proxies /api to :8080
```

Expects the backend on `:8080` (`mise run app` from the repo root). `mise run demo` at the root
does all of it in one command.

## The API contract

There is no code generation step. `src/api/types.ts` is a hand-written mirror of the response
shape, so `npm run build` typechecks and bundles with no backend running — a generator that
needs a live `/api-docs` could not do that. The shapes are reviewed against `SearchControllerIT`
whenever the API changes, which is what keeps them honest.

## Checks

```bash
npm run build            # tsc -b && vite build — no backend needed
npm run lint
npm run format:check
npm run test             # vitest — covers the headline sanitiser
```

## Stack

- React 19 (hooks only, no state library)
- Vite 8 + @vitejs/plugin-react
- Tailwind CSS 4 via @tailwindcss/vite (light + dark)
- ESLint 9 + Prettier
