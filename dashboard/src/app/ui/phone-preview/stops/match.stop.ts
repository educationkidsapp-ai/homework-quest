import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';
import { IllustrationComponent } from '../illustration.component';
import { MatchPair, MatchStop, Tile } from '../stop.model';
import { opened } from './answer-state';

/** The same seeded shuffle the app uses, so a stop always lays its right column out the same way. */
function shuffleBySeed<T>(items: readonly T[], seed: string): readonly T[] {
  let state = 0;
  for (const char of seed) state = (Math.imul(31, state) + (char.codePointAt(0) ?? 0)) | 0;
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    state = (Math.imul(1103515245, state) + 12345) | 0;
    const j = Math.abs(state) % (i + 1);
    const a = out[i];
    const b = out[j];
    if (a !== undefined && b !== undefined) {
      out[i] = b;
      out[j] = a;
    }
  }
  return out;
}

/** Two columns; tap one on each side; a pair that matches locks mint and stops being tappable. */
@Component({
  selector: 'hq-stop-match',
  imports: [IllustrationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="match">
      <p class="match__prompt">{{ stop().prompt }}</p>
      <div class="match__columns">
        <ul class="match__column">
          @for (pair of stop().pairs; track pair.id) {
            <li>
              <button
                type="button"
                class="match__tile"
                [class.match__tile--locked]="matched.has(pair.id)"
                [class.match__tile--selected]="picked() === pair.id"
                [disabled]="matched.has(pair.id)"
                [attr.aria-label]="describe(pair.left) + (matched.has(pair.id) ? ', matched' : '')"
                (click)="picked.set(pair.id)"
              >
                @if (pair.left.illustrationKey; as key) {
                  <hq-illustration [key]="key" size="sm" />
                }
                @if (pair.left.label; as label) {
                  <span>{{ label }}</span>
                }
              </button>
            </li>
          }
        </ul>

        <ul class="match__column">
          @for (pair of rights(); track pair.id) {
            <li>
              <button
                type="button"
                class="match__tile"
                [class.match__tile--locked]="matched.has(pair.id)"
                [disabled]="matched.has(pair.id)"
                [attr.aria-label]="describe(pair.right) + (matched.has(pair.id) ? ', matched' : '')"
                (click)="drop(pair.id)"
              >
                @if (pair.right.illustrationKey; as key) {
                  <hq-illustration [key]="key" size="sm" />
                }
                @if (pair.right.label; as label) {
                  <span>{{ label }}</span>
                }
              </button>
            </li>
          }
        </ul>
      </div>
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .match {
      @include child.child-stack(var(--hq-space-16));

      padding-inline: var(--hq-space-16);
    }

    .match__prompt {
      @include child.child-prompt;
    }

    .match__columns {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--hq-space-16);
      inline-size: 100%;
    }

    .match__column {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .match__tile {
      @include child.child-target;
      @include child.child-label;

      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-8);
      inline-size: 100%;
      min-block-size: calc(var(--hq-child-touch) + var(--hq-space-16));
      padding: var(--hq-space-8);
      border: none;
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-cream);
      color: var(--hq-child-ink);
    }

    .match__tile--locked {
      background: var(--hq-child-mint);
      opacity: 0.55;
    }

    .match__tile--selected {
      outline: var(--hq-child-rule) solid var(--hq-child-sun-deep);
      outline-offset: calc(var(--hq-child-rule) * -1);
    }
  `,
})
export class MatchStopComponent {
  readonly stop = input.required<MatchStop>();

  protected readonly matched = opened(() => this.stop().id);
  protected readonly picked = linkedSignal<string, string | null>({
    source: () => this.stop().id,
    computation: () => null,
  });
  protected readonly rights = computed<readonly MatchPair[]>(() =>
    shuffleBySeed(this.stop().pairs, this.stop().id),
  );

  protected describe(tile: Tile): string {
    return tile.label ?? tile.illustrationKey ?? '';
  }

  protected drop(id: string): void {
    const left = this.picked();
    if (left === null) return;
    if (left === id) this.matched.open(id);
    this.picked.set(null);
  }
}
