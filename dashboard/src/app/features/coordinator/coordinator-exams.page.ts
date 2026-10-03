/* hq-flag: none (shell) — the flag *is* declared, on the `exams` row in `core/nav/screens.ts`,
   which `areaRoutes` turns into `featureGuard(FLAGS.exams)` for the route and into the rail
   item's own gate — the same `exams` flag `ExamController` puts over every one of its routes, so
   a school without it meets `/not-found` before this file loads rather than a screen of 404s. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { EmptyStateComponent, PageComponent, SelectComponent, SkeletonComponent } from '../../ui';
import { ExamsTabComponent } from '../exams/exams-tab.component';
import { classPicker } from './coordinator-class-picker';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { StaffScopeService } from './staff-scope.service';

/**
 * Exams (R6): one of her sections, the teacher's own exams table, read only.
 *
 * `hq-exams-tab` in `readOnly` mode: title, window, state, how many sat and how much marking is
 * left, with New exam not rendered. A row opens the exam's results — the same read-only page the
 * teacher's row opens, in her area — and the title opens the exam itself as a read-only lesson.
 *
 * Her list costs one request rather than one per open exam: R3's `ExamRow` carries `sat`,
 * `roster` and `needsMarking`, which the teacher's `ExamSettings` does not.
 */
@Component({
  selector: 'hq-coordinator-exams-page',
  imports: [
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    ExamsTabComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.exams' | transloco" [subtitle]="co.scoped('exams.subtitle') | transloco">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (co.failed()) {
        <hq-coordinator-read-failed (retry)="co.reload()" />
      } @else if (picker.options().length === 0) {
        <hq-empty-state [message]="co.scoped('classes.empty') | transloco" />
      } @else {
        <hq-select
          [label]="'coordinator.lessons.byClass' | transloco"
          [options]="picker.options()"
          [value]="picker.classId()"
          (valueChange)="picker.select($event)"
        />
        <hq-exams-tab [classId]="picker.classId()" [className]="picker.className()" [readOnly]="true" />
      }
    </hq-page>
  `,
  styles: `
    hq-select {
      display: block;
      margin-block-end: 20px;
    }
  `,
})
export class CoordinatorExamsPage {
  protected readonly co = inject(StaffScopeService);
  protected readonly picker = classPicker();
}
