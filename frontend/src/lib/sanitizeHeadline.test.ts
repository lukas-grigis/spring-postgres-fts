import { describe, expect, it } from 'vitest';
import { sanitizeHeadline } from './sanitizeHeadline';

describe('sanitizeHeadline', () => {
  it('keeps the two literals the backend is configured to emit', () => {
    expect(sanitizeHeadline('a <mark>whale</mark> b')).toBe('a <mark>whale</mark> b');
  });

  it('neutralizes a script tag', () => {
    expect(sanitizeHeadline('<script>alert(1)</script>')).toBe(
      '&lt;script&gt;alert(1)&lt;/script&gt;',
    );
  });

  it('neutralizes an attribute-carrying tag, which a strip-tags regex would leak', () => {
    expect(sanitizeHeadline('<img src=x onerror=alert(1)>')).toBe(
      '&lt;img src=x onerror=alert(1)&gt;',
    );
  });

  it('does not unescape a mark that carries attributes', () => {
    // Only the exact literals <mark> and </mark> are restored, so anything with a space after
    // the tag name — i.e. anything with an attribute — stays inert.
    expect(sanitizeHeadline('<mark onmouseover="alert(1)">x</mark>')).toBe(
      '&lt;mark onmouseover="alert(1)"&gt;x</mark>',
    );
  });

  it('leaves the server-escaped ampersand alone rather than escaping it twice', () => {
    // The SQL side already turned `&` into `&amp;`; escaping it again here would put the literal
    // text "Tom &amp; Jerry" on the page. The server owns `&`, this function owns the brackets.
    expect(sanitizeHeadline('Tom &amp; Jerry')).toBe('Tom &amp; Jerry');
  });
});
