import { ChangeDetectionStrategy, Component, input, linkedSignal, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { BandComponent, ButtonComponent } from '../../ui';

/**
 * A password the server will not answer a second time.
 *
 * `POST /admin/coordinators`, `…/managers`, either `reset-password` and
 * `POST /admin/children/{id}/parent/reset-password` each answer a readable password **once** and
 * store a hash. So the value lives in the caller's one signal and nowhere else — never
 * `localStorage`, never a resource's cache — and this component only draws it: a notice band with
 * the sentence saying it will not be shown again, a `<code>` a screen reader reads out
 * (`role="status"`, so it is announced when it appears rather than found by tabbing), and one
 * button that copies it.
 *
 * Extracted from the Teachers page's band (N1.2) the moment a second and a third screen needed
 * the same three parts, because three copies of "shown once" is three places for one of them to
 * quietly become "shown until the tab closes".
 */
@Component({
  selector: 'hq-one-time-password',
  imports: [BandComponent, ButtonComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (value(); as password) {
      <hq-band variant="notice" [open]="true" [title]="title()" (dismissed)="dismissed.emit()">
        <p class="hq-otp__note">{{ 'admin.people.password.note' | transloco }}</p>
        <div class="hq-otp" role="status">
          <code class="hq-otp__code" dir="ltr" data-hq-temp-password>{{ password }}</code>
          <hq-button variant="secondary" (pressed)="copy(password)">
            {{ (copied() ? 'admin.people.password.copied' : 'admin.people.password.copy') | transloco }}
          </hq-button>
        </div>
      </hq-band>
    }
  `,
  styles: `
    .hq-otp {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-16);
      margin-block-start: var(--hq-space-8);
    }

    .hq-otp__code {
      padding: var(--hq-space-8) var(--hq-space-16);
      background: var(--hq-color-surface-sunken);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-control);
      font-family: var(--hq-font-family-mono);
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-semibold);
      letter-spacing: var(--hq-font-letter-spacing-label);
      color: var(--hq-color-ink);
    }
  `,
})
export class OneTimePasswordComponent {
  /** The password, or `null` when there is none to show — the band is absent, not empty. */
  readonly value = input<string | null>(null);
  readonly title = input.required<string>();
  readonly dismissed = output<void>();

  /**
   * Linked to the value, so the *next* password's button reads "Copy" again rather than
   * inheriting the last one's "Copied" and telling her she has something she has not.
   */
  protected readonly copied = linkedSignal<string | null, boolean>({
    source: this.value,
    computation: () => false,
  });

  protected copy(password: string): void {
    void navigator.clipboard?.writeText(password).then(
      () => this.copied.set(true),
      () => this.copied.set(false),
    );
  }
}
