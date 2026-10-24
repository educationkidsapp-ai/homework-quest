import type { AttendanceSettings, UpdateAttendanceSettingsRequest } from '../../api';

/**
 * Feature 14's page as a draft the form edits, and the rules the server applies, so the page can
 * say what is wrong before Save rather than after. Pure functions: the spec tests them directly.
 */

export const CATEGORIES = ['TEACHER', 'ADMINISTRATIVE', 'WORKER'] as const;
export type Category = (typeof CATEGORIES)[number];

export const NOTIFY_POLICIES = ['IMMEDIATE', 'DIGEST', 'OFF'] as const;
export type NotifyPolicy = (typeof NOTIFY_POLICIES)[number];

/** The Gulf week first, the order the page lists the days in. */
export const WEEKDAYS = [
  'SUNDAY',
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
] as const;

export const RADIUS_MIN = 50;
export const RADIUS_MAX = 500;
const MINUTES_MAX = 600;

/** Feature 34: the five leave types, in the order the spec names them. */
export const LEAVE_TYPES = ['ANNUAL', 'SICK', 'EMERGENCY', 'UNPAID', 'OTHER'] as const;
export type LeaveType = (typeof LEAVE_TYPES)[number];

/** Who decides: the direct manager alone, or the manager and then the Admin. */
export const LEAVE_CHAINS = ['MANAGER', 'MANAGER_THEN_ADMIN'] as const;
export type LeaveChain = (typeof LEAVE_CHAINS)[number];

/** `AttendanceSettingsService`'s own bounds, so the card refuses what the server would. */
export const PERMISSION_MAX_DEFAULT = 180;
export const PERMISSION_MAX_LIMIT = 600;
export const LEAVE_DAYS_MAX = 365;

export interface ScheduleDraft {
  readonly category: Category;
  readonly weekdays: readonly string[];
  readonly start: string;
  readonly end: string;
  readonly ramadanStart: string;
  readonly ramadanEnd: string;
}

/** One leave type as the Leave card edits it. An empty `documentAfterDays` is "no document rule". */
export interface LeaveTypeDraft {
  readonly type: LeaveType;
  readonly enabled: boolean;
  readonly documentAfterDays: string;
}

/**
 * Feature 34's leave rules inside the page's one draft: the types, the yearly balance per type and
 * category, the approval chain and the longest permission in a day.
 *
 * The balances are a flat map keyed `TYPE:CATEGORY` rather than a grid of rows, because that is
 * what the grid of inputs writes into and what the PUT's list is built from. An empty string is
 * **no balance** — the pair is left out of the request, which is how the server deletes it, and is
 * how unpaid and other leave stay unlimited.
 */
export interface LeaveDraft {
  readonly chain: LeaveChain;
  readonly permissionMax: string;
  readonly types: readonly LeaveTypeDraft[];
  readonly balances: Readonly<Record<string, string>>;
}

/** The key one cell of the balances grid writes. */
export const balanceKey = (type: string, category: string): string => `${type}:${category}`;

export interface HolidayDraft {
  readonly name: string;
  readonly from: string;
  readonly to: string;
}

/**
 * Numbers are strings because `hq-input` edits strings. One Ramadan range for the whole school —
 * the dates are the calendar's, not a category's — with start and end per category.
 */
export interface SettingsDraft {
  readonly latitude: string;
  readonly longitude: string;
  readonly radius: string;
  readonly grace: string;
  readonly notArrived: string;
  readonly missingCheckOut: string;
  readonly directorNotify: NotifyPolicy;
  readonly adminNotify: NotifyPolicy;
  readonly digestTime: string;
  readonly ramadanFrom: string;
  readonly ramadanTo: string;
  readonly schedules: readonly ScheduleDraft[];
  readonly holidays: readonly HolidayDraft[];
  readonly leave: LeaveDraft;
}

/** Translation keys under `admin.attendanceSettings.error.`, by field. */
export type DraftErrors = Readonly<Record<string, string>>;

const isCategory = (value: string | undefined): value is Category =>
  (CATEGORIES as readonly string[]).includes(value ?? '');

export const isNotifyPolicy = (value: string | undefined): value is NotifyPolicy =>
  (NOTIFY_POLICIES as readonly string[]).includes(value ?? '');

export const isLeaveChain = (value: string | undefined): value is LeaveChain =>
  (LEAVE_CHAINS as readonly string[]).includes(value ?? '');

function leaveDraftOf(settings: AttendanceSettings): LeaveDraft {
  const saved = settings.leaveTypes ?? [];
  const balances: Record<string, string> = {};
  for (const balance of settings.leaveBalances ?? [])
    if (balance.type && balance.category && balance.yearlyDays != null)
      balances[balanceKey(balance.type, balance.category)] = String(balance.yearlyDays);
  return {
    chain: isLeaveChain(settings.leaveChain) ? settings.leaveChain : 'MANAGER',
    permissionMax: String(settings.permissionMaxMinutes ?? PERMISSION_MAX_DEFAULT),
    // A type the school has never touched is offered — the server's own default.
    types: LEAVE_TYPES.map((type) => {
      const one = saved.find((setting) => setting.type === type);
      return {
        type,
        enabled: one?.enabled ?? true,
        documentAfterDays: one?.documentAfterDays == null ? '' : String(one.documentAfterDays),
      };
    }),
    balances,
  };
}

export function draftOf(settings: AttendanceSettings): SettingsDraft {
  const saved = settings.schedules ?? [];
  const withRamadan = saved.find((s) => s.ramadanFrom && s.ramadanTo);
  return {
    latitude: settings.latitude == null ? '' : String(settings.latitude),
    longitude: settings.longitude == null ? '' : String(settings.longitude),
    radius: String(settings.radiusMeters ?? 150),
    grace: String(settings.graceMinutes ?? 10),
    notArrived: String(settings.notArrivedMinutes ?? 60),
    missingCheckOut: String(settings.missingCheckOutMinutes ?? 60),
    directorNotify: isNotifyPolicy(settings.directorNotify) ? settings.directorNotify : 'DIGEST',
    adminNotify: isNotifyPolicy(settings.adminNotify) ? settings.adminNotify : 'DIGEST',
    digestTime: settings.digestTime ?? '07:30',
    ramadanFrom: withRamadan?.ramadanFrom ?? '',
    ramadanTo: withRamadan?.ramadanTo ?? '',
    schedules: CATEGORIES.map((category) => {
      const s = saved.find((one) => isCategory(one.category) && one.category === category);
      return {
        category,
        weekdays: s?.weekdays ?? ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY'],
        start: s?.start ?? '07:00',
        end: s?.end ?? '14:00',
        ramadanStart: s?.ramadanStart ?? '',
        ramadanEnd: s?.ramadanEnd ?? '',
      };
    }),
    holidays: (settings.holidays ?? []).map((h) => ({
      name: h.name ?? '',
      from: h.from ?? '',
      to: h.to ?? '',
    })),
    leave: leaveDraftOf(settings),
  };
}

const whole = (value: string): number | null => (/^-?\d+$/.test(value.trim()) ? Number(value.trim()) : null);
const decimal = (value: string): number | null => {
  const n = Number(value.trim());
  return value.trim() === '' || Number.isNaN(n) ? null : n;
};

/** `HH:mm` strings compare correctly as text, as `yyyy-MM-dd` dates do. */
const before = (a: string, b: string): boolean => a !== '' && b !== '' && a < b;

export function errorsOf(draft: SettingsDraft): DraftErrors {
  const errors: Record<string, string> = {};
  const lat = draft.latitude.trim();
  const lng = draft.longitude.trim();
  if ((lat === '') !== (lng === '')) errors['location'] = 'locationPair';
  else if (lat !== '') {
    const la = decimal(lat);
    const lo = decimal(lng);
    if (la === null || la < -90 || la > 90 || lo === null || lo < -180 || lo > 180)
      errors['location'] = 'locationRange';
  }
  const radius = whole(draft.radius);
  if (radius === null || radius < RADIUS_MIN || radius > RADIUS_MAX) errors['radius'] = 'radius';
  const minutes: readonly [keyof SettingsDraft, number][] = [
    ['grace', 0],
    ['notArrived', 1],
    ['missingCheckOut', 1],
  ];
  for (const [field, min] of minutes) {
    const value = whole(draft[field] as string);
    if (value === null || value < min || value > MINUTES_MAX) errors[field] = 'minutes';
  }
  if (draft.digestTime === '') errors['digestTime'] = 'time';

  const ramadan = draft.ramadanFrom !== '' || draft.ramadanTo !== '';
  if (ramadan) {
    if (draft.ramadanFrom === '' || draft.ramadanTo === '') errors['ramadan'] = 'ramadanDates';
    else if (draft.ramadanTo < draft.ramadanFrom) errors['ramadan'] = 'ramadanOrder';
  }
  draft.schedules.forEach((s, i) => {
    if (s.weekdays.length === 0) errors[`days.${i}`] = 'days';
    if (!before(s.start, s.end)) errors[`hours.${i}`] = 'startBeforeEnd';
    // Empty Ramadan hours keep the category's normal hours; a half-filled pair is a mistake.
    const ramadanHours = s.ramadanStart !== '' || s.ramadanEnd !== '';
    if (ramadan && ramadanHours && !before(s.ramadanStart, s.ramadanEnd))
      errors[`ramadanHours.${i}`] = 'startBeforeEnd';
  });
  const permissionMax = whole(draft.leave.permissionMax);
  if (permissionMax === null || permissionMax < 1 || permissionMax > PERMISSION_MAX_LIMIT)
    errors['leave.permissionMax'] = 'permissionMax';
  draft.leave.types.forEach((type) => {
    if (type.documentAfterDays === '') return;
    const days = whole(type.documentAfterDays);
    if (days === null || days < 0 || days > LEAVE_DAYS_MAX) errors[`leave.document.${type.type}`] = 'leaveDays';
  });
  for (const [key, value] of Object.entries(draft.leave.balances)) {
    if (value.trim() === '') continue;
    const days = whole(value);
    if (days === null || days < 0 || days > LEAVE_DAYS_MAX) errors[`leave.balance.${key}`] = 'leaveDays';
  }

  draft.holidays.forEach((h, i) => {
    if (h.name.trim() === '' || h.name.trim().length > 80) errors[`holidayName.${i}`] = 'holidayName';
    if (h.from === '' || h.to === '' || h.to < h.from) errors[`holidayDates.${i}`] = 'holidayDates';
  });
  return errors;
}

export function requestOf(draft: SettingsDraft): UpdateAttendanceSettingsRequest {
  const ramadan = draft.ramadanFrom !== '' && draft.ramadanTo !== '';
  const lat = decimal(draft.latitude);
  const lng = decimal(draft.longitude);
  return {
    ...(lat !== null && lng !== null ? { latitude: lat, longitude: lng } : {}),
    radiusMeters: Number(draft.radius),
    graceMinutes: Number(draft.grace),
    notArrivedMinutes: Number(draft.notArrived),
    missingCheckOutMinutes: Number(draft.missingCheckOut),
    directorNotify: draft.directorNotify,
    adminNotify: draft.adminNotify,
    digestTime: draft.digestTime,
    schedules: draft.schedules.map((s) => ({
      category: s.category,
      weekdays: WEEKDAYS.filter((d) => s.weekdays.includes(d)),
      start: s.start,
      end: s.end,
      ...(ramadan
        ? {
            ramadanFrom: draft.ramadanFrom,
            ramadanTo: draft.ramadanTo,
            ramadanStart: s.ramadanStart || s.start,
            ramadanEnd: s.ramadanEnd || s.end,
          }
        : {}),
    })),
    holidays: draft.holidays.map((h) => ({ name: h.name.trim(), from: h.from, to: h.to })),
    leaveChain: draft.leave.chain,
    permissionMaxMinutes: Number(draft.leave.permissionMax),
    // All five, always: a type the request leaves out keeps what it had.
    leaveTypes: draft.leave.types.map((type) => ({
      type: type.type,
      enabled: type.enabled,
      ...(type.documentAfterDays.trim() === ''
        ? {}
        : { documentAfterDays: Number(type.documentAfterDays) }),
    })),
    // Only the pairs that have a balance: a pair left out is the one the server deletes.
    leaveBalances: LEAVE_TYPES.flatMap((type) =>
      CATEGORIES.flatMap((category) => {
        const value = (draft.leave.balances[balanceKey(type, category)] ?? '').trim();
        return value === '' ? [] : [{ type, category, yearlyDays: Number(value) }];
      }),
    ),
  };
}

/** The circle's radius in the 200-unit preview: 50 m is a small ring, 500 m nearly fills it. */
export function previewRadius(radiusMeters: number): number {
  const clamped = Math.min(
    RADIUS_MAX,
    Math.max(RADIUS_MIN, Number.isFinite(radiusMeters) ? radiusMeters : 150),
  );
  return 18 + ((clamped - RADIUS_MIN) / (RADIUS_MAX - RADIUS_MIN)) * 72;
}

/** The role's category, for an employee with none set — the server's rule. */
export function defaultCategory(role: string | undefined): Category {
  if (role === 'TEACHER') return 'TEACHER';
  if (role === 'WORKER') return 'WORKER';
  return 'ADMINISTRATIVE';
}
