import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';
import { type School, SchoolsApi } from '../../api';
import { SchoolScopeStore } from '../../core/auth/school-scope.store';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { MAX_PHONE_LENGTH, normalisePhone, phoneErrorKey } from '../../core/forms/phone';
import { CanDirective } from '../../core/permissions/can.directive';
import { ThemeService } from '../../core/theme/theme.service';
import { BandComponent, ButtonComponent, InputComponent } from '../../ui';

/** S1: "image ≤ 1 MB" — refused here, with the reason, before a megabyte is sent to be refused. */
export const MAX_LOGO_BYTES = 1024 * 1024;
/** What `PUT /admin/schools/{id}/logo` takes — it sniffs the bytes, so the name is not enough. */
const LOGO_TYPES = ['image/png', 'image/jpeg', 'image/webp'];
/** Before the read lands, or after one that failed: a name that falls back and a blank number. */
const NO_SCHOOL: School = {};

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
 *
 * **The school's telephone number** lives here too (feature 19). It is school information rather
 * than a chat setting, but the only thing that reads it is the "call the school" line a parent is
 * shown while her child's teacher is outside office hours — so the field rides on
 * `chatOfficeHours` and does not ask a school without the feature for a number nothing would use.
 */
@Component({
  selector: 'hq-school-card',
  imports: [BandComponent, ButtonComponent, CanDirective, FeatureDirective, InputComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (schoolId(); as id) {
      <section class="school" [attr.aria-label]="'admin.school.title' | transloco" data-hq-school-card>
        <div class="school__logo" [class.school__logo--empty]="!shownLogo()">
          @if (shownLogo(); as logo) {
            <img
              class="school__image"
              [src]="logo"
              [alt]="'admin.school.logoAlt' | transloco: { name: name() }"
              (error)="failedLogo.set(logo)"
            />
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

        <div class="school__phone" *hqFeature="chatOfficeHoursFlag">
          <div class="school__phone-form" *hqCan="'school.write'" data-hq-school-phone>
            <hq-input
              class="school__phone-field"
              type="tel"
              dir="ltr"
              autocomplete="off"
              [label]="'admin.school.phone' | transloco"
              [hint]="'admin.school.phoneHint' | transloco"
              [maxLength]="maxPhone"
              [error]="phoneError()"
              [disabled]="savingPhone()"
              [value]="phone()"
              (valueChange)="setPhone($event)"
            />
            <hq-button
              [disabled]="!phoneDirty() || phoneError() !== null || savingPhone()"
              (pressed)="savePhone(id)"
            >
              {{ 'admin.school.savePhone' | transloco }}
            </hq-button>
            @if (phoneSaved()) {
              <p class="school__saved" role="status">{{ 'admin.school.phoneSaved' | transloco }}</p>
            }
          </div>
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

    .school__phone {
      grid-column: 1 / -1;
    }

    // The field and its one action on a line, the Save button on the field's own baseline rather
    // than on its label's.
    .school__phone-form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hq-space-8);
      padding-block-start: var(--hq-space-16);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .school__phone-field {
      flex: 1 1 60%;
      min-inline-size: 0;
    }

    .school__saved {
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-ink-soft);
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
    // The name is a caption and the number a field with a blank state: a card without either
    // still uploads a logo.
    stream: ({ params: id }) => this.schools.school(id).pipe(catchError(() => of(NO_SCHOOL))),
    defaultValue: NO_SCHOOL,
  });

  protected readonly name = computed(() => this.school.value().name || this.scope.scope()?.name || '');

  /** What the shell shows too — the school's own, or the platform's when it has none. */
  protected readonly logoUrl = computed(() => this.theme.logoUrl());
  /** The preview's address that would not load: the box shows its empty state, the controls still say Replace. */
  protected readonly failedLogo = signal<string | null>(null);
  protected readonly shownLogo = computed(() => {
    const logo = this.logoUrl();
    return logo && logo !== this.failedLogo() ? logo : null;
  });
  /** Only the school's own logo can be removed; the platform's fallback is not hers to delete. */
  protected readonly hasOwnLogo = computed(() => (this.theme.theme()?.logoUrl ?? '') !== '');

  /** The logo was replaced or removed: for a host that shows it from a response of its own. */
  readonly changed = output<void>();

  protected readonly busy = signal(false);
  protected readonly problem = signal<string | null>(null);
  protected readonly confirmingRemove = signal(false);

  // ---- feature 19: the school's telephone number ----------------------------------------

  protected readonly chatOfficeHoursFlag = FLAGS.chatOfficeHours;
  protected readonly maxPhone = MAX_PHONE_LENGTH;

  /** What the school has now — the saved number, which is also what "unchanged" is measured against. */
  private readonly savedPhone = computed(() => this.school.value().phone ?? '');
  /**
   * What is in the field. Reseeded whenever the saved number changes — the first read, and every
   * write's own response — so a number saved here, or changed in another tab, replaces what is
   * typed only when the server's answer differs from it.
   */
  protected readonly phone = linkedSignal(() => this.savedPhone());
  protected readonly savingPhone = signal(false);
  protected readonly phoneSaved = signal(false);

  /** The same rule the server applies (`core/forms/phone`), so a refusal is rare and explained here. */
  protected readonly phoneError = computed(() => {
    const key = phoneErrorKey(this.phone());
    return key === null ? null : this.transloco.translate<string>(key);
  });

  /** Nothing to save when the field normalises to what is already stored. */
  protected readonly phoneDirty = computed(() => (normalisePhone(this.phone()) ?? '') !== this.savedPhone());

  protected setPhone(value: string): void {
    this.phone.set(value);
    this.phoneSaved.set(false);
  }

  /**
   * `PATCH /admin/schools/{id}` with the number alone — the normalised form, or `''` to clear it,
   * which is what the server reads as "the school has no number". The response is the school, so
   * the saved value (and with it the field) comes back from the write rather than from a second
   * read; a refusal is the error interceptor's red band, and the field keeps what was typed.
   */
  protected savePhone(schoolId: string): void {
    if (this.phoneError() !== null || this.savingPhone()) return;
    this.savingPhone.set(true);
    this.phoneSaved.set(false);
    this.schools.updateSchool(schoolId, { phone: normalisePhone(this.phone()) ?? '' }).subscribe({
      next: (school) => {
        this.savingPhone.set(false);
        this.phoneSaved.set(true);
        this.school.set(school);
      },
      error: () => this.savingPhone.set(false),
    });
  }

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
