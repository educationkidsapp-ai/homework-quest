/**
 * **Which switch is which, in the Admin's words.**
 *
 * The server's `GET /admin/flags` already answers every key with a one-line description, but
 * those lines are written for whoever reads `FlagKeys.java`: they name routes, migrations and
 * permission keys. The Admin needs to know what appears on screen and who sees it, so the
 * sentence she reads lives in the translation bundle (`admin.switches.keys.<id>.*`) and this
 * table is the only place that says which key it belongs to.
 *
 * **Nothing is hidden.** This table is a *grouping*, not a filter: the page walks the server's
 * definitions and every key not named here lands in "Other" with the server's own description.
 * A flag added to `FlagKeys` and seeded by a migration therefore shows up on the page the day it
 * exists, whether or not anybody remembered this file.
 *
 * `id` is the translation id and is deliberately not derived from `key`: half the keys carry a
 * dot (`lessons.pdf`), which would nest them as objects in the bundle and break the one check
 * that keeps English and Arabic honest.
 */

/** One switch the Admin is given words for. */
export interface SwitchEntry {
  /** The server's flag key, exactly as `FlagKeys` spells it. */
  readonly key: string;
  /** The translation id under `admin.switches.keys.`. */
  readonly id: string;
  /** The feature number in `docs/features/`, shown as "Feature 14". Absent for §4's original set. */
  readonly feature?: number;
  /** Keys this one rides on: the page names them and warns while any of them is off. */
  readonly needs?: readonly string[];
  /** An extra caution, under `admin.switches.notes.`, shown whatever the other switches say. */
  readonly noteKey?: string;
}

export interface SwitchGroup {
  /** The translation id under `admin.switches.groups.`. */
  readonly id: string;
  readonly entries: readonly SwitchEntry[];
}

/**
 * The groups, in the order the Admin reads them: her own staff first (the switches she asked for
 * when she could not turn the staff app on), then what she and her managers use, then what
 * reaches the parents, then the teaching itself.
 */
export const SWITCH_GROUPS: readonly SwitchGroup[] = [
  {
    id: 'staff',
    entries: [
      { key: 'staffMobileApp', id: 'staffMobileApp', feature: 13 },
      { key: 'staffAttendance', id: 'staffAttendance', feature: 14, needs: ['staffMobileApp'] },
      { key: 'remoteStudyDays', id: 'remoteStudyDays', feature: 23, needs: ['staffAttendance'] },
      { key: 'staffLeave', id: 'staffLeave', feature: 34, needs: ['staffAttendance'] },
      { key: 'chatOfficeHours', id: 'chatOfficeHours', feature: 19, needs: ['chat'] },
      { key: 'floorSupervisor', id: 'floorSupervisor', feature: 20, noteKey: 'needsLatestApp' },
      { key: 'teachingMaterials', id: 'teachingMaterials', feature: 31 },
      { key: 'childEvaluations', id: 'childEvaluations', feature: 33 },
      { key: 'schoolStructure', id: 'schoolStructure', feature: 36 },
    ],
  },
  {
    id: 'leaders',
    entries: [
      { key: 'todayHome', id: 'todayHome', feature: 21 },
      { key: 'morningBrief', id: 'morningBrief', feature: 25 },
      { key: 'voiceAnnouncement', id: 'voiceAnnouncement', feature: 22, needs: ['announcements'] },
      { key: 'documentReader', id: 'documentReader', feature: 26, noteKey: 'documentReader' },
      { key: 'complaintTriage', id: 'complaintTriage', feature: 27, needs: ['complaints'] },
    ],
  },
  {
    id: 'parents',
    entries: [
      { key: 'announcements', id: 'announcements' },
      { key: 'chat', id: 'chat' },
      { key: 'complaints', id: 'complaints' },
      { key: 'teacherQuestions', id: 'teacherQuestions' },
      { key: 'parentEveningCard', id: 'parentEveningCard', feature: 28 },
      { key: 'languageBridge', id: 'languageBridge', feature: 29, needs: ['chat'] },
      { key: 'childTeam', id: 'childTeam', feature: 32, needs: ['chat'] },
      { key: 'classChannel', id: 'classChannel', feature: 32, needs: ['chat'] },
    ],
  },
  {
    id: 'lessons',
    entries: [
      { key: 'gradebook', id: 'gradebook' },
      { key: 'openStopMarking', id: 'openStopMarking', needs: ['gradebook'] },
      { key: 'exams', id: 'exams' },
      { key: 'smartHints', id: 'smartHints', feature: 30, noteKey: 'smartHints' },
      { key: 'certificates', id: 'certificates' },
    ],
  },
];

/** Every key this table has words for, so the page can tell what belongs in "Other". */
export const DESCRIBED_KEYS: ReadonlySet<string> = new Set(
  SWITCH_GROUPS.flatMap((group) => group.entries.map((entry) => entry.key)),
);

/** The id of the group that catches whatever the server defines and this table does not name. */
export const OTHER_GROUP_ID = 'other';
