import { Injectable, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoService } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { FeatureFlagsApi } from '../../api';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
import { BandService } from '../band/band.service';
import { DEFAULT_FLAGS } from './flags.defaults';

/**
 * The flag keys this dashboard names, mirroring `quest.server.flags.FlagKeys`: §4's fourteen
 * plus the seven `V7__sections.sql` adds for the one-school build (N1). Only the ones a screen
 * actually reads are listed — `FlagService` keys on strings, so an unnamed flag still works;
 * naming one is how a typo in a template becomes a compile error.
 */
export const FLAGS = {
  lessonsPdf: 'lessons.pdf',
  lessonsSlides: 'lessons.slides',
  lessonsImages: 'lessons.images',
  lessonsManual: 'lessons.manual',
  levelsThree: 'levels.three',
  retellRecording: 'retell.recording',
  openAnswerDrawing: 'openAnswer.drawing',
  parentPanelArabic: 'parentPanel.arabic',
  complaints: 'complaints',
  announcements: 'announcements',
  teacherQuestions: 'teacherQuestions',
  stickersTreasureChest: 'stickers.treasureChest',
  progressWeeklyEmail: 'progress.weeklyEmail',
  certificates: 'certificates',
  multiSchool: 'multiSchool',
  // N2.3: roster editing by the teacher herself. Off by default — most schools want the
  // office to own who is in a class, and the ones that do not can turn it on per school.
  teacherRosterEdit: 'teacher.rosterEdit',
  // N4.2: scores. `gradebook` is the whole of §7 — the Results page, the grid and the child
  // page, and the server's `GradingController` carries it too. `openStopMarking` is the half a
  // school can be without: it gates only `PUT /teacher/marks`, so a school with the gradebook
  // and not the marking reads every number and writes none.
  gradebook: 'gradebook',
  openStopMarking: 'openStopMarking',
  // N4.4: §8's exams. One flag over the whole of `ExamController` — the tab, the New exam form,
  // the settings card in the editor and the results page — so a school that has not bought them
  // gets the same silence from the dashboard as from the API.
  exams: 'exams',
} as const;

export type FlagKey = (typeof FLAGS)[keyof typeof FLAGS];

const STORAGE_PREFIX = 'hq.flags.';

function storageKey(schoolId: string | null): string {
  return `${STORAGE_PREFIX}${schoolId ?? 'platform'}`;
}

/** The last map this browser fetched successfully for this scope, or `null` if there is none. */
function readLastKnown(schoolId: string | null): Record<string, boolean> | null {
  try {
    const raw = localStorage.getItem(storageKey(schoolId));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed as Record<string, boolean>;
  } catch {
    return null;
  }
}

function writeLastKnown(schoolId: string | null, flags: Record<string, boolean>): void {
  try {
    localStorage.setItem(storageKey(schoolId), JSON.stringify(flags));
  } catch {
    // Private browsing / a full quota: the cache just stops helping, silently.
  }
}

/**
 * Which features this school has, from `GET /schools/{id}/flags`.
 *
 * §4's rule is that a flag is checked in three places — the server returns 404 when it is
 * off, the dashboard hides the menu item, the app hides the screen — and this is the
 * dashboard's copy. It is a convenience, never a control: hiding the item keeps someone from
 * walking into a 404, but the endpoint refuses regardless of what the menu shows.
 *
 * **Which school.** A TEACHER or MANAGERIAL account has one. An Admin has none until the
 * header's switcher picks one, and "All schools" genuinely has no flag set to read — there
 * is no union that would be honest, since a feature on in one school and off in another is
 * neither. In that state the platform defaults stand in, so the Admin sees every item that
 * any school could have and the per-school truth lives on the Feature flags matrix (P3.3).
 */
@Injectable({ providedIn: 'root' })
export class FlagService {
  private readonly flagsApi = inject(FeatureFlagsApi);
  private readonly auth = inject(AuthService);
  private readonly band = inject(BandService);
  private readonly transloco = inject(TranslocoService);
  private readonly scope = inject(SchoolScopeStore);

  /** Set only while the band on screen is the one this service put there — never dismiss someone else's. */
  private bandIsOurs = false;

  private readonly resource = rxResource<Record<string, boolean>, string | null | undefined>({
    params: () => (this.auth.signedIn() ? this.auth.effectiveSchoolId() : undefined),
    stream: ({ params: schoolId }) => this.mapFor(schoolId),
    // Seeded from whatever this scope last fetched successfully, so a cold start with a slow
    // or briefly unreachable network shows the last real answer rather than a flash of "every
    // feature off" while the first request is in flight.
    defaultValue: readLastKnown(this.auth.effectiveSchoolId()) ?? {},
  });

  readonly loading = this.resource.isLoading;
  readonly flags = computed(() => this.resource.value());
  /**
   * True once the map has settled — resolved, failed, or never asked for because nobody is
   * signed in. `featureGuard` waits on this instead of on `loading`, which is still false in
   * the tick between the parameters becoming known and the request starting: a guard that read
   * `isOn` in that gap would bounce a person off their own bookmark on every cold start.
   */
  readonly ready = computed(() => {
    const status = this.resource.status();
    return status === 'resolved' || status === 'error' || status === 'local' || !this.auth.signedIn();
  });

  constructor() {
    /*
     * D13: `multiSchool` decides whether there is a switcher at all, so the school scope follows
     * it rather than the other way round — a selection kept in this browser cannot outlive the
     * flag being turned off, and with it off no request carries an `X-School-Id`.
     *
     * Gated on `ready`, not just on `isOn`: until the map settles every flag reads as off, and
     * acting on that would drop a real Admin's selection on every cold start of a deployment
     * that does have several schools.
     */
    effect(() => {
      if (!this.auth.signedIn() || !this.ready()) return;
      this.scope.setMultiSchool(this.isOn(FLAGS.multiSchool));
    });
  }

  /** Default false: a feature nobody has turned on is off, including while the map loads. */
  isOn(key: string): boolean {
    return this.resource.value()[key] === true;
  }

  reload(): void {
    this.resource.reload();
  }

  /**
   * The flag map for one school, or the platform defaults when the scope is "All schools".
   *
   * `GET /schools/{id}/flags` answers the flat map itself (`{"lessons.pdf": true, …}`), not a
   * `SchoolFlags` envelope with a `.flags` property — that shape is `FlagMatrix.schools[]`'s,
   * a different response entirely. Reading `.flags` off the flat map used to be `undefined`,
   * silently swallowed into an empty map, so every school-scoped session read every flag as
   * off with no error to show for it.
   *
   * A failed read must not go to an empty map either — that is the same failure, just from a
   * different cause. It falls back to the last map this browser fetched for this scope, or
   * `DEFAULT_FLAGS` when there is none, and says so with a quiet band rather than settling on
   * stale or seeded data without a word.
   */
  private mapFor(schoolId: string | null): Observable<Record<string, boolean>> {
    const source: Observable<Record<string, boolean>> =
      schoolId === null ? this.platformDefaults() : this.flagsApi.schoolFlags(schoolId);
    return source.pipe(
      tap((flags) => {
        writeLastKnown(schoolId, flags);
        if (this.bandIsOurs) {
          this.band.dismiss();
          this.bandIsOurs = false;
        }
      }),
      catchError(() => {
        this.band.show({
          message: this.transloco.translate('band.flagsRefreshFailed'),
          variant: 'notice',
        });
        this.bandIsOurs = true;
        return of(readLastKnown(schoolId) ?? DEFAULT_FLAGS);
      }),
    );
  }

  /** "All schools": the platform's own defaults, shaped like a school's flat flag map. */
  private platformDefaults(): Observable<Record<string, boolean>> {
    return this.flagsApi.matrix().pipe(
      map((matrix) => {
        const flags: Record<string, boolean> = {};
        for (const definition of matrix.definitions ?? [])
          if (definition.key) flags[definition.key] = definition.defaultOn === true;
        return flags;
      }),
    );
  }
}
