/* hq-flag: none (shell) — the flag *is* declared, on the `gradebook` row in
   `core/nav/screens.ts`, which `areaRoutes` turns into `featureGuard(FLAGS.gradebook)` for the
   route and into the rail item's own gate. Repeating it inside the page would be the drift the
   rule exists to prevent; a school without the flag meets `/not-found` before this file loads. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { EmptyStateComponent, PageComponent, SelectComponent, SkeletonComponent } from '../../ui';
import { GradebookComponent } from '../results/gradebook.component';
import { classPicker } from './coordinator-class-picker';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { CoordinatorService } from './coordinator.service';

/**
 * Gradebook (R6): one of her sections, the teacher's own grid, read only.
 *
 * `hq-gradebook` in `readOnly` mode — children down the side, lessons across the top, one
 * coloured square each, and no cell that opens an editor, no release toggle and neither export
 * (both `.csv` and `.xlsx` are teacher-namespace routes). The range lives inside the component,
 * which is why this screen carries only the class picker: her eight weeks and a teacher's eight
 * weeks are the same eight weeks.
 *
 * A square opens nothing; a **name** does — `/coordinator/children/{id}`, her copy of the child
 * report. The component derives that base from the role rather than taking it from here, so a
 * screen cannot wire it to the wrong area.
 */
@Component({
  selector: 'hq-coordinator-gradebook-page',
  imports: [
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    GradebookComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.gradebook' | transloco" [subtitle]="'coordinator.gradebook.subtitle' | transloco">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (co.failed()) {
        <hq-coordinator-read-failed (retry)="co.reload()" />
      } @else if (picker.options().length === 0) {
        <hq-empty-state [message]="'coordinator.classes.empty' | transloco" />
      } @else {
        <hq-select
          [label]="'coordinator.lessons.byClass' | transloco"
          [options]="picker.options()"
          [value]="picker.classId()"
          (valueChange)="picker.select($event)"
        />
        <hq-gradebook [classId]="picker.classId()" [className]="picker.className()" [readOnly]="true" />
      }
    </hq-page>
  `,
})
export class CoordinatorGradebookPage {
  protected readonly co = inject(CoordinatorService);
  protected readonly picker = classPicker();
}
