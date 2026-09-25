import { Injectable, computed, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { type AdminLesson, type JobRef, JobRefStatusEnum, apiErrorOf } from '../../api';
import { LessonApiService } from './lesson-api.service';
import { type LessonSource, type NewLessonRequest } from './lessons.models';

/** The three calls the upload flow makes, in the order it makes them. */
export type CreationStep = 'creating' | 'uploading' | 'analyzing';

/**
 * E3: create → upload → analyze, owned by a service rather than by the New lesson page.
 *
 * The chain used to live in `new-lesson.page.ts`, in three nested `subscribe`s. That put a
 * screen in charge of work that outlives it: the teacher who left the page mid-upload was
 * navigated back to the lesson minutes later from a component she had closed, or — on the
 * failure path — had her draft rolled back behind a page that was no longer listening.
 *
 * Here the chain belongs to the app. The page reads {@link step} to draw the progress card and
 * {@link done} to decide whether *it* navigates; {@link workInBackground} says she has stopped
 * watching, and from then on nothing navigates on her behalf. The work carries on either way,
 * and the bell (E2) is what tells her the questions are ready.
 *
 * The one real bug this closes is the blind analyze: `POST …/files` answers a `JobRef`, and a
 * `status` of `error` there means the convert never produced Markdown. The old code went
 * straight on to `POST …/analyze`, which then failed on an empty source. {@link uploadFailed}
 * stops at the upload instead, keeps the draft, and offers Retry or Delete.
 */
@Injectable({ providedIn: 'root' })
export class LessonCreationService {
  private readonly api = inject(LessonApiService);
  private readonly transloco = inject(TranslocoService);

  private files: readonly File[] = [];

  readonly step = signal<CreationStep | null>(null);
  readonly busy = computed(() => this.step() !== null);
  readonly error = signal<string | null>(null);
  readonly lessonId = signal<string | null>(null);
  /** The chain finished; the page navigates to {@link lessonId} unless she went elsewhere. */
  readonly done = signal(false);
  /** The upload job came back `error`: the draft is still there, to retry or to delete. */
  readonly uploadFailed = signal(false);
  /** She pressed "Work in background": the work continues, the navigation does not happen. */
  readonly background = signal(false);
  /** `lessons.new.createdManual` or `lessons.new.created` — the notice the lesson opens with. */
  readonly noticeKey = signal<string | null>(null);

  /** Start over: the page calls this once it has acted on {@link done} or on a failure. */
  reset(): void {
    this.step.set(null);
    this.error.set(null);
    this.lessonId.set(null);
    this.done.set(false);
    this.uploadFailed.set(false);
    this.background.set(false);
    this.noticeKey.set(null);
    this.files = [];
  }

  workInBackground(): void {
    this.background.set(true);
  }

  start(request: NewLessonRequest, source: LessonSource, files: readonly File[]): void {
    if (this.busy()) return;
    this.reset();
    this.files = files;
    this.step.set('creating');
    this.api.create(request).subscribe({
      next: (lesson) => this.afterCreate(lesson, source),
      error: (cause: unknown) => this.fail(cause),
    });
  }

  /** "Retry" on a failed upload: the draft is kept, its files are sent again. */
  retryUpload(): void {
    const id = this.lessonId();
    if (!id || this.busy() || this.files.length === 0) return;
    this.error.set(null);
    this.uploadFailed.set(false);
    this.upload(id);
  }

  /** "Delete": the draft the create step made goes, and the form is free again. */
  deleteDraft(): void {
    const id = this.lessonId();
    if (!id || this.busy()) return;
    this.api.deleteLesson(id).subscribe({
      next: () => this.reset(),
      error: () => this.reset(),
    });
  }

  private afterCreate(lesson: AdminLesson, source: LessonSource): void {
    this.lessonId.set(lesson.id);
    if (source === 'manual') {
      this.step.set(null);
      this.noticeKey.set('lessons.new.createdManual');
      this.done.set(true);
      return;
    }
    this.upload(lesson.id);
  }

  private upload(lessonId: string): void {
    this.step.set('uploading');
    this.api.uploadFiles(lessonId, this.files).subscribe({
      next: (job) => this.afterUpload(lessonId, job),
      error: (cause: unknown) => this.rollback(lessonId, cause),
    });
  }

  /**
   * The convert job's own verdict, read before anything is built on it. `error` leaves the
   * lesson exactly where it is — the teacher can send the file again or drop the draft — and
   * never starts an analyze that has nothing to read.
   */
  private afterUpload(lessonId: string, job: JobRef): void {
    if (job.status === JobRefStatusEnum.ERROR) {
      this.step.set(null);
      this.uploadFailed.set(true);
      this.error.set(this.t('lessons.new.uploadFailed'));
      return;
    }
    this.step.set('analyzing');
    this.api.analyze(lessonId).subscribe({
      next: () => {
        this.step.set(null);
        this.noticeKey.set('lessons.new.created');
        this.done.set(true);
      },
      error: (cause: unknown) => this.rollback(lessonId, cause),
    });
  }

  /** No orphan lesson: an upload or analyze failure removes the draft the create step made. */
  private rollback(lessonId: string, cause: unknown): void {
    const message = apiErrorOf(cause)?.message ?? this.t('band.unreachable');
    this.api.deleteLesson(lessonId).subscribe({
      next: () => this.failWith(this.t('lessons.new.rollback', { message })),
      error: () => this.failWith(this.t('lessons.new.rollbackFailed', { message })),
    });
  }

  private fail(cause: unknown): void {
    this.failWith(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
  }

  private failWith(message: string): void {
    this.step.set(null);
    this.lessonId.set(null);
    this.error.set(message);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
