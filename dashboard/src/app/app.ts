import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanguageService } from './core/i18n/language.service';
import { MotionService } from './ui/motion';

/**
 * The application shell.
 *
 * It injects LanguageService and MotionService so `lang`/`dir` and the reduced-motion
 * attribute are on `<html>` before the first route paints; P3.1 adds the nav and the
 * authenticated layout around this outlet.
 */
@Component({
  selector: 'hq-root',
  imports: [RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<router-outlet />`,
})
export class App {
  protected readonly language = inject(LanguageService);
  protected readonly motion = inject(MotionService);
}
