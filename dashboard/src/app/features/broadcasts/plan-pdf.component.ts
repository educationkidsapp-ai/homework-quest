import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { PLAN_PDF_TYPE } from '../../core/broadcasts/plan-rules';
import { reserveTab } from '../../core/download/download';
import { MediaService } from '../../core/media/media.service';
import { ButtonComponent } from '../../ui';

/**
 * **A weekly plan that is a PDF** (D2, list 3): its file name and one action, Open.
 *
 * A picture is drawn; a document is opened. `GET /media/attachments/{id}` is behind the bearer, so
 * a plain link would navigate to a 401 — the bytes come through the generated client and are shown
 * in a new tab (`reserveTab`). There is **no inline preview**: the shipped CSP allows no `blob:`
 * frame or object, and a `data:` one of ten megabytes is not a thing to put in every card.
 *
 * A failed read is said here, in the card, next to the button that caused it — the request is
 * silent to the red band for the reason every media read is.
 */
@Component({
  selector: 'hq-plan-pdf',
  imports: [ButtonComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pp">
      <div class="pp__head">
        <div class="pp__icon-box" aria-hidden="true">
          <svg class="pp__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" width="20" height="20">
            <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
          </svg>
        </div>
        <span class="pp__badge" aria-hidden="true">PDF</span>
      </div>

      <div class="pp__info">
        <span class="pp__name" dir="auto" [title]="name() || ('plans.pdf.unnamed' | transloco)">
          {{ name() || ('plans.pdf.unnamed' | transloco) }}
        </span>
      </div>

      <div class="pp__action">
        <hq-button
          variant="secondary"
          [block]="true"
          [loading]="opening()"
          [disabled]="!attachmentId()"
          [ariaLabel]="'plans.pdf.openNamed' | transloco: { name: label() }"
          (pressed)="openFile()"
        >
          <svg class="pp__btn-icon" viewBox="0 0 20 20" fill="currentColor" width="14" height="14" aria-hidden="true">
            <path d="M11 3a1 1 0 100 2h2.586l-6.293 6.293a1 1 0 101.414 1.414L15 6.414V9a1 1 0 102 0V4a1 1 0 00-1-1h-5z" />
            <path d="M5 5a2 2 0 00-2 2v8a2 2 0 002 2h8a2 2 0 002-2v-3a1 1 0 10-2 0v3H5V7h3a1 1 0 000-2H5z" />
          </svg>
          {{ 'plans.pdf.open' | transloco }}
        </hq-button>
      </div>

      @if (failed()) {
        <p class="pp__error" role="alert">{{ 'plans.pdf.failed' | transloco }}</p>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      inline-size: 100%;
    }

    .pp {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: var(--hq-space-12);
      padding: var(--hq-space-16);
      background: var(--hq-color-surface-sunken);
      border: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      border-radius: var(--hq-radius-control);
      min-block-size: 168px;
      box-sizing: border-box;
      transition: border-color 0.2s ease, box-shadow 0.2s ease;

      &:hover {
        border-color: color-mix(in srgb, var(--hq-color-ink) 25%, var(--hq-color-divider));
      }
    }

    .pp__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      inline-size: 100%;
    }

    .pp__icon-box {
      display: flex;
      align-items: center;
      justify-content: center;
      inline-size: 36px;
      block-size: 36px;
      border-radius: var(--hq-radius-control);
      background: color-mix(in srgb, var(--hq-color-error-600) 10%, var(--hq-color-surface));
      color: var(--hq-color-error-600);
      border: 1px solid color-mix(in srgb, var(--hq-color-error-600) 20%, transparent);
    }

    .pp__badge {
      font-size: var(--hq-text-theme-xs);
      font-weight: var(--hq-text-weight-bold);
      letter-spacing: 0.05em;
      color: var(--hq-color-error-600);
      background: color-mix(in srgb, var(--hq-color-error-600) 10%, transparent);
      padding: 2px 8px;
      border-radius: var(--hq-radius-pill, 9999px);
    }

    .pp__info {
      inline-size: 100%;
      min-block-size: 40px;
      display: flex;
      align-items: flex-start;
    }

    .pp__name {
      margin: 0;
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink);
      line-height: 1.45;
      overflow-wrap: anywhere;
      word-break: break-word;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .pp__action {
      inline-size: 100%;
      margin-block-start: auto;
    }

    .pp__btn-icon {
      margin-inline-end: var(--hq-space-6);
      vertical-align: -2px;
    }

    .pp__error {
      margin: 0;
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-error-ink);
    }
  `,
})
export class PlanPdfComponent {
  private readonly media = inject(MediaService);

  readonly attachmentId = input.required<string | null>();
  /** The file's own name, which is the only words a plan carries. */
  readonly name = input<string>('');
  /** What the plan is, for the button's accessible name: "Weekly plan · Grade 3 · Week of …". */
  readonly label = input<string>('');

  protected readonly opening = signal(false);
  protected readonly failed = signal(false);

  protected openFile(): void {
    const id = this.attachmentId();
    if (!id || this.opening()) return;
    // Reserved inside the click: a tab opened after the bytes arrive is a blocked popup.
    const tab = reserveTab();
    this.opening.set(true);
    this.failed.set(false);
    this.media.attachmentFile(id).subscribe({
      next: (blob) => {
        this.opening.set(false);
        // Typed here rather than trusted: the viewer is chosen by the type on the Blob.
        tab.show(new Blob([blob], { type: PLAN_PDF_TYPE }), this.name() || 'weekly-plan.pdf');
      },
      error: () => {
        this.opening.set(false);
        this.failed.set(true);
        tab.cancel();
      },
    });
  }
}
