import {
  ChangeDetectionStrategy,
  Component,
  Input,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AttendanceService } from '../../core/attendance/attendance.service';
import {
  AttendanceStatus,
  ClassAttendanceItem,
  ClassAttendanceResponse,
} from '../../core/attendance/attendance.models';
import { activeLang } from '../../core/i18n/active-lang';
import { BandComponent, ButtonComponent, CardComponent, SkeletonComponent } from '../../ui';

@Component({
  selector: 'hq-class-attendance',
  imports: [BandComponent, ButtonComponent, CardComponent, SkeletonComponent, FormsModule, TranslocoPipe],
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
      const id = this.classId;
      const date = this.selectedDate();
      if (id && date) {
        this.loadAttendance(id, date);
      }
    });
  }

  protected readonly totalCount = computed(() => this.students().length);
  protected readonly presentCount = computed(
    () => this.students().filter((s) => s.status === 'PRESENT').length,
  );
  protected readonly lateCount = computed(
    () => this.students().filter((s) => s.status === 'LATE').length,
  );
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
    this.students.update((list) =>
      list.map((s) => (s.childId === childId ? { ...s, status } : s)),
    );
  }

  protected onNotesChange(childId: string, notes: string): void {
    this.saveSuccess.set(false);
    this.students.update((list) =>
      list.map((s) => (s.childId === childId ? { ...s, notes } : s)),
    );
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
