import { Injectable, inject } from '@angular/core';
import { type Observable } from 'rxjs';
import {
  type PracticePlayView,
  type SmartHintsView,
  type StopHintsView,
  SmartHintsApi,
} from '../../api';
import { silentErrors } from '../../core/http/error.interceptor';
import { approveHintsBody, saveStopHintsBody } from './lessons.models';

/**
 * Feature 30's five routes, all on `/teacher/lessons/{id}/**` and all behind `smartHints`.
 *
 * Unlike {@link LessonApiService}, there is nothing to branch on here: `/admin/**` has no alias
 * for any of them, and it needs none — `permissions.json` grants an ADMIN `lesson.read` and
 * `lesson.write`, and `TeacherScope` narrows a TEACHER and nobody else, so the teacher's route
 * answers for both. A COORDINATOR or a MANAGERIAL account holds no `lesson.write` and reaches
 * the section not at all: `*hqCan` keeps every control off her read-only page, and the one read
 * the section does make is gated by the same `lesson.read` the page itself needs.
 *
 * Every write is `silentErrors()`. The refusals belong next to what she typed: the 400 for a
 * hint that is too long, or that gives the answer away, is a sentence under that stop's two
 * fields, and the red band at the top of the lesson page would say it a second time with no way
 * of telling her which of six stops it was about.
 */
@Injectable({ providedIn: 'root' })
export class SmartHintsService {
  private readonly api = inject(SmartHintsApi);

  /** Every stop's hints and every practice set, with their approval state. */
  read(lessonId: string): Observable<SmartHintsView> {
    return this.api.smartHints(lessonId);
  }

  /** Her own two hints for one stop. The server clears the approval, so the answer is read back. */
  save(lessonId: string, stopId: string, hints: readonly string[]): Observable<StopHintsView> {
    return this.api.saveStopHints(lessonId, stopId, saveStopHintsBody(hints), 'body', false, {
      context: silentErrors(),
    });
  }

  /** The named stops, or — with `stopIds` left out — every stop that has hints. */
  approveHints(lessonId: string, stopIds?: readonly string[]): Observable<SmartHintsView> {
    return this.api.approveHints(lessonId, approveHintsBody(stopIds), 'body', false, {
      context: silentErrors(),
    });
  }

  approvePractice(lessonId: string, playId: string): Observable<PracticePlayView> {
    return this.api.approvePractice(lessonId, playId, 'body', false, { context: silentErrors() });
  }

  /** 204, and the play is gone — the one destructive action in the section. */
  rejectPractice(lessonId: string, playId: string): Observable<unknown> {
    return this.api.rejectPractice(lessonId, playId, 'body', false, { context: silentErrors() });
  }
}
