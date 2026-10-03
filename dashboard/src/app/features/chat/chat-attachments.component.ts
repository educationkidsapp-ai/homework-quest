import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import {
  ChatAttachment,
  CHAT_PDF_TYPE,
  formatBytes,
  isImageAttachment,
} from '../../core/chat/chat-attachments';
import { reserveTab } from '../../core/download/download';
import { MediaService } from '../../core/media/media.service';
import { AttachmentImageDirective, ButtonComponent, DialogComponent } from '../../ui';

const THUMB_WIDTH = 640;

/**
 * **The files on one chat message** (D4): pictures drawn, documents opened.
 *
 * Every byte comes through the generated client with the bearer — `GET /media/attachments/{id}`
 * is for the two people on the thread only, so an `<img src>` at it would be a 401.
 *
 * * A picture is `AttachmentImageDirective`: loaded only when it nears the viewport, at most three
 *   at once, into the media cache's bounded LRU as a `data:` URL. Not an object URL: the shipped
 *   CSP's `img-src 'self' https: data:` does not take `blob:`, and the cache's caps are what bound
 *   a long conversation's memory instead. Clicking it opens it full size in a dialog (Esc, the X,
 *   focus kept inside).
 * * A PDF is a card — name, size, Open — and Open shows it in a tab reserved inside the click, as
 *   the weekly plan's PDF does (`PlanPdfComponent`): the CSP allows no `blob:` frame in the page.
 */
@Component({
  selector: 'hq-chat-attachments',
  imports: [AttachmentImageDirective, ButtonComponent, DialogComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (images().length > 0) {
      <div class="ca__images" [class.ca__images--many]="images().length > 1">
        @for (image of images(); track image.id) {
          <button
            type="button"
            class="ca__thumb"
            [attr.aria-label]="'chat.attachment.viewImage' | transloco: { name: image.name }"
            (click)="enlarged.set(image)"
          >
            <img class="ca__img" [hqAttachmentImage]="image.id" [downscale]="thumbWidth" [alt]="image.name" />
          </button>
        }
      </div>
    }
    @for (file of files(); track file.id) {
      <div class="ca__file">
        <span class="hq-badge" aria-hidden="true">PDF</span>
        <span class="ca__file-text">
          <span class="ca__file-name" dir="auto" [title]="file.name">{{ file.name }}</span>
          <span class="ca__file-size">{{ sizeOf(file) }}</span>
        </span>
        <hq-button
          variant="secondary"
          [loading]="opening() === file.id"
          [ariaLabel]="'chat.attachment.openNamed' | transloco: { name: file.name }"
          (pressed)="openFile(file)"
        >
          {{ 'chat.attachment.open' | transloco }}
        </hq-button>
      </div>
      @if (failed() === file.id) {
        <p class="ca__error" role="alert">{{ 'chat.attachment.openFailed' | transloco }}</p>
      }
    }

    @if (enlarged(); as image) {
      <hq-dialog [open]="true" [title]="image.name" (openChange)="onDialog($event)">
        <img class="ca__full" [hqAttachmentImage]="image.id" [eager]="true" [alt]="image.name" />
      </hq-dialog>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
    }

    .ca__images {
      display: grid;
      gap: var(--hq-space-4);
      max-inline-size: var(--hq-size-stop-list-width);
    }

    .ca__images--many {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .ca__thumb {
      display: block;
      padding: 0;
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-xs);
      background: var(--hq-color-surface-sunken);
      overflow: hidden;
      cursor: zoom-in;
      @include m.focus-ring;
    }

    .ca__img {
      display: block;
      inline-size: 100%;
      max-block-size: var(--hq-size-drop-zone-height);
      min-block-size: var(--hq-size-grade-card);
      object-fit: cover;
    }

    .ca__images--many .ca__img {
      aspect-ratio: 1;
    }

    .ca__file {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      max-inline-size: var(--hq-size-stop-list-width);
      padding: var(--hq-space-8) var(--hq-space-12);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-xs);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
    }

    .ca__file-text {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-inline-size: 0;
    }

    .ca__file-name {
      overflow: hidden;
      font-weight: var(--hq-font-label-weight);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .ca__file-size {
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-note);
    }

    .ca__error {
      margin: 0;
      color: var(--hq-color-error-ink);
      font-size: var(--hq-text-note);
    }

    .ca__full {
      display: block;
      max-inline-size: 100%;
      max-block-size: 70vh;
      margin-inline: auto;
      object-fit: contain;
    }
  `,
})
export class ChatAttachmentsComponent {
  private readonly media = inject(MediaService);

  readonly attachments = input.required<readonly ChatAttachment[]>();

  /** One of the widths the server keeps (320/640/1280): about twice the bubble's widest picture. */
  protected readonly thumbWidth = THUMB_WIDTH;
  protected readonly images = computed(() => this.attachments().filter(isImageAttachment));
  protected readonly files = computed(() => this.attachments().filter((file) => !isImageAttachment(file)));
  protected readonly enlarged = signal<ChatAttachment | null>(null);
  protected readonly opening = signal<string | null>(null);
  protected readonly failed = signal<string | null>(null);

  protected onDialog(open: boolean): void {
    if (!open) this.enlarged.set(null);
  }

  protected sizeOf(file: ChatAttachment): string {
    return formatBytes(file.size);
  }

  protected openFile(file: ChatAttachment): void {
    if (this.opening() !== null) return;
    // Reserved inside the click: a tab opened after the bytes arrive is a blocked popup.
    const tab = reserveTab();
    this.opening.set(file.id);
    this.failed.set(null);
    this.media.attachmentFile(file.id).subscribe({
      next: (blob) => {
        this.opening.set(null);
        tab.show(new Blob([blob], { type: file.contentType || CHAT_PDF_TYPE }), file.name);
      },
      error: () => {
        this.opening.set(null);
        this.failed.set(file.id);
        tab.cancel();
      },
    });
  }
}
