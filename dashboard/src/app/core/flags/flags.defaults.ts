/**
 * Every flag key with its `default_on`, mirroring `shared-api`'s `quest.api.DEFAULT_FLAGS`
 * (`ContentApi.kt`) and the migrations that seed them.
 *
 * Since `V54__flags_on_by_default.sql` (D30, owner 2026-10-17) that is **on for everything
 * except `multiSchool`**: the school buys the whole platform and the Admin turns off what she does
 * not want on Admin → Feature switches. `multiSchool` is a tenancy mode, not a feature (D13), so
 * it stays off.
 *
 * `FlagService`'s fallback of last resort: what a browser that has never once fetched a flag
 * map for a school, and cannot reach the server now either, shows instead of reading every
 * flag as off. `flags.defaults.spec.ts` keeps this list honest against the migrations.
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
  complaints: true,
  announcements: true,
  teacherQuestions: true,
  'stickers.treasureChest': true,
  'progress.weeklyEmail': true,
  certificates: true,
  // V7 (N1). `multiSchool` is the one switch V54 leaves off: off is what makes ADMIN mean
  // "this school" (D13).
  multiSchool: false,
  webPlayer: true,
  gradebook: true,
  openStopMarking: true,
  exams: true,
  'teacher.rosterEdit': true,
  'join.byList': true,
  // V15 (C1).
  chat: true,
  // V36 (feature 13).
  staffMobileApp: true,
  // V37 (feature 14).
  staffAttendance: true,
  // V41 (feature 19).
  chatOfficeHours: true,
  // V42 (feature 20).
  floorSupervisor: true,
  // V43 (feature 21).
  todayHome: true,
  // V44 (feature 22).
  voiceAnnouncement: true,
  // V46 (feature 23).
  remoteStudyDays: true,
  // V47 (feature 25).
  morningBrief: true,
  // V48 (feature 26).
  documentReader: true,
  // V49 (feature 27).
  complaintTriage: true,
  // V50 (feature 28).
  parentEveningCard: true,
  // V51 (feature 29).
  languageBridge: true,
  // V52 (feature 30).
  smartHints: true,
  // V53 (feature 31).
  teachingMaterials: true,
  // V55 (feature 32). Both also need `chat`, which the services check beside them.
  childTeam: true,
  classChannel: true,
  // V56 (feature 33).
  childEvaluations: true,
  // V57 (feature 34).
  staffLeave: true,
};
