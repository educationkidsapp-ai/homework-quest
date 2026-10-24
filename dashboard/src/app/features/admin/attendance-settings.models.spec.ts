import { describe, expect, it } from 'vitest';
import {
  defaultCategory,
  draftOf,
  errorsOf,
  previewRadius,
  requestOf,
  type SettingsDraft,
} from './attendance-settings.models';

const base = (): SettingsDraft => draftOf({});

const withSchedule = (
  patch: Partial<SettingsDraft['schedules'][number]>,
  extra: Partial<SettingsDraft> = {},
): SettingsDraft => {
  const draft = base();
  return { ...draft, ...extra, schedules: draft.schedules.map((s, i) => (i === 0 ? { ...s, ...patch } : s)) };
};

describe('attendance settings draft', () => {
  it('starts from the spec defaults: 150 m, grace 10, both thresholds 60, Sunday–Thursday', () => {
    const draft = base();
    expect(draft.radius).toBe('150');
    expect(draft.grace).toBe('10');
    expect(draft.notArrived).toBe('60');
    expect(draft.missingCheckOut).toBe('60');
    expect(draft.directorNotify).toBe('DIGEST');
    expect(draft.digestTime).toBe('07:30');
    expect(draft.schedules.map((s) => s.category)).toEqual(['TEACHER', 'ADMINISTRATIVE', 'WORKER']);
    expect(draft.schedules[0]!.weekdays).toEqual(['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY']);
    expect(errorsOf(draft)).toEqual({});
  });

  it('keeps the radius between 50 and 500 metres', () => {
    expect(errorsOf({ ...base(), radius: '49' })['radius']).toBe('radius');
    expect(errorsOf({ ...base(), radius: '501' })['radius']).toBe('radius');
    expect(errorsOf({ ...base(), radius: '50' })['radius']).toBeUndefined();
    expect(errorsOf({ ...base(), radius: '500' })['radius']).toBeUndefined();
  });

  it('asks for both coordinates or neither', () => {
    expect(errorsOf({ ...base(), latitude: '24.7' })['location']).toBe('locationPair');
    expect(errorsOf({ ...base(), latitude: '95', longitude: '46' })['location']).toBe('locationRange');
    expect(errorsOf({ ...base(), latitude: '24.7136', longitude: '46.6753' })['location']).toBeUndefined();
  });

  it('needs every start before its end', () => {
    expect(errorsOf(withSchedule({ start: '14:00', end: '07:00' }))['hours.0']).toBe('startBeforeEnd');
    expect(errorsOf(withSchedule({ start: '07:00', end: '07:00' }))['hours.0']).toBe('startBeforeEnd');
    expect(errorsOf(withSchedule({ weekdays: [] }))['days.0']).toBe('days');
  });

  it('accepts a Ramadan range only with both dates in order and Ramadan hours for each category', () => {
    expect(errorsOf({ ...base(), ramadanFrom: '2027-02-08' })['ramadan']).toBe('ramadanDates');
    expect(errorsOf({ ...base(), ramadanFrom: '2027-03-09', ramadanTo: '2027-02-08' })['ramadan']).toBe(
      'ramadanOrder',
    );
    const range = { ramadanFrom: '2027-02-08', ramadanTo: '2027-03-09' };
    expect(errorsOf({ ...base(), ...range })).toEqual({});
    expect(
      errorsOf(withSchedule({ ramadanStart: '13:00', ramadanEnd: '09:00' }, range))['ramadanHours.0'],
    ).toBe('startBeforeEnd');
    expect(errorsOf(withSchedule({ ramadanStart: '09:00' }, range))['ramadanHours.0']).toBe('startBeforeEnd');
    const ok: SettingsDraft = {
      ...base(),
      ...range,
      schedules: base().schedules.map((s) => ({ ...s, ramadanStart: '09:00', ramadanEnd: '13:00' })),
    };
    expect(errorsOf(ok)).toEqual({});
  });

  it('names every holiday and keeps its days in order', () => {
    const holidays = [{ name: '', from: '2026-11-19', to: '2026-11-15' }];
    const errors = errorsOf({ ...base(), holidays });
    expect(errors['holidayName.0']).toBe('holidayName');
    expect(errors['holidayDates.0']).toBe('holidayDates');
  });

  it('keeps the normal hours in Ramadan for a category whose Ramadan hours are empty', () => {
    const request = requestOf({ ...base(), ramadanFrom: '2027-02-08', ramadanTo: '2027-03-09' });
    expect(request.schedules?.[1]).toMatchObject({ ramadanStart: '07:00', ramadanEnd: '14:00' });
  });

  it('sends the Ramadan range on every category, and nothing about Ramadan without one', () => {
    const plain = requestOf(base());
    expect(plain.schedules?.[0]).not.toHaveProperty('ramadanFrom');
    expect(plain).not.toHaveProperty('latitude');

    const draft: SettingsDraft = {
      ...base(),
      latitude: '24.7136',
      longitude: '46.6753',
      ramadanFrom: '2027-02-08',
      ramadanTo: '2027-03-09',
      schedules: base().schedules.map((s) => ({
        ...s,
        ramadanStart: '09:00',
        ramadanEnd: '13:00',
        weekdays: ['MONDAY', 'SUNDAY'],
      })),
    };
    const request = requestOf(draft);
    expect(request.latitude).toBe(24.7136);
    expect(request.schedules?.every((s) => s.ramadanFrom === '2027-02-08' && s.ramadanEnd === '13:00')).toBe(
      true,
    );
    expect(request.schedules?.[0]?.weekdays).toEqual(['SUNDAY', 'MONDAY']);
  });

  it('reads one Ramadan range back from what the server saved', () => {
    const draft = draftOf({
      schedules: [
        {
          category: 'WORKER',
          weekdays: ['SUNDAY'],
          start: '06:00',
          end: '12:00',
          ramadanFrom: '2027-02-08',
          ramadanTo: '2027-03-09',
          ramadanStart: '08:00',
          ramadanEnd: '11:00',
        },
      ],
    });
    expect(draft.ramadanFrom).toBe('2027-02-08');
    expect(draft.schedules[2]!.start).toBe('06:00');
    expect(draft.schedules[0]!.start).toBe('07:00');
  });

  // ---- feature 34: the leave rules ------------------------------------------------------------

  it('defaults the leave rules to the spec: the manager alone, three hours, every type offered', () => {
    const { leave } = base();
    expect(leave.chain).toBe('MANAGER');
    expect(leave.permissionMax).toBe('180');
    expect(leave.types.map((type) => type.type)).toEqual([
      'ANNUAL',
      'SICK',
      'EMERGENCY',
      'UNPAID',
      'OTHER',
    ]);
    expect(leave.types.every((type) => type.enabled && type.documentAfterDays === '')).toBe(true);
    expect(leave.balances).toEqual({});
  });

  it('reads the leave rules the server saved, balances keyed by type and category', () => {
    const { leave } = draftOf({
      leaveChain: 'MANAGER_THEN_ADMIN',
      permissionMaxMinutes: 120,
      leaveTypes: [
        { type: 'SICK', enabled: true, documentAfterDays: 3 },
        { type: 'UNPAID', enabled: false },
      ],
      leaveBalances: [{ type: 'ANNUAL', category: 'TEACHER', yearlyDays: 30 }],
    });
    expect(leave.chain).toBe('MANAGER_THEN_ADMIN');
    expect(leave.permissionMax).toBe('120');
    expect(leave.types[1]).toEqual({ type: 'SICK', enabled: true, documentAfterDays: '3' });
    // A type the answer never mentioned is offered, which is the server's own default.
    expect(leave.types[0]).toEqual({ type: 'ANNUAL', enabled: true, documentAfterDays: '' });
    expect(leave.types[3]!.enabled).toBe(false);
    expect(leave.balances).toEqual({ 'ANNUAL:TEACHER': '30' });
  });

  it('sends all five types and only the balances that have a value', () => {
    const draft = base();
    const request = requestOf({
      ...draft,
      leave: {
        ...draft.leave,
        balances: { 'ANNUAL:TEACHER': '30', 'ANNUAL:WORKER': '', 'SICK:ADMINISTRATIVE': '15' },
      },
    });
    expect(request.leaveTypes).toHaveLength(5);
    expect(request.leaveChain).toBe('MANAGER');
    expect(request.permissionMaxMinutes).toBe(180);
    // An emptied pair is left out, which is how the server deletes it.
    expect(request.leaveBalances).toEqual([
      { type: 'ANNUAL', category: 'TEACHER', yearlyDays: 30 },
      { type: 'SICK', category: 'ADMINISTRATIVE', yearlyDays: 15 },
    ]);
  });

  it('refuses a permission limit and a balance the server would answer 400 to', () => {
    const draft = base();
    const bad = (leave: Partial<SettingsDraft['leave']>): SettingsDraft => ({
      ...draft,
      leave: { ...draft.leave, ...leave },
    });
    expect(errorsOf(bad({ permissionMax: '0' }))['leave.permissionMax']).toBe('permissionMax');
    expect(errorsOf(bad({ permissionMax: '601' }))['leave.permissionMax']).toBe('permissionMax');
    expect(errorsOf(bad({ permissionMax: '600' }))['leave.permissionMax']).toBeUndefined();
    expect(errorsOf(bad({ balances: { 'ANNUAL:TEACHER': '366' } }))['leave.balance.ANNUAL:TEACHER']).toBe(
      'leaveDays',
    );
    // Empty is "no balance", not a mistake.
    expect(errorsOf(bad({ balances: { 'ANNUAL:TEACHER': '' } }))).toEqual({});
    expect(
      errorsOf(
        bad({
          types: draft.leave.types.map((type) =>
            type.type === 'SICK' ? { ...type, documentAfterDays: '400' } : type,
          ),
        }),
      )['leave.document.SICK'],
    ).toBe('leaveDays');
  });

  it('grows the preview circle with the radius and defaults a category from the role', () => {
    expect(previewRadius(50)).toBeLessThan(previewRadius(500));
    expect(previewRadius(9999)).toBe(previewRadius(500));
    expect(defaultCategory('TEACHER')).toBe('TEACHER');
    expect(defaultCategory('WORKER')).toBe('WORKER');
    expect(defaultCategory('MANAGERIAL')).toBe('ADMINISTRATIVE');
  });
});
