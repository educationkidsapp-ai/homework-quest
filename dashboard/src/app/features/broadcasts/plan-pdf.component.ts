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
      <span class="hq-badge" aria-hidden="true">PDF</span>
      <span class="pp__name" dir="auto">{{ name() || ('plans.pdf.unnamed' | transloco) }}</span>
      <hq-button
        variant="secondary"
        [loading]="opening()"
        [disabled]="!attachmentId()"
        [ariaLabel]="'plans.pdf.openNamed' | transloco: { name: label() }"
        (pressed)="openFile()"
      >
        {{ 'plans.pdf.open' | transloco }}
      </hq-button>
      @if (failed()) {
        <p class="pp__error" role="alert">{{ 'plans.pdf.failed' | transloco }}</p>
      }
    </div>
  `,
  styles: `
    .pp {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--hq-space-8);
      padding: var(--hq-space-12);
      border: var(--hq-size-rule) solid var(--hq-color-ink);
    }

    .pp__name {
      max-inline-size: 100%;
      overflow-wrap: anywhere;
      font-weight: var(--hq-font-label-weight);
    }

    .pp__error {
      margin: 0;
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
