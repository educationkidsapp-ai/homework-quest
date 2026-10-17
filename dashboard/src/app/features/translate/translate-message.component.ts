import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { type StaffSource, StaffTranslationService } from '../../core/translate/staff-translation.service';

/**
 * Feature 29, on a staff screen: **one line under a parent's own message**.
 *
 * Closed, it is the action — "Translate to English" or «ترجم إلى العربية», whichever language
 * this dashboard is in. Open, it is the translation under a clear "Machine-translated" label,
 * with "Show original only" to put it away. What it never does is replace the message: the
 * original bubble above it is untouched, because the original is the record (the owner's rule).
 *
 * Only on a parent's message, and only while `languageBridge` is on and this account holds
 * `staff.translate` — a colleague's Arabic is not what this is for, and with the switch off
 * `POST /staff/translate` is a 404 anyway, so the action is absent rather than broken (§4's
 * three-place rule). The permission is what keeps it away from an Admin's read-only "View as"
 * session, where that write is a 403 and retrying could never help.
 *
 * Both staff screens that carry a parent's words draw it: Messages
 * (`features/chat/chat.page.ts`) and one complaint (`features/complaints`).
 */
@Component({
  selector: 'hq-translate-message',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shows()) {
      <div class="translate">
        @let translated = bridge.translation(kind(), messageId());
        @if (translated === null) {
          <button type="button" class="translate__action" [disabled]="busy()" (click)="show()">
            {{ (busy() ? 'translate.working' : 'translate.action') | transloco }}
          </button>
        } @else {
          <p class="translate__label">{{ 'translate.machine' | transloco }}</p>
          <p class="translate__text" dir="auto">{{ translated.text }}</p>
          <button type="button" class="translate__action" (click)="hide()">
            {{ 'translate.showOriginal' | transloco }}
          </button>
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .translate {
      margin-block-start: var(--hq-space-4);
      padding-block-start: var(--hq-space-4);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .translate__label {
      margin: 0;
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-semibold);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
    }

    .translate__text {
      margin: var(--hq-space-4) 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .translate__action {
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-semibold);
      text-decoration: underline;
      cursor: pointer;
    }

    .translate__action:disabled {
      cursor: progress;
      color: var(--hq-color-disabled);
    }

    .translate__action:focus-visible {
      outline: var(--hq-size-rule-thin) solid var(--hq-color-accent);
      outline-offset: var(--hq-space-4);
    }
  `,
})
export class TranslateMessageComponent {
  protected readonly bridge = inject(StaffTranslationService);

  /** Which `POST /staff/translate` source this message is: a thread message or a complaint's. */
  readonly kind = input.required<StaffSource>();
  readonly messageId = input.required<string>();
  /** A parent wrote it. A colleague's message carries no action. */
  readonly fromParent = input.required<boolean>();
  /** Nothing to translate: a message that is only files. */
  readonly hasBody = input<boolean>(true);

  protected readonly shows = computed(
    () => this.bridge.allowed() && this.fromParent() && this.hasBody(),
  );
  protected readonly busy = computed(() => this.bridge.busy(this.kind(), this.messageId()));

  protected show(): void {
    this.bridge.translate(this.kind(), this.messageId());
  }

  protected hide(): void {
    this.bridge.hide(this.kind(), this.messageId());
  }
}
