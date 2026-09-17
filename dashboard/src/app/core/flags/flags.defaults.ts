/**
 * The 14 keys of §4 with their `default_on`, mirroring `shared-api`'s
 * `quest.api.DEFAULT_FLAGS` (`ContentApi.kt`) — itself `V5__flags_themes.sql`'s seed, on for
 * what ships today and off for what is not built yet.
 *
 * `FlagService`'s fallback of last resort: what a browser that has never once fetched a flag
 * map for a school, and cannot reach the server now either, shows instead of reading every
 * flag as off. `flags.defaults.spec.ts` keeps this list honest against the migration.
 */
export const DEFAULT_FLAGS: Readonly<Record<string, boolean>> = {
  'lessons.pdf': true,
  'lessons.slides': true,
  'lessons.images': true,
  'lessons.manual': true,
  'levels.three': true,
  'retell.recording': true,
  'openAnswer.drawing': true,
  'parentPanel.arabic': true,
  complaints: false,
  announcements: false,
  teacherQuestions: false,
  'stickers.treasureChest': true,
  'progress.weeklyEmail': false,
  certificates: true,
};
