import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { type ChatFileRefusal, type ChatUploadFailure, formatBytes } from '../../core/chat/chat-attachments';
import { ChatUploads } from '../../core/chat/chat-uploads';
import { BandComponent, ButtonComponent, ProgressBarComponent } from '../../ui';

/**
 * **The files a composer is about to send** (D4, shared by D5): one row per picked file, each
 * uploading at once with its own bar; ✕ cancels one still uploading and takes a finished one off
 * the message; the last refusal in a band under them.
 *
 * Reads the {@link ChatUploads} its composer provides, so Messages and a complaint's reply box
 * stage files the same way and neither can drift from the other.
 */
@Component({
  selector: 'hq-chat-staged-files',
  imports: [BandComponent, ButtonComponent, ProgressBarComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (uploads.staged().length > 0) {
      <ul class="composer-files" [attr.aria-label]="'chat.attachment.staged' | transloco">
        @for (file of uploads.staged(); track file.key) {
          <li class="composer-file" [class.is-failed]="file.state === 'failed'">
            @if (file.preview) {
              <img class="composer-file__thumb" [src]="file.preview" [alt]="file.name" />
            } @else {
              <span class="hq-badge composer-file__badge" aria-hidden="true">{{
                file.kind === 'pdf' ? 'PDF' : 'IMG'
              }}</span>
            }
            <span class="composer-file__text">
              <span class="composer-file__name" dir="auto" [title]="file.name">{{ file.name }}</span>
              @if (file.state === 'uploading') {
                <hq-progress-bar
                  [plain]="true"
                  [value]="null"
                  [label]="'chat.attachment.uploading' | transloco: { name: file.name }"
                />
              } @else {
                <span class="composer-file__size">{{ sizeOf(file.size) }}</span>
              }
            </span>
            <hq-button
              variant="icon"
              [ariaLabel]="
                (file.state === 'uploading' ? 'chat.attachment.cancel' : 'chat.attachment.remove')
                  | transloco: { name: file.name }
              "
              (pressed)="uploads.remove(file.key)"
            >
              <svg
                viewBox="0 0 20 20"
                width="16"
                height="16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                aria-hidden="true"
              >
                <path d="M5 5l10 10M15 5L5 15" />
              </svg>
            </hq-button>
          </li>
        }
      </ul>
    }
    @if (uploads.problem(); as problem) {
      <hq-band [open]="true" (dismissed)="uploads.problem.set(null)">
        {{ problemText(problem.reason) | transloco: { name: problem.name } }}
      </hq-band>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .composer-files {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      margin: 0 0 var(--hq-space-8);
      padding: 0;
      list-style: none;
    }

    .composer-file {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
      padding-inline-start: var(--hq-space-8);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-xs);
      background: var(--hq-color-surface-sunken);
      color: var(--hq-color-ink);

      &.is-failed {
        border-color: var(--hq-color-error-rule);
        background: var(--hq-color-error-soft);
      }
    }

    .composer-file__thumb {
      flex: none;
      inline-size: var(--hq-size-touch-target);
      block-size: var(--hq-size-touch-target);
      border-radius: var(--hq-radius-xs);
      object-fit: cover;
    }

    .composer-file__badge {
      flex: none;
    }

    .composer-file__text {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: var(--hq-space-4);
      min-inline-size: 0;
    }

    .composer-file__name {
      overflow: hidden;
      font-weight: var(--hq-font-label-weight);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .composer-file__size {
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-note);
    }
  `,
})
export class ChatStagedFilesComponent {
  protected readonly uploads = inject(ChatUploads);

  protected problemText(reason: ChatFileRefusal | ChatUploadFailure): string {
    return `chat.upload.${reason}`;
  }

  protected sizeOf(bytes: number): string {
    return formatBytes(bytes);
  }
}
