import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type PlanRow, type PlanWeek, readerBodies } from '../../core/broadcasts/plan-archive';
import { activeLang } from '../../core/i18n/active-lang';
import { CardComponent } from '../../ui';

/**
 * **The archive, drawn**: weeks newest first, each plan a row that expands (MG2b).
 *
 * One component for both readers of `WeeklyPlanArchive` — the manager's Weekly plans screen, where
 * `readBy` is answered and the list is exportable, and the read-only tab a teacher and a
 * coordinator get on Broadcasts. `readBy` is simply absent on theirs (`null`), so there is no flag
 * to pass and no second template to keep in step.
 *
 * Collapsed by default, including the current week: an archive is a list she is looking *for*
 * something in, and twelve weeks of open bodies is a page she has to scroll past to search.
 */
@Component({
  selector: 'hq-plan-weeks',
  imports: [CardComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (week of weeks(); track week.weekStart) {
      <section class="pw__week">
        <h3 class="pw__heading">{{ weekLabel(week.weekStart) }}</h3>
        @for (row of week.rows; track row.id) {
          <hq-card [eyebrow]="gradeLabel(row)">
            <button type="button" class="pw__head" [attr.aria-expanded]="isOpen(row)" (click)="toggle(row)">
              <span class="pw__title">{{ row.title || ('broadcasts.untitled' | transloco) }}</span>
              @if (row.readBy !== null) {
                <span class="pw__read">{{ 'plans.readBy' | transloco: { count: row.readBy } }}</span>
              }
            </button>
            @if (isOpen(row)) {
              @for (body of bodiesOf(row); track body.dir) {
                <p class="pw__body" [dir]="body.dir">{{ body.text }}</p>
              }
              <p class="hq-muted">{{ authorOf(row) }}</p>
              @if (row.plan.attachment; as file) {
                <a class="pw__file" [href]="file.url" target="_blank" rel="noopener">
                  {{ file.name || ('broadcasts.attachment' | transloco) }}
                </a>
              }
            }
          </hq-card>
        }
      </section>
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

    .pw__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
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

    .pw__title {
      font-weight: var(--hq-font-label-weight);
    }

    .pw__read {
      font-size: var(--hq-font-meta-size);
    }

    .pw__body {
      white-space: pre-wrap;
    }

    .pw__file {
      color: var(--hq-accent);
    }
  `,
})
export class PlanWeeksComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly weeks = input.required<readonly PlanWeek[]>();

  private readonly opened = signal<readonly string[]>([]);

  protected isOpen(row: PlanRow): boolean {
    return this.opened().includes(row.id);
  }

  protected toggle(row: PlanRow): void {
    this.opened.update((ids) =>
      ids.includes(row.id) ? ids.filter((other) => other !== row.id) : [...ids, row.id],
    );
  }

  protected bodiesOf(row: PlanRow) {
    return readerBodies(row.plan, this.lang());
  }

  protected gradeLabel(row: PlanRow): string {
    return row.grade === null
      ? this.transloco.translate<string>('broadcasts.gradeAll')
      : this.transloco.translate<string>('broadcasts.gradeN', { grade: row.grade });
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
