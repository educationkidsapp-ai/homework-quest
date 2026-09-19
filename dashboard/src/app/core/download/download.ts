/**
 * Save bytes the API answered with, under a name a teacher will recognise on her desktop.
 *
 * **Why not a plain link.** Every export on the teacher's side — `results.csv`, `gradebook.xlsx`
 * — is behind the bearer, and an `<a href="/teacher/…/results.csv">` sends no `Authorization`
 * header, so the browser navigates to a 401 and the tab she was working in is gone. The bytes
 * therefore come through the generated client, and this turns them into a file.
 *
 * **Why an object URL is safe here when `MediaService` may not use one.** The shipped CSP is
 * `default-src 'self'` with `img-src` widened (`DashboardController.CSP`), so a `blob:` URL
 * cannot be *rendered*. A download is not a render: `a[download]` hands the bytes to the
 * browser's download manager and never fetches them into the document, which is why this is the
 * one place a `blob:` is the right carrier. The URL is revoked on the next frame — after the
 * click, before the tab has a chance to accumulate them.
 */
export function saveFile(body: Blob | string, filename: string, type: string): void {
  const blob = typeof body === 'string' ? new Blob([body], { type }) : body;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  // Firefox needs the element in the document for a programmatic click to count.
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * A `data:` URL back into bytes, without a network call.
 *
 * `fetch(dataUrl)` would be the one-liner, and the shipped CSP's `connect-src 'self'` refuses
 * it. `MediaService` hands out `data:` URLs because that is what `img-src` allows, so anything
 * that wants to *save* one rather than paint it has to decode it here.
 */
export function blobOfDataUrl(dataUrl: string): Blob {
  const comma = dataUrl.indexOf(',');
  if (!dataUrl.startsWith('data:') || comma < 0) throw new Error('not a data URL');
  const header = dataUrl.slice(5, comma);
  const type = header.split(';')[0] || 'application/octet-stream';
  const body = dataUrl.slice(comma + 1);
  if (!header.includes('base64')) return new Blob([decodeURIComponent(body)], { type });
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type });
}

/** `audio/webm` → `webm`, `image/jpeg` → `jpeg`; the subtype is a good enough extension. */
export function extensionOf(type: string, fallback: string): string {
  const subtype = type.split('/')[1]?.split(';')[0]?.trim();
  return subtype && /^[a-z0-9]+$/i.test(subtype) ? subtype : fallback;
}

/** `1A British — Counting to ten — results.csv`, with the characters a file system refuses gone. */
export function exportName(parts: readonly (string | null | undefined)[], extension: string): string {
  const name = parts
    .map((part) => (part ?? '').trim())
    .filter((part) => part.length > 0)
    .join(' - ')
    .replace(/[\\/:*?"<>|]/g, '')
    .slice(0, 120);
  return `${name || 'export'}.${extension}`;
}
