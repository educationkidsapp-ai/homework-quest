import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, map, of } from 'rxjs';
import { SchoolsApi } from '../../api';
import { SchoolScopeStore } from '../../core/auth/school-scope.store';
import { CanDirective } from '../../core/permissions/can.directive';
import { ThemeService } from '../../core/theme/theme.service';
import { BandComponent, ButtonComponent } from '../../ui';

/** S1: "image ≤ 1 MB" — refused here, with the reason, before a megabyte is sent to be refused. */
export const MAX_LOGO_BYTES = 1024 * 1024;
/** What `PUT /admin/schools/{id}/logo` takes — it sniffs the bytes, so the name is not enough. */
const LOGO_TYPES = ['image/png', 'image/jpeg', 'image/webp'];

/**
 * **The school's logo** — a small card on the Admin's Home (the owner's list of 2026-10-01, ADMIN
 * item 6): upload one, replace it, remove it.
 *
 * On the Home rather than on a settings screen, because item 5 of the same list took Schools out
 * of the rail and Platform settings went with MA0 — there is no settings surface left to reach.
 *
 * **Which school.** The one in scope, or the only one there is (`SchoolScopeStore`). With several
 * schools and none chosen the card is not drawn: a logo belongs to a school, and guessing which
 * would put one school's badge on another.
 *
 * **Seen everywhere without a reload.** The server puts the upload into the theme's existing
 * `logoUrl` — as `/schools/{id}/logo?v=<uploaded-at>`, so the address changes with the image and
 * no cache has to be fought. The shell's logo block and this card both read
 * `ThemeService.logoUrl()`, so one `reload()` after the write repaints them together; the host is
 * told too (`changed`), for a logo it shows from a response of its own. The sign-in page asks
 * `POST /schools/logo` afresh every time an email is typed, so it has the new one the next time
 * anybody sees it.
 *
 * Removing is the one destructive thing here, so it asks first — in a red band, in place.
 */
@Component({
  selector: 'hq-school-card',
  imports: [BandComponent, ButtonComponent, CanDirective, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (schoolId(); as id) {
      <section class="school" [attr.aria-label]="'admin.school.title' | transloco" data-hq-school-card>
        <div class="school__logo" [class.school__logo--empty]="!logoUrl()">
          @if (logoUrl(); as logo) {
            <img class="school__image" [src]="logo" [alt]="'admin.school.logoAlt' | transloco: { name: name() }" />
          } @else {
            <span class="school__placeholder">{{ 'admin.school.noLogo' | transloco }}</span>
          }
        </div>

        <div class="school__body">
          <h2 class="school__title">{{ 'admin.school.title' | transloco }}</h2>
          <p class="school__name">{{ name() }}</p>
          <p class="school__hint">{{ 'admin.school.hint' | transloco }}</p>

          <div class="school__actions" *hqCan="'school.write'">
            <label class="school__upload">
              <input
                class="school__file"
                type="file"
                [accept]="accept"
                [disabled]="busy()"
                (change)="onFile($event, id)"
              />
              <span class="school__upload-label">
                {{ (logoUrl() ? 'admin.school.replace' : 'admin.school.upload') | transloco }}
              </span>
            </label>
            @if (hasOwnLogo()) {
              <hq-button variant="quiet" [disabled]="busy()" (pressed)="confirmingRemove.set(true)">
                {{ 'admin.school.remove' | transloco }}
              </hq-button>
            }
          </div>

          @if (problem(); as message) {
            <p class="school__problem" role="alert">{{ message }}</p>
          }
        </div>

        @if (confirmingRemove()) {
          <div class="school__confirm">
            <hq-band
              variant="error"
              [open]="true"
              [title]="'admin.school.removeTitle' | transloco"
              [confirmLabel]="'admin.school.remove' | transloco"
              (confirmed)="remove(id)"
              (dismissed)="confirmingRemove.set(false)"
            >
              {{ 'admin.school.removeMessage' | transloco: { name: name() } }}
            </hq-band>
          </div>
        }
      </section>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .school {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: var(--hq-space-16) var(--hq-space-24);
      align-items: center;
      padding: var(--hq-space-24);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
      background: var(--hq-color-surface);
    }

    .school__logo {
      display: grid;
      place-items: center;
      inline-size: calc(var(--hq-size-logo-size) * 2);
      block-size: calc(var(--hq-size-logo-size) * 2);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-surface-sunken);
      overflow: hidden;
    }

    .school__logo--empty {
      border-style: dashed;
    }

    .school__image {
      max-inline-size: 100%;
      max-block-size: 100%;
      object-fit: contain;
    }

    .school__placeholder {
      padding: var(--hq-space-8);
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
      text-align: center;
    }

    .school__body {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      min-inline-size: 0;
    }

    .school__title {
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-soft);
    }

    .school__name {
      font-size: var(--hq-text-theme-xl);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-ink);
      overflow-wrap: anywhere;
    }

    .school__hint {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }

    .school__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-8);
      margin-block-start: var(--hq-space-8);
    }

    // The native input is kept in the tab order and visually replaced by its label: a file
    // chooser that only a pointer can open is not a control.
    .school__file {
      position: absolute;
      inline-size: 1px;
      block-size: 1px;
      opacity: 0;
    }

    .school__upload-label {
      display: inline-flex;
      align-items: center;
      block-size: var(--hq-size-button-height);
      padding-inline: var(--hq-space-16);
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-accent);
      color: var(--hq-color-on-accent);
      font-weight: var(--hq-font-label-weight);
      cursor: pointer;
      @include m.motion-safe('background-color');
    }

    .school__file:disabled + .school__upload-label {
      background: var(--hq-color-disabled);
      cursor: not-allowed;
    }

    .school__file:focus-visible + .school__upload-label {
      outline: var(--hq-size-focus-ring) solid var(--hq-color-focus);
      outline-offset: var(--hq-size-focus-ring-offset);
    }

    .school__problem {
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-error-ink);
    }

    .school__confirm {
      grid-column: 1 / -1;
    }

    @media (width <= 40rem) {
      .school {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class SchoolCardComponent {
  private readonly scope = inject(SchoolScopeStore);
  private readonly schools = inject(SchoolsApi);
  private readonly theme = inject(ThemeService);
  private readonly transloco = inject(TranslocoService);

  protected readonly accept = LOGO_TYPES.join(',');

  /** The school in scope, else the only one there is. Null — no card — while neither is known. */
  protected readonly schoolId = computed(() => this.scope.schoolId() ?? this.scope.soleSchoolId());

  private readonly school = rxResource({
    params: () => this.schoolId() ?? undefined,
    stream: ({ params: id }) =>
      this.schools.school(id).pipe(
        map((school) => school.name ?? ''),
        // The name is a caption: a card without one still uploads a logo.
        catchError(() => of('')),
      ),
    defaultValue: '',
  });

  protected readonly name = computed(() => this.school.value() || this.scope.scope()?.name || '');

  /** What the shell shows too — the school's own, or the platform's when it has none. */
  protected readonly logoUrl = computed(() => this.theme.logoUrl());
  /** Only the school's own logo can be removed; the platform's fallback is not hers to delete. */
  protected readonly hasOwnLogo = computed(() => (this.theme.theme()?.logoUrl ?? '') !== '');

  /** The logo was replaced or removed: for a host that shows it from a response of its own. */
  readonly changed = output<void>();

  protected readonly busy = signal(false);
  protected readonly problem = signal<string | null>(null);
  protected readonly confirmingRemove = signal(false);

  protected onFile(event: Event, schoolId: string): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Cleared so choosing the same file again — after fixing it — is still a change.
    input.value = '';
    if (!file) return;
    this.confirmingRemove.set(false);
    if (!LOGO_TYPES.includes(file.type)) {
      this.problem.set(this.transloco.translate<string>('admin.school.notImage'));
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      this.problem.set(this.transloco.translate<string>('admin.school.tooLarge'));
      return;
    }
    this.problem.set(null);
    this.busy.set(true);
    this.schools.uploadSchoolLogo(schoolId, file).subscribe({
      next: () => this.settled(),
      // The server's own sentence is already in the red band (the error interceptor).
      error: () => this.busy.set(false),
    });
  }

  protected remove(schoolId: string): void {
    this.confirmingRemove.set(false);
    this.busy.set(true);
    this.schools.deleteSchoolLogo(schoolId).subscribe({
      next: () => this.settled(),
      error: () => this.busy.set(false),
    });
  }

  /** One reload repaints this card and the shell's logo block together. */
  private settled(): void {
    this.busy.set(false);
    this.theme.reload();
    this.changed.emit();
  }
}
