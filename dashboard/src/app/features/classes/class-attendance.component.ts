import {
  ChangeDetectionStrategy,
  Component,
  Input,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { ClassAttendanceResponse as AttendanceDayDto } from '../../api';
import { AttendanceService } from '../../core/attendance/attendance.service';
import {
  AttendanceStatus,
  ClassAttendanceItem,
  ClassAttendanceResponse,
} from '../../core/attendance/attendance.models';
import { exportName, saveFile } from '../../core/download/download';
import { activeLang } from '../../core/i18n/active-lang';
import { BandComponent, ButtonComponent, CardComponent, SkeletonComponent, ToastComponent } from '../../ui';
import { attendanceCsv, attendanceRange } from './attendance-range';

@Component({
  selector: 'hq-class-attendance',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    SkeletonComponent,
    ToastComponent,
    FormsModule,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './class-attendance.component.html',
  styleUrl: './class-attendance.component.scss',
})
export class ClassAttendanceComponent {
  private readonly attendanceService = inject(AttendanceService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  @Input({ required: true }) classId!: string;
  @Input() className = '';

  /**
   * R6 (DR2): draw the roster, and none of the controls that mark it.
   *
   * A coordinator reads attendance and may not touch it, so the status pills, the notes, "Mark
   * all present" and Save are not rendered at all — hidden rather than disabled, like every other
   * control she has no key for. The same input the shared calendar takes (`hq-class-calendar`).
   */
  readonly readOnly = input(false);

  /**
   * R6: a **range** of days, already read by the caller, instead of the one day this fetches.
   *
   * `GET /coordinator/classes/{id}/attendance?from&to` answers a day per element, which is the
   * table she wants — children down the side, days across the top, her four totals on the end.
   * Set, it takes the place of the internal single-day read: the teacher's screen is a marking
   * screen for today and hers is a fortnight at a glance, and the roster, the avatars, the
   * colours and the four words are the same table either way.
   */
  readonly days = input<readonly AttendanceDayDto[] | null>(null);

  /**
   * Where a child's name goes — her report lives under a different area for each role.
   *
   * `null` means **nowhere**: MG2a took the child report off the manager's rail, so on the one
   * record screen she kept, a name that was still a link bounced her to her Home. A name is a
   * name then, not a door with the handle painted over.
   */
  readonly childBase = input<string | null>('/teacher/children');

  /** U1 item 5: the green strip goes away on its own after three seconds. */
  protected readonly savedToastMs = 3000;

  protected readonly todayStr: string = new Date().toISOString().split('T')[0] ?? '';
  protected readonly selectedDate = signal<string>(new Date().toISOString().split('T')[0] ?? '');
  protected readonly isLoading = signal<boolean>(false);
  protected readonly isSaving = signal<boolean>(false);
  protected readonly saveSuccess = signal<boolean>(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly attendanceData = signal<ClassAttendanceResponse | null>(null);
  protected readonly students = signal<ClassAttendanceItem[]>([]);

  constructor() {
    effect(() => {
      // A caller that supplies the days owns the read: fetching today's on top of them would
      // paint one day's roster over a range she asked for.
      if (this.days() !== null) return;
      const id = this.classId;
      const date = this.selectedDate();
      if (id && date) {
        this.loadAttendance(id, date);
      }
    });
  }

  // ---- R6: the range ------------------------------------------------------------------------

  /** The matrix, or `null` when this is the teacher's single-day screen. */
  protected readonly range = computed(() => {
    const days = this.days();
    return days === null ? null : attendanceRange(days);
  });

  protected childLink(childId: string): readonly string[] | null {
    const base = this.childBase();
    return base === null ? null : [base, childId];
  }

  /**
   * "Sep 14" — a column heading in her own language, the same shape the gradebook's columns use.
   *
   * The review found a raw `MM-DD` slice here, which is neither Arabic nor English and reads as a
   * fraction. The full date stays in each cell's `title`, where the day of the week is the thing
   * a register is actually read by.
   */
  protected dayHeader(date: string): string {
    this.lang();
    return new Intl.DateTimeFormat(this.lang(), {
      day: 'numeric',
      month: 'short',
      timeZone: 'UTC',
    }).format(new Date(`${date}T00:00:00Z`));
  }

  /** "92%", or a dash for a child whose register was never kept — never a flattering 100%. */
  protected rateText(rate: number | null): string {
    return rate === null ? '—' : `${rate}%`;
  }

  /** The short word in a cell — "P", "L", "A", "E" — with the full one in its title. */
  protected cellWord(status: AttendanceStatus): string {
    this.lang();
    return status === 'NOT_MARKED' ? '·' : this.transloco.translate<string>(`attendance.short.${status}`);
  }

  /** "Present" — the mark itself, for the screen that may read it and not change it. */
  protected statusWord(status: AttendanceStatus): string {
    this.lang();
    return this.transloco.translate<string>(
      status === 'NOT_MARKED' ? 'attendance.notMarked' : `attendance.${status.toLowerCase()}`,
    );
  }

  /** "Omar · 2026-09-15 · Absent" — everything a one-letter square cannot fit. */
  protected cellTitle(childName: string, date: string, status: AttendanceStatus): string {
    return `${childName} · ${date} · ${this.statusWord(status)}`;
  }

  protected exportCsv(): void {
    const range = this.range();
    if (!range) return;
    const csv = attendanceCsv(range, {
      child: this.tr('results.table.child'),
      present: this.tr('attendance.present'),
      late: this.tr('attendance.late'),
      absent: this.tr('attendance.absent'),
      excused: this.tr('attendance.excused'),
      rate: this.tr('attendance.rate'),
    });
    saveFile(
      csv,
      exportName([this.className, this.tr('attendance.attendance')], 'csv'),
      'text/csv;charset=utf-8',
    );
  }

  private tr(key: string): string {
    return this.transloco.translate<string>(key);
  }

  protected readonly totalCount = computed(() => this.students().length);
  protected readonly presentCount = computed(
    () => this.students().filter((s) => s.status === 'PRESENT').length,
  );
  protected readonly lateCount = computed(() => this.students().filter((s) => s.status === 'LATE').length);
  protected readonly absentCount = computed(
    () => this.students().filter((s) => s.status === 'ABSENT').length,
  );
  protected readonly excusedCount = computed(
    () => this.students().filter((s) => s.status === 'EXCUSED').length,
  );

  protected readonly attendanceRate = computed(() => {
    const total = this.totalCount();
    if (total === 0) return 100;
    const marked = this.presentCount() + this.lateCount() + this.absentCount() + this.excusedCount();
    if (marked === 0) return 100;
    const rate = ((this.presentCount() + this.lateCount()) / marked) * 100;
    return Math.round(rate * 10) / 10;
  });

  protected readonly formattedDate = computed(() => {
    this.lang();
    const [y, m, d] = this.selectedDate().split('-').map(Number);
    if (!y || !m || !d) return this.selectedDate();
    const dateObj = new Date(Date.UTC(y, m - 1, d));
    return dateObj.toLocaleDateString(this.lang() === 'ar' ? 'ar-SA' : 'en-US', {
      weekday: 'short',
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC',
    });
  });

  private loadAttendance(classId: string, date: string): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);
    this.saveSuccess.set(false);

    this.attendanceService.getClassAttendance(classId, date).subscribe({
      next: (res) => {
        this.attendanceData.set(res);
        // If a student has no status recorded yet, default to PRESENT for quick one-click confirmation
        const mapped = (res.students ?? []).map((s) => ({
          ...s,
          status: s.status === 'NOT_MARKED' ? 'PRESENT' : s.status,
          notes: s.notes ?? '',
        }));
        this.students.set(mapped);
        this.isLoading.set(false);
      },
      error: (err: unknown) => {
        this.isLoading.set(false);
        const message = err instanceof Error ? err.message : 'Failed to load attendance';
        this.errorMessage.set(message);
      },
    });
  }

  protected shiftDate(days: number): void {
    const parts = this.selectedDate().split('-').map(Number);
    const y = parts[0] ?? new Date().getFullYear();
    const m = parts[1] ?? 1;
    const d = parts[2] ?? 1;
    const dateObj = new Date(Date.UTC(y, m - 1, d));
    dateObj.setUTCDate(dateObj.getUTCDate() + days);
    const nextStr = dateObj.toISOString().split('T')[0];
    if (nextStr) {
      this.selectedDate.set(nextStr);
    }
  }

  protected setToday(): void {
    const today = this.todayStr || new Date().toISOString().split('T')[0] || '';
    this.selectedDate.set(today);
  }

  protected onDateChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    if (target.value) {
      this.selectedDate.set(target.value);
    }
  }

  protected setStatus(childId: string, status: AttendanceStatus): void {
    this.saveSuccess.set(false);
    this.students.update((list) => list.map((s) => (s.childId === childId ? { ...s, status } : s)));
  }

  protected onNotesChange(childId: string, notes: string): void {
    this.saveSuccess.set(false);
    this.students.update((list) => list.map((s) => (s.childId === childId ? { ...s, notes } : s)));
  }

  protected markAll(status: AttendanceStatus): void {
    this.saveSuccess.set(false);
    this.students.update((list) => list.map((s) => ({ ...s, status })));
  }

  protected save(): void {
    if (!this.classId) return;
    this.isSaving.set(true);
    this.errorMessage.set(null);
    this.saveSuccess.set(false);

    const payload = {
      date: this.selectedDate(),
      items: this.students().map((s) => ({
        childId: s.childId,
        status: s.status,
        notes: s.notes ? s.notes.trim() : null,
      })),
    };

    this.attendanceService.saveClassAttendance(this.classId, payload).subscribe({
      next: (res) => {
        this.attendanceData.set(res);
        this.isSaving.set(false);
        this.saveSuccess.set(true);
      },
      error: (err: unknown) => {
        this.isSaving.set(false);
        const message = err instanceof Error ? err.message : 'Failed to save attendance';
        this.errorMessage.set(message);
      },
    });
  }
}
