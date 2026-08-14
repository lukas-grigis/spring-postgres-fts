import { useEffect, useState } from 'react';
import { fetchGenres } from '../api/client';

/** Fetched once on mount from GET /api/genres — the seeded genres, not a hand-copied list. */
export function useGenres(): readonly string[] {
  const [genres, setGenres] = useState<readonly string[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetchGenres()
      .then((names) => {
        if (!cancelled) setGenres(names);
      })
      .catch(() => {
        // Genre filter degrades to "All genres only" — search itself still works without it.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return genres;
}
