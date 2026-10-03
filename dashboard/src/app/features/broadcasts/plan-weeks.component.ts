import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type PlanRow, type PlanWeek } from '../../core/broadcasts/plan-archive';
import { AttachmentImageDirective, CardComponent, DialogComponent } from '../../ui';
import { PlanPdfComponent } from './plan-pdf.component';

/**
 * **The archive, drawn**: weeks newest first, each plan its picture (MG2b, reworked by MH2 item 4).
 *
 * One component for both readers of `WeeklyPlanArchive` — the manager's Weekly plans screen, where
 * `readBy` is answered and the list is exportable, and the read-only tab a teacher and a coordinator
 * get on Announcements. `readBy` is simply absent on theirs (`null`), so there is no flag to pass and
 * no second template to keep in step.
 *
 * MH1 made a plan an image, so a row is a **thumbnail with its grade**, not a title with an
 * expanding body: the expanding row was there because a plan was words, and a picture that has to be
 * unfolded before it can be seen is a picture behind a door. Pressing one opens it full size in a
 * dialog, which is the only way to read a photographed A4 sheet at 200 px wide.
 *
 * Every picture is fetched with the bearer through `AttachmentImageDirective` — `[src]` pointed at
 * `attachment.url` answers 401, which is the whole reason that directive exists — and **only once
 * the row is near the viewport**: twelve weeks of a six-grade department is seventy-odd full-size
 * scans, and arriving on the screen must not ask for all of them. Until the bytes are there the row
 * draws its grade and week in the picture's own box, so the grid is readable and does not reflow
 * when the image lands.
 */
@Component({
  selector: 'hq-plan-weeks',
  imports: [AttachmentImageDirective, CardComponent, DialogComponent, PlanPdfComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (week of weeks(); track week.weekStart) {
      <section class="pw__week">
        <h3 class="pw__heading">{{ weekLabel(week.weekStart) }}</h3>
        <div class="pw__grid">
          @for (row of week.rows; track row.id) {
            <hq-card [title]="gradeLabel(row)">
              @if (row.pdf) {
                <!-- D2, list 3: a PDF is opened, not drawn — its name and Open, in the picture's
                     place, with the same line under it. -->
                <div class="pw__doc" [class.is-named]="row.id === open()">
                  <hq-plan-pdf
                    [attachmentId]="row.attachmentId"
                    [name]="row.attachmentName"
                    [label]="altOf(row)"
                  />
                  <div class="pw__meta">
                    @if (row.readBy !== null) {
                      <div class="pw__meta-stat">
                        <svg class="pw__meta-icon" viewBox="0 0 20 20" fill="currentColor" width="14" height="14" aria-hidden="true">
                          <path d="M10 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" />
                          <path fill-rule="evenodd" d="M.664 10.59a1.651 1.651 0 010-1.186A10.004 10.004 0 0110 3c4.257 0 7.893 2.66 9.336 6.41.147.381.147.804 0 1.186A10.004 10.004 0 0110 17c-4.257 0-7.893-2.66-9.336-6.41zM14 10a4 4 0 11-8 0 4 4 0 018 0z" clip-rule="evenodd" />
                        </svg>
                        <span>{{ 'plans.readBy' | transloco: { count: row.readBy } }}</span>
                      </div>
                    }
                    <div class="pw__meta-author">
                      <svg class="pw__meta-icon" viewBox="0 0 20 20" fill="currentColor" width="13" height="13" aria-hidden="true">
                        <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd" />
                      </svg>
                      <span class="pw__meta-author-text">{{ authorOf(row) }}</span>
                    </div>
                  </div>
                </div>
              } @else {
                <button
                  type="button"
                  class="pw__open"
                  [class.is-named]="row.id === open()"
                  (click)="enlarged.set(row)"
                >
                  <span class="pw__box">
                    <img
                      #thumb="hqAttachmentImage"
                      class="pw__thumb"
                      [hqAttachmentImage]="row.attachmentId"
                      [alt]="altOf(row)"
                    />
                    @if (thumb.state() !== 'ready') {
                      <!-- Not a spinner: the two things she is looking for in a twelve-week list are
                           the grade and the week, and they are known before any byte arrives. -->
                      <span class="pw__placeholder" aria-hidden="true">
                        <span class="pw__placeholder-grade">{{ gradeLabel(row) }}</span>
                        <span>{{ weekLabel(row.weekStart) }}</span>
                      </span>
                    }
                  </span>
                  <div class="pw__meta">
                    @if (row.readBy !== null) {
                      <div class="pw__meta-stat">
                        <svg class="pw__meta-icon" viewBox="0 0 20 20" fill="currentColor" width="14" height="14" aria-hidden="true">
                          <path d="M10 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" />
                          <path fill-rule="evenodd" d="M.664 10.59a1.651 1.651 0 010-1.186A10.004 10.004 0 0110 3c4.257 0 7.893 2.66 9.336 6.41.147.381.147.804 0 1.186A10.004 10.004 0 0110 17c-4.257 0-7.893-2.66-9.336-6.41zM14 10a4 4 0 11-8 0 4 4 0 018 0z" clip-rule="evenodd" />
                        </svg>
                        <span>{{ 'plans.readBy' | transloco: { count: row.readBy } }}</span>
                      </div>
                    }
                    <div class="pw__meta-author">
                      <svg class="pw__meta-icon" viewBox="0 0 20 20" fill="currentColor" width="13" height="13" aria-hidden="true">
                        <path fill-rule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clip-rule="evenodd" />
                      </svg>
                      <span class="pw__meta-author-text">{{ authorOf(row) }}</span>
                    </div>
                  </div>
                </button>
              }
            </hq-card>
          }
        </div>
      </section>
    }

    @if (enlarged(); as row) {
      <!-- guarded: the @if above is what removes this dialog, so Esc, the backdrop and Close all
           have to come back here rather than closing an element the template would draw again. -->
      <hq-dialog [open]="true" [guarded]="true" [title]="altOf(row)" (closeRequested)="enlarged.set(null)">
        <img class="pw__full" [hqAttachmentImage]="row.attachmentId" [alt]="altOf(row)" />
      </hq-dialog>
    }
  `,
  styles: `
    .pw__week {
      margin-block-end: var(--hq-space-32);

      &:last-child {
        margin-block-end: 0;
      }
    }

    .pw__heading {
      margin: 0 0 var(--hq-space-12);
      font-size: var(--hq-text-size-body);
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink);
    }

    .pw__grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: var(--hq-space-20);
      margin-block-start: var(--hq-space-8);
    }

    .pw__grid hq-card {
      display: flex;
      flex-direction: column;
      height: 100%;
      border-radius: var(--hq-radius-card);
      border: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.04), 0 1px 2px -1px rgba(0, 0, 0, 0.03);
      transition: box-shadow 0.2s ease, border-color 0.2s ease;

      &:hover {
        border-color: color-mix(in srgb, var(--hq-color-ink) 25%, var(--hq-color-divider));
        box-shadow: 0 6px 16px -4px rgba(0, 0, 0, 0.08);
      }
    }

    .pw__open {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: start;
      cursor: pointer;
    }

    .pw__doc {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
    }

    .pw__doc.is-named {
      outline: var(--hq-size-rule) solid var(--hq-color-accent);
      outline-offset: var(--hq-space-8);
      border-radius: var(--hq-radius-control);
    }

    .pw__open.is-named .pw__thumb {
      outline: var(--hq-size-rule) solid var(--hq-color-accent);
      outline-offset: var(--hq-space-4);
    }

    .pw__box {
      position: relative;
      display: block;
      width: 100%;
      border-radius: var(--hq-radius-control);
      overflow: hidden;
      background: var(--hq-color-surface-sunken);
      border: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .pw__thumb {
      display: block;
      width: 100%;
      aspect-ratio: 16 / 10;
      object-fit: cover;
      transition: transform 0.2s ease;
    }

    .pw__open:hover .pw__thumb {
      transform: scale(1.02);
    }

    .pw__placeholder {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-4);
      background: var(--hq-color-surface-sunken);
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
      text-align: center;
      padding: var(--hq-space-12);
    }

    .pw__placeholder-grade {
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink);
    }

    .pw__meta {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-6);
      margin-block-start: var(--hq-space-12);
      padding-block-start: var(--hq-space-12);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      font-size: var(--hq-text-theme-xs);
      line-height: 1.4;
    }

    .pw__meta-stat {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-6);
      font-size: var(--hq-text-theme-xs);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink);
    }

    .pw__meta-author {
      display: flex;
      align-items: flex-start;
      gap: var(--hq-space-6);
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
      line-height: 1.35;
    }

    .pw__meta-author-text {
      overflow-wrap: anywhere;
    }

    .pw__meta-icon {
      color: var(--hq-color-ink-soft);
      flex-shrink: 0;
      margin-top: 1px;
    }

    .pw__full {
      max-width: 100%;
      max-height: 70vh;
      object-fit: contain;
    }
  `,
})
export class PlanWeeksComponent {
  private readonly transloco = inject(TranslocoService);

  readonly weeks = input.required<readonly PlanWeek[]>();
  /**
   * The plan a notification named (MH2 item 6): its thumbnail is outlined and it opens full size, so
   * a click on the bell ends at the picture rather than in a list that merely contains it.
   */
  readonly open = input<string>('');

  protected readonly enlarged = signal<PlanRow | null>(null);

  /** The ids this visit has already enlarged on its own: once per plan, never again after a close. */
  private readonly shown = new Set<string>();

  constructor() {
    /*
     * The row a notification named, enlarged as soon as it is in the list.
     *
     * An effect rather than a `computed`, because `enlarged` is also written by a click — and
     * `shown` rather than reading `enlarged()` here, so closing the dialog does not immediately
     * re-open it on the effect's own re-run.
     */
    effect(() => {
      const wanted = this.open();
      const rows = this.weeks().flatMap((week) => week.rows);
      untracked(() => {
        if (wanted === '' || this.shown.has(wanted)) return;
        const row = rows.find((candidate) => candidate.id === wanted);
        if (row === undefined) return;
        this.shown.add(wanted);
        // A PDF has nothing to enlarge: its card is outlined, and Open is hers to press.
        if (!row.pdf) this.enlarged.set(row);
      });
    });
  }

  protected gradeLabel(row: PlanRow): string {
    return row.grade === null
      ? this.transloco.translate<string>('plans.department')
      : this.transloco.translate<string>('broadcasts.gradeN', { grade: row.grade });
  }

  /** A11y: "Weekly plan · Grade 3 · Week of 12 Oct 2026" — what the picture is, in words. */
  protected altOf(row: PlanRow): string {
    return this.transloco.translate<string>('plans.imageAlt', {
      grade: row.grade ?? '',
      week: this.weekLabel(row.weekStart),
    });
  }

  protected authorOf(row: PlanRow): string {
    const plan = row.plan;
    const role = plan.authorRole ? this.transloco.translate<string>(`role.${plan.authorRole}`) : '';
    const track = plan.curriculum ? this.transloco.translate<string>(`curriculum.${plan.curriculum}`) : '';
    return this.transloco.translate<string>('broadcasts.by', {
      who: [plan.authorName, role].filter((part) => part).join(' · '),
      scope: track,
    });
  }

  protected weekLabel(week: string): string {
    return this.transloco.translate<string>('broadcasts.weekOf', {
      date: new Date(`${week}T00:00:00Z`).toLocaleDateString(undefined, {
        timeZone: 'UTC',
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      }),
    });
  }
}
