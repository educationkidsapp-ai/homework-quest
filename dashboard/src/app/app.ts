import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanguageService } from './core/i18n/language.service';
import { PlatformService } from './core/platform/platform.service';
import { ThemeService } from './core/theme/theme.service';
import { MotionService } from './ui/motion';

/**
 * The application shell.
 *
 * Four services are injected here and nowhere else, because each of them owns an attribute or
 * a property on `<html>` and has to be alive before the first route paints:
 * `LanguageService` (`lang`/`dir`), `MotionService` (reduced motion), `ThemeService` (the
 * school's `--hq-*` colours) and `PlatformService` (the document title). They are constructed
 * here rather than in a route so the answer does not change under a lazy chunk boundary.
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
  protected readonly theme = inject(ThemeService);
  protected readonly platform = inject(PlatformService);
}
