/* hq-flag: none (shell) — routed through `areaRoutes`, whose `staff-attendance` rows carry
   `FLAGS.staffAttendance`; the server's controller is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { type Tab, PageComponent, TabsComponent } from '../../ui';
import { type BoardView, AttendanceBoardComponent } from './attendance-board.component';

/**
 * Staff attendance (feature 17) for the Admin, and for a teacher or coordinator who is someone's
 * direct manager: the board and the monthly report, one tab each. The manager's own Staff
 * attendance page draws the same {@link AttendanceBoardComponent} when the switch is on.
 */
@Component({
  selector: 'hq-attendance-board-page',
  imports: [AttendanceBoardComponent, PageComponent, TabsComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.staffAttendance' | transloco" [subtitle]="'staffBoard.subtitle' | transloco">
      <hq-tabs [tabs]="tabs()" [(selected)]="view" [label]="'staffBoard.viewLabel' | transloco" />
      <hq-attendance-board [view]="view()" />
    </hq-page>
  `,
  styles: `
    hq-tabs {
      display: block;
      margin-block-end: var(--hq-space-16);
    }
  `,
})
export class AttendanceBoardPage {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly view = signal<BoardView>('today');

  protected readonly tabs = computed<readonly Tab<BoardView>[]>(() => {
    this.lang();
    return [
      { id: 'today', label: this.transloco.translate<string>('staffBoard.todayTab') },
      { id: 'report', label: this.transloco.translate<string>('staffBoard.reportTab') },
    ];
  });
}
