/* hq-flag: documentReader — the whole screen. `screens.ts` puts the key on the Admin's row and on
   the manager's; the `*hqFeature` block and `notEnabled` below are the answer to a bookmark, and
   the server's `@FeatureFlag(FlagKeys.DOCUMENT_READER)` answers 404 to the routes either way. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import {
  type CreateBroadcastRequest,
  type DocumentSummary,
  BroadcastsApi,
  DocumentReaderApi,
  ManagementApi,
  apiErrorCodeOf,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import {
  type BroadcastDraft,
  type ComposeContext,
  EMPTY_DRAFT,
} from '../../core/broadcasts/broadcast.rules';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import {
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  ProgressBarComponent,
  SkeletonComponent,
} from '../../ui';
import { BroadcastComposeComponent, type ComposeSection } from '../broadcasts/compose-sheet.component';
import {
  DOCUMENT_ACCEPT,
  MAX_DOCUMENT_PAGES,
  documentFileError,
  keyDateLabel,
} from './document-file';

/** Which half of the summary is on screen. `null` is "whichever language the dashboard is in". */
type Reading = 'en' | 'ar' | null;

/**
 * **Document reader** (feature 26): a circular in, what it says out — and a decision she makes.
 *
 * A ministry circular arrives as a PDF, a Word file or a photograph of a paper letter. She uploads
 * it and reads back a title, a summary in both languages, the dates it names and what the school
 * has to do. Four things about this screen are deliberate.
 *
 * **The file is never stored, and the screen says so.** `DocumentReaderService` reads it from a
 * temporary file it deletes in a `finally` and writes nothing to the bucket, so there is no
 * original to re-open, no thumbnail and nothing to download — and the line under the drop zone is
 * not reassurance, it is the reason the list below holds summaries and not files.
 *
 * **Reading starts when she picks the file.** No second button between "choose a document" and the
 * answer: she has chosen the circular, which is the whole of the request. The two rules the server
 * would answer 413 and 400 to are asked here first (`document-file.ts`), so the obvious mistake —
 * a 30 MB scan — costs her nothing but the picker.
 *
 * **The bar is indeterminate on purpose.** `withFetch` reports no upload progress, so a percentage
 * would be a number this screen invented; "Reading your document…" is what is true.
 *
 * **"Draft an announcement" sends nothing.** It opens `hq-broadcast-compose` — the typed composer,
 * with the suggestion as its `initial` draft, every field hers — and the announcement goes out on
 * its own Send, which is `POST /management/broadcasts`, unchanged. Exactly feature 22's shape.
 */
@Component({
  selector: 'hq-document-reader-page',
  imports: [
    BroadcastComposeComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    EmptyStateComponent,
    FeatureDirective,
    PageComponent,
    ProgressBarComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.documents' | transloco" [subtitle]="'documents.subtitle' | transloco">
      @if (!enabled()) {
        <hq-empty-state
          [message]="'documents.notEnabled' | transloco"
          [detail]="'documents.notEnabledHint' | transloco"
        />
      }

      <div *hqFeature="'documentReader'" class="dr">
        <!-- A drop target that is also the label of the input inside it: dragging the circular in
             and pressing Enter on the keyboard are one control, not two. -->
        <label
          class="dr__drop"
          [class.is-over]="dragging()"
          [class.is-busy]="reading()"
          (dragover)="onDragOver($event)"
          (dragleave)="dragging.set(false)"
          (drop)="onDrop($event)"
        >
          <span class="dr__label">{{ 'documents.pick' | transloco }}</span>
          <span class="hq-muted">{{ 'documents.pickHint' | transloco: { pages: maxPages } }}</span>
          <input
            class="dr__file"
            type="file"
            [accept]="accept"
            [disabled]="reading()"
            [attr.aria-label]="'documents.pick' | transloco"
            (change)="onPick($event)"
          />
        </label>
        <p class="hq-muted dr__privacy">{{ 'documents.privacy' | transloco }}</p>

        @if (reading()) {
          <hq-progress-bar [plain]="true" [value]="null" [label]="'documents.reading' | transloco" />
          <p class="dr__reading" aria-live="polite">{{ 'documents.reading' | transloco }}</p>
        }

        @if (summary(); as read) {
          <hq-card [eyebrow]="'documents.summary' | transloco" [title]="read.title ?? ''">
            <p class="hq-muted dr__file-line" dir="auto">
              {{ read.fileName }} ·
              {{ 'documents.pageCount' | transloco: { count: read.pages ?? 1 } }}
            </p>

            <p class="dr__summary" [dir]="dir()">{{ body() }}</p>
            <hq-button variant="quiet" (pressed)="flip()">{{ otherLanguage() | transloco }}</hq-button>

            <h3 class="dr__heading">{{ 'documents.keyDates' | transloco }}</h3>
            @if (dates().length === 0) {
              <p class="hq-muted">{{ 'documents.noKeyDates' | transloco }}</p>
            } @else {
              <ul class="dr__list">
                @for (row of dates(); track row.date + row.label) {
                  <li class="dr__date">
                    <span class="dr__day">{{ row.day }}</span>
                    <span [dir]="dir()">{{ row.label }}</span>
                  </li>
                }
              </ul>
            }

            <h3 class="dr__heading">{{ 'documents.actions' | transloco }}</h3>
            @if (actions().length === 0) {
              <p class="hq-muted">{{ 'documents.noActions' | transloco }}</p>
            } @else {
              <ul class="dr__list">
                @for (action of actions(); track action) {
                  <li [dir]="dir()">{{ action }}</li>
                }
              </ul>
            }

            @if (read.announcement) {
              <!-- The one primary action this screen has once there is something to read. Behind
                   the announcements switch as well as her write key: a school without the feed
                   has nothing for the sheet to post into. -->
              <div *hqFeature="'announcements'" class="dr__draft">
                <hq-button *hqCan="'management.broadcast'" variant="primary" (pressed)="draft()">
                  {{ 'documents.draft' | transloco }}
                </hq-button>
              </div>
            }
          </hq-card>
        }

        <hq-card [title]="'documents.recent' | transloco">
          @if (recent.isLoading()) {
            <hq-skeleton [label]="'ui.loading' | transloco" [lines]="3" />
          } @else if (recent.error()) {
            <!-- The red band is already up (the error interceptor), so this says what the list
                 cannot show and offers the one thing that helps. -->
            <hq-empty-state
              [message]="'documents.errors.recent' | transloco"
              [actionLabel]="'ui.retry' | transloco"
              (action)="recent.reload()"
            />
          } @else if (recent.value().length === 0) {
            <hq-empty-state
              [message]="'documents.noRecent' | transloco"
              [detail]="'documents.noRecentHint' | transloco"
            />
          } @else {
            <ul class="dr__recent">
              @for (row of recent.value(); track row.id) {
                <li class="dr__row">
                  <span class="dr__row-text">
                    <span dir="auto">{{ row.title || row.fileName }}</span>
                    <span class="hq-muted" dir="auto">{{ row.fileName }}</span>
                  </span>
                  <!-- Twenty buttons all called "Open" is twenty buttons a screen reader cannot
                       tell apart, so each says which circular it opens. -->
                  <hq-button
                    variant="quiet"
                    [ariaLabel]="
                      ('documents.open' | transloco) + ': ' + (row.title || row.fileName || '')
                    "
                    (pressed)="reopen(row)"
                  >
                    {{ 'documents.open' | transloco }}
                  </hq-button>
                </li>
              }
            </ul>
          }
        </hq-card>

        @if (suggestion(); as prefilled) {
          <hq-broadcast-compose
            [(open)]="drafting"
            [ctx]="ctx()"
            [sections]="sections()"
            [initial]="prefilled"
            [posting]="posting()"
            [title]="'documents.review'"
            (submitted)="send($event)"
          />
        }
      </div>
    </hq-page>
  `,
  styles: `
    .dr {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .dr__drop {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-8);
      min-block-size: var(--hq-size-drop-zone-height);
      padding: var(--hq-space-24);
      border: var(--hq-size-rule) dashed var(--hq-color-rule);
      background: var(--hq-color-surface);
      text-align: center;
      cursor: pointer;
    }

    .dr__drop.is-over {
      border-color: var(--hq-color-accent);
      background: var(--hq-color-accent-soft);
    }

    .dr__drop.is-busy {
      cursor: progress;
      opacity: 0.6;
    }

    .dr__label {
      font-weight: var(--hq-font-label-weight);
    }

    .dr__file {
      position: absolute;
      inset: 0;
      inline-size: 100%;
      block-size: 100%;
      opacity: 0;
      cursor: inherit;
    }

    .dr__privacy,
    .dr__file-line,
    .dr__reading {
      margin: 0;
    }

    .dr__reading {
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-accent-strong);
    }

    .dr__summary {
      margin-block: 0 var(--hq-space-12);
      white-space: pre-wrap;
    }

    .dr__heading {
      margin-block: var(--hq-space-24) var(--hq-space-8);
      font-size: var(--hq-font-label-size);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
    }

    .dr__list {
      margin: 0;
      padding-inline-start: var(--hq-space-24);
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
    }

    .dr__date {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-8);
    }

    .dr__day {
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-accent-ink);
      white-space: nowrap;
    }

    .dr__draft {
      margin-block-start: var(--hq-space-24);
    }

    .dr__recent {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .dr__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-row-height);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .dr__row:last-child {
      border-block-end: 0;
    }

    .dr__row-text {
      display: flex;
      flex-direction: column;
    }
  `,
})
export class DocumentReaderPage {
  private readonly api = inject(DocumentReaderApi);
  private readonly broadcasts = inject(BroadcastsApi);
  private readonly management = inject(ManagementApi);
  private readonly flags = inject(FlagService);
  private readonly band = inject(BandService);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly accept = DOCUMENT_ACCEPT;
  protected readonly maxPages = MAX_DOCUMENT_PAGES;

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.documentReader));

  protected readonly dragging = signal(false);
  protected readonly reading = signal(false);
  protected readonly summary = signal<DocumentSummary | null>(null);
  /** Her own choice of language for the summary; `null` follows the dashboard's. */
  private readonly chosen = signal<Reading>(null);
  protected readonly drafting = signal(false);
  protected readonly posting = signal(false);

  /**
   * Her last twenty, newest first (`GET /management/documents`).
   *
   * The rows are **whole summaries** — the server's list route answers the same `DocumentDto.View`
   * the read does — so "Open" shows one from the row it is on rather than fetching it again. A
   * spinner for bytes already in the browser is a spinner for nothing.
   */
  protected readonly recent = rxResource({
    params: () => (this.enabled() ? true : undefined),
    stream: () => this.api.myDocumentSummaries(),
    defaultValue: [] as DocumentSummary[],
  });

  /** Which language the summary is read in: hers if she picked one, otherwise the dashboard's. */
  protected readonly shown = computed<'en' | 'ar'>(
    () => this.chosen() ?? (this.lang() === 'ar' ? 'ar' : 'en'),
  );

  protected readonly dir = computed(() => (this.shown() === 'ar' ? 'rtl' : 'ltr'));

  protected readonly otherLanguage = computed(() =>
    this.shown() === 'ar' ? 'documents.readEnglish' : 'documents.readArabic',
  );

  protected readonly body = computed(() => {
    const read = this.summary();
    if (read === null) return '';
    return (this.shown() === 'ar' ? read.summaryAr : read.summaryEn) ?? '';
  });

  /** Each key date as the school says the day, beside the label in the language on screen. */
  protected readonly dates = computed(() => {
    const zone = this.platform.timezone();
    const arabic = this.shown() === 'ar';
    return (this.summary()?.keyDates ?? []).map((row) => ({
      date: row.date ?? '',
      day: keyDateLabel(row.date ?? '', this.shown(), zone),
      label: (arabic ? row.labelAr : row.labelEn) ?? '',
    }));
  });

  protected readonly actions = computed(() => {
    const read = this.summary();
    if (read === null) return [];
    return (this.shown() === 'ar' ? read.actionsAr : read.actionsEn) ?? [];
  });

  /**
   * The suggested announcement as a draft of the **typed** composer — title and both bodies, and
   * nothing else. The server suggests no audience for a circular (`DocumentAnnouncementDraft` has
   * no such field), so who it goes to is hers to say on the sheet, where the rules that refuse a
   * bad one are the rules the server enforces.
   */
  protected readonly suggestion = computed<BroadcastDraft | null>(() => {
    const draft = this.summary()?.announcement;
    if (draft === undefined) return null;
    return {
      ...EMPTY_DRAFT,
      kind: 'announcement',
      title: draft.title ?? '',
      bodyEn: draft.bodyEn ?? '',
      bodyAr: draft.bodyAr ?? '',
    };
  });

  /**
   * The school's classes, read only once there is an announcement to draft.
   *
   * `GET /management/classes` answers the Admin and a department manager alike (it is what
   * `StaffScopeService` reads for a manager and what Announcements reads for the Admin), and the
   * sheet needs them for one thing: the academy and grade she may name. Asking for them before the
   * sheet can open would be a request on every visit to a screen most of whose visits end at a
   * summary.
   */
  private readonly classes = rxResource({
    params: () => (this.enabled() && this.drafting() ? true : undefined),
    stream: () => this.management.managementClasses(),
    defaultValue: [],
  });

  protected readonly sections = computed<readonly ComposeSection[]>(() =>
    this.classes
      .value()
      .flatMap((group) =>
        (group.classes ?? []).map((row) => ({
          classId: row.classId ?? '',
          className: row.className ?? '',
          // A group *is* an academy and a grade, so it answers for a row that left either out.
          curriculum: row.curriculum ?? group.curriculum ?? '',
          grade: row.grade ?? group.grade ?? 0,
        })),
      )
      .filter((row) => row.classId !== '' && row.curriculum !== ''),
  );

  protected readonly ctx = computed<ComposeContext>(() => ({
    role: 'manager',
    departments: [...new Set(this.sections().map((row) => row.curriculum))].sort(),
    sections: this.sections(),
    today: new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
    zone: this.platform.timezone(),
  }));

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.reading()) this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.take(event.dataTransfer?.files?.[0] ?? null);
  }

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    // Cleared so picking the same file twice reads it twice: `change` does not fire otherwise.
    input.value = '';
    this.take(file);
  }

  protected flip(): void {
    this.chosen.set(this.shown() === 'ar' ? 'en' : 'ar');
  }

  /** "Open" on a recent row: the stored summary again, in the language on screen. */
  protected reopen(row: DocumentSummary): void {
    this.summary.set(row);
    this.drafting.set(false);
  }

  /**
   * One file, checked against the two rules the server would answer 413 and 400 to, then read.
   *
   * A refused file is **not** kept: there is nothing to retry and nothing for a second button to
   * do, so the band says which rule it broke and the picker is still there.
   */
  private take(file: File | null): void {
    if (file === null || this.reading()) return;
    const refused = documentFileError(file);
    if (refused !== null) {
      this.band.fail(this.t(`documents.errors.${refused}`));
      return;
    }
    this.band.dismiss();
    this.reading.set(true);
    this.api
      .readDocument(file)
      .pipe(
        tap((read) => {
          this.reading.set(false);
          this.summary.set(read);
          this.chosen.set(null);
          this.recent.reload();
        }),
        catchError((cause: unknown) => {
          this.reading.set(false);
          this.band.fail(this.failure(cause));
          return of(null);
        }),
      )
      .subscribe();
  }

  /** The sheet, on the suggestion. Nothing has been posted and nothing is about to be. */
  protected draft(): void {
    if (this.suggestion() === null) return;
    this.drafting.set(true);
  }

  /**
   * Send: the **existing** route, unchanged (`POST /management/broadcasts`).
   *
   * Not a route of its own, so an announcement drafted from a circular is the same row, the same
   * audience rules and the same notifications as one she typed. The summary carries no id onto it:
   * a circular is what she read, not what the parents are told.
   */
  protected send(body: CreateBroadcastRequest): void {
    this.posting.set(true);
    this.broadcasts
      .createManagementBroadcast(body)
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.drafting.set(false);
        }),
        catchError((cause: unknown) => {
          // The sheet stays open on its own draft: nothing was sent, and everything she reviewed
          // is still there to send again.
          this.posting.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
          return of(null);
        }),
      )
      .subscribe();
  }

  /**
   * The six answers this feature has, each in words she can act on.
   *
   * `too_large` is asked for by status as well as by code: a 10 MB limit is also enforced by the
   * proxy in front of the server, and that 413 arrives as an HTML page with no `{code}` in it.
   */
  private failure(cause: unknown): string {
    const code = apiErrorCodeOf(cause);
    if (code === 'too_large' || statusOf(cause) === 413) return this.t('documents.errors.tooLarge');
    if (code === 'too_long') return this.t('documents.errors.tooLong');
    if (code === 'unreadable_file') return this.t('documents.errors.unreadable');
    if (code === 'rephrase') return this.t('documents.errors.rephrase');
    if (code === 'model_failed') return this.t('documents.errors.model');
    if (code === 'bad_request') return this.t('documents.errors.badType');
    return apiErrorOf(cause)?.message ?? this.t('band.unreachable');
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}

/** The HTTP status of a failure, without reaching for `HttpErrorResponse` in a screen. */
function statusOf(cause: unknown): number | null {
  if (cause === null || typeof cause !== 'object') return null;
  const status: unknown = (cause as Record<string, unknown>)['status'];
  return typeof status === 'number' ? status : null;
}
