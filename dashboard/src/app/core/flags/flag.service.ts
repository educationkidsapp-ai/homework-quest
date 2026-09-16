import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { FeatureFlagsApi, SchoolFlags } from '../../api';
import { AuthService } from '../auth/auth.service';

/** The 14 keys of §4, mirroring `quest.server.flags.FlagKeys`. */
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
} as const;

export type FlagKey = (typeof FLAGS)[keyof typeof FLAGS];

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

  private readonly resource = rxResource<Record<string, boolean>, string | null | undefined>({
    params: () => (this.auth.signedIn() ? this.auth.effectiveSchoolId() : undefined),
    stream: ({ params: schoolId }) => this.mapFor(schoolId),
    defaultValue: {},
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

  /** Default false: a feature nobody has turned on is off, including while the map loads. */
  isOn(key: string): boolean {
    return this.resource.value()[key] === true;
  }

  reload(): void {
    this.resource.reload();
  }

  /** The flag map for one school, or the platform defaults when the scope is "All schools". */
  private mapFor(schoolId: string | null): Observable<Record<string, boolean>> {
    const source: Observable<SchoolFlags> =
      schoolId === null ? this.platformDefaults() : this.flagsApi.schoolFlags(schoolId);
    return source.pipe(
      map((flags) => flags.flags ?? {}),
      // A flag read that fails must not blank the dashboard: an empty map hides the flagged
      // items and leaves the shell usable, which is the safe direction.
      catchError(() => of<Record<string, boolean>>({})),
    );
  }

  /**
   * "All schools": the platform's own defaults, shaped like a school's flag map so the rest
   * of the service cannot tell the difference.
   */
  private platformDefaults(): Observable<SchoolFlags> {
    return this.flagsApi.matrix().pipe(
      map((matrix): SchoolFlags => {
        const flags: Record<string, boolean> = {};
        for (const definition of matrix.definitions ?? [])
          if (definition.key) flags[definition.key] = definition.defaultOn === true;
        return { flags };
      }),
      catchError(() => of<SchoolFlags>({ flags: {} })),
    );
  }
}
