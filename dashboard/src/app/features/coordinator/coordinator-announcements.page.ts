import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { type Announcement, type CreateAnnouncementRequest, CoordinatorAnnouncementsApi } from '../../api';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TextareaComponent,
  ToastComponent,
} from '../../ui';
import { CoordinatorService } from './coordinator.service';

/**
 * The body `POST /coordinator/announcements` actually takes.
 *
 * **A contract defect, not a hand-written call.** `CoordinatorDto.CreateAnnouncementRequest`
 * (`classIds`, plural — one row per class, or every class in her scope when it is absent) and
 * `TeacherDto.CreateAnnouncementRequest` (`classId`, one) share a schema *name*, so springdoc
 * exported only the teacher's shape and `server/openapi.json` has the coordinator's route
 * pointing at it. The generated client therefore cannot express the field the server reads.
 * The request still goes through the generated `CoordinatorAnnouncementsApi` — the URL, the
 * interceptors and the auth header are all its own; only the body type is asserted here, and
 * this interface goes the moment the server names the schema (e.g. `@Schema(name = …)`) and
 * `pnpm gen:api` answers with `classIds`.
 */
interface CoordinatorAnnouncementBody {
  readonly bodyEn: string;
  readonly bodyAr?: string;
  readonly classIds?: readonly string[];
  readonly expiresAt?: number;
}

/**
 * Announcements (R7, DR4): the notes she posts to the parents of the classes she coordinates.
 *
 * One screen, one primary action, and the compose sheet is the only place in `/coordinator/**`
 * that writes anything a parent reads. `classIds` left empty means *every* class in her scope,
 * which is the common case and the reason the class list is a set of checkboxes with "all of
 * them" as the default rather than a required picker she has to satisfy first.
 *
 * Parents have no bell (R4): `GET /children/{id}/announcements` in the app is the delivery, so
 * there is no notification to promise here and this screen does not pretend there is one.
 */
@Component({
  selector: 'hq-coordinator-announcements-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    CheckboxComponent,
    DatePipe,
    DialogComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TextareaComponent,
    ToastComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page
      [title]="'nav.announcements' | transloco"
      [subtitle]="'coordinator.announcements.subtitle' | transloco"
    >
      @if (!enabled()) {
        <!-- featureGuard closes the route from the rail; this is the answer to a bookmark. The
             server says 404 for the same reason, so "not yet" rather than "failed". -->
        <hq-empty-state
          [message]="'coordinator.announcements.notEnabled' | transloco"
          [detail]="'coordinator.announcements.notEnabledHint' | transloco"
        />
      }

      <div *hqFeature="'announcements'">
        @if (failed()) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            (dismissed)="failed.set(false)"
          >
            {{ 'coordinator.announcements.postFailed' | transloco }}
          </hq-band>
        }

        @if (list.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="4" [label]="'ui.loading' | transloco" />
        } @else if (list.value().length === 0) {
          <!-- No action on the empty state: the screen's one primary action is the sticky
               footer button, and offering it twice is two places for it to disagree. -->
          <hq-empty-state
            [message]="'coordinator.announcements.empty' | transloco"
            [detail]="'coordinator.announcements.emptyHint' | transloco"
          />
        } @else {
          @for (note of list.value(); track note.id) {
            <hq-card [eyebrow]="noteEyebrow(note)">
              <p>{{ note.bodyEn }}</p>
              @if (note.bodyAr) {
                <p dir="rtl">{{ note.bodyAr }}</p>
              }
              @if (note.expiresAt; as until) {
                <p class="hq-muted">
                  {{ 'coordinator.announcements.until' | transloco: { date: (until | date: 'mediumDate') } }}
                </p>
              }
            </hq-card>
          }
        }

        <div page-footer>
          <hq-button (pressed)="composing.set(true)">
            {{ 'coordinator.announcements.compose' | transloco }}
          </hq-button>
        </div>

        <hq-dialog
          [sheet]="true"
          [(open)]="composing"
          [title]="'coordinator.announcements.compose' | transloco"
          [confirmLabel]="'coordinator.announcements.post' | transloco"
          [cancelLabel]="'ui.cancel' | transloco"
          [confirmDisabled]="!canPost()"
          [loading]="posting()"
          (confirmed)="post()"
        >
          <hq-textarea
            [label]="'coordinator.announcements.bodyEn' | transloco"
            [required]="true"
            [rows]="4"
            [maxLength]="1000"
            [error]="bodyError()"
            [(value)]="bodyEn"
          />
          <hq-textarea
            dir="rtl"
            [label]="'coordinator.announcements.bodyAr' | transloco"
            [rows]="4"
            [maxLength]="1000"
            [hint]="'coordinator.announcements.bodyArHint' | transloco"
            [(value)]="bodyAr"
          />

          <fieldset class="co-classes">
            <legend>{{ 'coordinator.announcements.classes' | transloco }}</legend>
            <p class="hq-muted">{{ 'coordinator.announcements.classesHint' | transloco }}</p>
            @for (row of co.classes(); track row.classId) {
              <hq-checkbox
                [label]="row.className"
                [checked]="selected().includes(row.classId)"
                (checkedChange)="toggle(row.classId, $event)"
              />
            }
          </fieldset>

          <hq-input
            type="date"
            [label]="'coordinator.announcements.expires' | transloco"
            [hint]="'coordinator.announcements.expiresHint' | transloco"
            [(value)]="expires"
          />
        </hq-dialog>

        <hq-toast
          tone="success"
          [open]="posted()"
          [message]="'coordinator.announcements.posted' | transloco"
          (expired)="posted.set(false)"
        />
      </div>
    </hq-page>
  `,
  styles: `
    .co-classes {
      margin: 0;
      padding: 0;
      border: 0;
    }

    .co-classes legend {
      padding: 0;
      font-weight: var(--hq-font-weight-bold);
    }
  `,
})
export class CoordinatorAnnouncementsPage {
  private readonly api = inject(CoordinatorAnnouncementsApi);
  private readonly flags = inject(FlagService);
  private readonly transloco = inject(TranslocoService);
  protected readonly co = inject(CoordinatorService);

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.announcements));

  protected readonly composing = signal(false);
  protected readonly posting = signal(false);
  protected readonly posted = signal(false);
  protected readonly failed = signal(false);

  protected readonly bodyEn = signal('');
  protected readonly bodyAr = signal('');
  protected readonly expires = signal('');
  protected readonly selected = signal<readonly string[]>([]);

  protected readonly list = rxResource<Announcement[], boolean>({
    params: () => this.enabled(),
    stream: ({ params }) => (params ? this.api.coordinatorAnnouncements() : of([])),
    defaultValue: [],
  });

  /** The one rule the server also enforces: 1–1000 characters of English body (`@NotBlank`). */
  protected readonly canPost = computed(() => {
    const body = this.bodyEn().trim();
    return body.length > 0 && body.length <= 1000 && this.bodyAr().trim().length <= 1000;
  });

  protected readonly bodyError = computed(() =>
    this.bodyEn().length > 1000
      ? this.transloco.translate<string>('coordinator.announcements.tooLong')
      : null,
  );

  protected toggle(classId: string, on: boolean): void {
    this.selected.update((ids) => (on ? [...ids, classId] : ids.filter((id) => id !== classId)));
  }

  protected noteEyebrow(note: Announcement): string {
    const created = note.createdAt ?? note.publishedAt ?? null;
    const when = created === null ? '' : new Date(created).toLocaleDateString();
    return [note.subject, when].filter((part) => part !== '' && part !== undefined).join(' · ');
  }

  protected post(): void {
    if (!this.canPost()) return;
    const chosen = this.selected();
    const expiresAt = this.expires() === '' ? undefined : Date.parse(`${this.expires()}T23:59:59Z`);
    const body: CoordinatorAnnouncementBody = {
      bodyEn: this.bodyEn().trim(),
      // Absent rather than empty: the server reads "" as a body it must store, and an empty
      // `classIds` as "no class at all" instead of "every class of mine".
      ...(this.bodyAr().trim() === '' ? {} : { bodyAr: this.bodyAr().trim() }),
      ...(chosen.length === 0 ? {} : { classIds: [...chosen] }),
      ...(expiresAt === undefined || Number.isNaN(expiresAt) ? {} : { expiresAt }),
    };

    this.posting.set(true);
    this.failed.set(false);
    this.api
      .createCoordinatorAnnouncement(body as unknown as CreateAnnouncementRequest)
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.composing.set(false);
          this.posted.set(true);
          this.bodyEn.set('');
          this.bodyAr.set('');
          this.expires.set('');
          this.selected.set([]);
          this.list.reload();
        }),
        catchError(() => {
          this.posting.set(false);
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }
}
