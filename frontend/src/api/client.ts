import type { SearchParams, SearchResponseDto } from './types';

export async function search(params: SearchParams): Promise<SearchResponseDto> {
  const query = new URLSearchParams({
    q: params.q,
    mode: params.mode,
    page: String(params.page),
    size: String(params.size),
  });
  if (params.genre) query.set('genre', params.genre);

  const res = await fetch(`/api/search?${query.toString()}`);
  if (!res.ok) {
    throw new Error(`Search request failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as SearchResponseDto;
}

/** Backs the genre filter with the actual seeded genres (GET /api/genres) instead of a hand-copied list. */
export async function fetchGenres(): Promise<string[]> {
  const res = await fetch('/api/genres');
  if (!res.ok) {
    throw new Error(`Genre list request failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as string[];
}
