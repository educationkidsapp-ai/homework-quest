import { type Signal, computed, inject, signal } from '@angular/core';
import { type SelectOption } from '../../ui';
import { StaffScopeService } from './staff-scope.service';

/**
 * "Which of her sections?" — the one control all three of R6's screens open with.
 *
 * Attendance, Gradebook and Exams each read **one** class at a time, because every one of R3's
 * three routes is keyed by a class id: a table of six grades at once would be six requests and a
 * screen that says less than any of them. The picker defaults to her first section in scope, so
 * none of the three ever draws an empty state that only means "choose something".
 *
 * A function rather than a component: what the three screens share is the *state* — the selected
 * id, the options, and the fact that the id has to follow the classes as they land — and the
 * `<hq-select>` itself is one line of template each.
 */
export interface ClassPicker {
  readonly classId: Signal<string>;
  readonly className: Signal<string>;
  readonly options: Signal<readonly SelectOption[]>;
  select(classId: string): void;
}

export function classPicker(): ClassPicker {
  const co = inject(StaffScopeService);
  const chosen = signal('');

  const options = computed<readonly SelectOption[]>(() =>
    co.classes().map((row) => ({ value: row.classId, label: row.className })),
  );

  /**
   * Her choice while it is still one of hers, and otherwise the first section in scope.
   *
   * Derived rather than written by an effect: the classes arrive after the screen does, and an
   * effect that set the signal would make the first render's `classId` empty — one wasted request
   * for `''` on every one of the three screens.
   */
  const classId = computed(() => {
    const all = options();
    const mine = chosen();
    if (mine !== '' && all.some((option) => option.value === mine)) return mine;
    return all[0]?.value ?? '';
  });

  const className = computed(() => options().find((option) => option.value === classId())?.label ?? '');

  return { classId, className, options, select: (next: string) => chosen.set(next) };
}
