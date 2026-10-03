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
            <hq-card [eyebrow]="gradeLabel(row)">
              @if (row.pdf) {
                <!-- D2, list 3: a PDF is opened, not drawn — its name and Open, in the picture's
                     place, with the same line under it. -->
                <div class="pw__doc" [class.is-named]="row.id === open()">
                  <hq-plan-pdf
                    [attachmentId]="row.attachmentId"
                    [name]="row.attachmentName"
                    [label]="altOf(row)"
                  />
                  <span class="pw__meta">
                    @if (row.readBy !== null) {
                      <span>{{ 'plans.readBy' | transloco: { count: row.readBy } }}</span>
                    }
                    <span class="hq-muted">{{ authorOf(row) }}</span>
                  </span>
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
                  <span class="pw__meta">
                    @if (row.readBy !== null) {
                      <span>{{ 'plans.readBy' | transloco: { count: row.readBy } }}</span>
                    }
                    <span class="hq-muted">{{ authorOf(row) }}</span>
                  </span>
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
      margin-block-end: var(--hq-space-4);
    }

    .pw__heading {
      margin: 0 0 var(--hq-space-2);
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
    }

    .pw__grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: var(--hq-space-3);
      margin-top: 15px;
    }

    .pw__open {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-2);
      width: 100%;
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
      gap: var(--hq-space-8);
    }

    .pw__doc.is-named {
      outline: var(--hq-size-rule) solid var(--hq-color-accent);
      outline-offset: var(--hq-space-8);
    }

    .pw__open.is-named .pw__thumb {
      outline: var(--hq-rule) solid var(--hq-accent);
      outline-offset: var(--hq-space-1);
    }

    .pw__box {
      position: relative;
      display: block;
      width: 100%;
    }

    .pw__thumb {
      display: block;
      width: 100%;
      aspect-ratio: 4 / 3;
      object-fit: cover;
    }

    .pw__placeholder {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-1);
      border: var(--hq-rule) solid var(--hq-ink);
      font-size: var(--hq-font-meta-size);
      text-align: center;
    }

    .pw__placeholder-grade {
      font-weight: var(--hq-font-label-weight);
    }

    .pw__meta {
      display: flex;
      flex-direction: column;
      font-size: var(--hq-font-meta-size);
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
