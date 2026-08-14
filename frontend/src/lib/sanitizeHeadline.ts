/**
 * The server escapes the excerpt in SQL *before* ts_headline runs, so what arrives here is
 * already-escaped text whose only live markup is the `<mark>` pair this application asked for.
 * The split is deliberate: the server owns `&`, the client owns the angle brackets. Escaping `&`
 * again here would render a server-escaped `Tom &amp; Jerry` as the visible text `Tom &amp; Jerry`.
 *
 * The second layer still holds if the server step were ever bypassed: escaping `<` and `>` first
 * and restoring only the two exact literals means anything with an attribute — a space after the
 * tag name — stays inert. The worst a hostile excerpt can produce is a bare, attribute-free
 * `<mark>`. See docs/POSTGRES-FTS.md, "Highlighting: ts_headline does not escape — so the input
 * is escaped first".
 */
export function sanitizeHeadline(raw: string): string {
  const escaped = raw.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return escaped.replace(/&lt;mark&gt;/g, '<mark>').replace(/&lt;\/mark&gt;/g, '</mark>');
}
