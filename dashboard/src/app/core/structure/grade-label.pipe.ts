import { Pipe, type PipeTransform, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../i18n/active-lang';
import { gradeLabelOf } from './academy-naming';
import { AcademyNamingService } from './academy-naming.service';

/** The key every screen used before feature 36, and the one a grade with no naming still reads. */
export const GRADE_FALLBACK_KEY = 'admin.classes.gradeOption';

/**
 * `{{ row.grade | hqGradeLabel: row.curriculum }}` — **what this academy calls that grade**.
 *
 * "Year 2" in a British academy, "Grade 1" in an American one, from the naming the Admin set on
 * the School structure screen. With no naming known (every role but the Admin, the switch off, the
 * request still in the air) it is `Grade {{grade}}` in the active language: the exact string the
 * screen showed before, which is why this pipe could be dropped onto screens the feature does not
 * own without changing a single one of them.
 *
 * Pure, and it reads a signal: the naming arrives after the first render, and a signal read inside
 * a template expression marks the view for check when it lands. The `load()` in the constructor is
 * what starts that one request, guarded inside the service — a pipe is created per binding, so it
 * must be cheap to construct, and "ask once per session" lives on the service for that reason.
 */
@Pipe({ name: 'hqGradeLabel' })
export class GradeLabelPipe implements PipeTransform {
  private readonly namings = inject(AcademyNamingService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  constructor() {
    this.namings.load();
  }

  /**
   * @param grade the stored grade number, which never moves.
   * @param academyKey the class's academy (`curriculum`), or nothing to force the fallback.
   * @param fallbackKey the translation key for an academy with no naming of its own.
   */
  transform(
    grade: number | null | undefined,
    academyKey?: string | null,
    fallbackKey: string = GRADE_FALLBACK_KEY,
  ): string {
    const lang = this.lang();
    if (grade === null || grade === undefined) return '';
    return (
      gradeLabelOf(grade, this.namings.naming(academyKey), lang) ??
      this.transloco.translate<string>(fallbackKey, { grade })
    );
  }
}
