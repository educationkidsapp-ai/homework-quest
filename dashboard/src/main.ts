import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { applyStoredColorScheme } from './app/core/theme/dark-mode.service';

// Before bootstrap, not in an initializer: an initializer runs after `index.html` has already
// painted, and a person who chose dark would watch a white page for a frame first.
applyStoredColorScheme();

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
