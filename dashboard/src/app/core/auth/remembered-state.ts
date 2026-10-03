/**
 * **What this browser remembers on one account's behalf** — and forgets when that account leaves.
 *
 * D1 (the owner's list of 2026-10-01, "errors on first open"). QA was re-created as a new school
 * and the owner moves between roles in one browser; whatever the last account left behind was then
 * read by the next one, whose requests for those rows the server answered with an honest 404 and
 * the dashboard painted as a red band over a screen nobody had touched.
 *
 * The rule: remembered state belongs to the session that wrote it. It is erased on sign-out and
 * whenever the session's owner changes (`AuthService`), so the next account starts from the
 * server's lists and not from somebody else's ids.
 *
 * Listed by prefix rather than by service, because the point is that nothing here needs to know
 * who wrote a key:
 *
 * - `hq.school` — the Admin's chosen school, sent as `X-School-Id` on every request. A school id
 *   from before the database was re-created answers `404 school not found` to everything,
 *   including `/me`.
 * - `hq.flags.*` — the last flag map per school, kept for a slow cold start. Keyed by school id,
 *   so a re-created school's would never be read again and only accumulate.
 * - `hq_chat_att_*` (session) — left by the pre-D4 composer, which kept "attached" files here.
 *
 * **Not here:** the language, the colour scheme and the rail's collapsed state are the browser's
 * preferences rather than an account's, and `hq.course.<userId>` is keyed by the user it belongs
 * to and holds a curriculum and a grade — no row id that could go stale.
 */
const LOCAL_PREFIXES = ['hq.school', 'hq.flags.'] as const;
const SESSION_PREFIXES = ['hq_chat_att_'] as const;

export function forgetRememberedState(view: Window | null): void {
  if (view === null) return;
  purge(() => view.localStorage, LOCAL_PREFIXES);
  purge(() => view.sessionStorage, SESSION_PREFIXES);
}

/** Storage access itself can throw (private-mode Safari), hence the thunk. */
function purge(storage: () => Storage, prefixes: readonly string[]): void {
  try {
    const store = storage();
    const doomed: string[] = [];
    for (let index = 0; index < store.length; index++) {
      const key = store.key(index);
      if (key !== null && prefixes.some((prefix) => key.startsWith(prefix))) doomed.push(key);
    }
    for (const key of doomed) store.removeItem(key);
  } catch {
    // Nothing readable is nothing to forget.
  }
}
