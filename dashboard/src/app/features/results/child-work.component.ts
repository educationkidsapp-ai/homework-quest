import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { MediaService, childMediaIdOf } from '../../core/media/media.service';
import { blobOfDataUrl, exportName, extensionOf, saveFile } from '../../core/download/download';
import { ButtonComponent, ChildImageDirective } from '../../ui';

/**
 * One piece of a child's saved work, next to the stop it belongs to (§4 step 9).
 *
 * **Three carriers, one rule: the bytes are behind the bearer.** A drawing or a photographed
 * answer is an `<img>` fed by {@link ChildImageDirective}, which reads it through the generated
 * client and hands it over as a `data:` URL — the only form the shipped CSP's `img-src` allows.
 *
 * **A recording is downloaded, not streamed.** The same CSP has no `media-src`, so it falls back
 * to `default-src 'self'` and a `data:` audio source is refused by the browser. The player is
 * therefore offered optimistically and withdrawn the moment the element says it cannot load it,
 * leaving the one control that always works: Save the recording. (The server can make the player
 * work everywhere by adding `media-src 'self' data:` to `DashboardController.CSP`; until then a
 * teacher gets the file rather than a dead transport bar.)
 */
@Component({
  selector: 'hq-child-work',
  imports: [ButtonComponent, ChildImageDirective, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (url()) {
      <div class="work">
        @if (audio()) {
          @if (playable()) {
            <audio
              class="work__audio"
              controls
              [src]="source()"
              [attr.aria-label]="'results.work.recordingOf' | transloco: { name: childName() }"
              (error)="playable.set(false)"
            ></audio>
          } @else {
            <p class="work__note">{{ 'results.work.cannotPlay' | transloco }}</p>
          }
          <hq-button variant="secondary" [loading]="saving()" (pressed)="save()">
            {{ 'results.work.download' | transloco }}
          </hq-button>
        } @else {
          <img
            class="work__image"
            [hqChildImage]="url()"
            [alt]="'results.work.drawingOf' | transloco: { name: childName() }"
          />
        }
      </div>
    } @else {
      <p class="work__note">{{ 'results.work.none' | transloco }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .work {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--hq-space-12);
    }

    .work__audio {
      inline-size: 100%;
      max-inline-size: 320px;
    }

    .work__image {
      inline-size: 100%;
      max-inline-size: 260px;
      block-size: auto;
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-tile);
      background: var(--hq-color-surface);
    }

    .work__note {
      margin: 0;
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
    }
  `,
})
export class ChildWorkComponent {
  private readonly media = inject(MediaService);

  /** `ChildStopResult.workUrl` — an absolute `…/media/child/{id}` link, or nothing saved. */
  readonly url = input.required<string | null>();
  /** The stop's own type: `retell` is the one that is listened to rather than looked at. */
  readonly stopType = input('');
  readonly childName = input('');

  protected readonly audio = computed(() => this.stopType() === 'retell');
  protected readonly playable = signal(true);
  protected readonly saving = signal(false);
  protected readonly source = signal('');

  constructor() {
    // The bytes are fetched once for both purposes: the player's source and the file the button
    // saves. `MediaService` caches them, so the second use costs nothing. An effect rather than
    // the constructor body, because a required input has no value until the first read.
    effect(() => {
      const id = childMediaIdOf(this.url());
      if (!id || !this.audio()) return;
      this.media.childMedia(id).subscribe({
        next: (dataUrl) => this.source.set(dataUrl),
        error: () => this.playable.set(false),
      });
    });
  }

  protected async save(): Promise<void> {
    const id = childMediaIdOf(this.url());
    if (!id) return;
    this.saving.set(true);
    try {
      const dataUrl = this.source() || (await firstValueFrom(this.media.childMedia(id)));
      const blob = blobOfDataUrl(dataUrl);
      saveFile(
        blob,
        exportName([this.childName(), this.stopType()], extensionOf(blob.type, 'webm')),
        blob.type,
      );
    } catch {
      this.playable.set(false);
    } finally {
      this.saving.set(false);
    }
  }
}
