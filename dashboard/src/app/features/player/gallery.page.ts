/* hq-flag: none (shell) — a placeholder for the web player N3 builds; there is nothing here to
   gate yet, and the flag that will gate the player belongs with the player. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Location } from '@angular/common';
import { TranslocoPipe } from '@jsverse/transloco';
import { EmptyStateComponent, PageComponent } from '../../ui';

/**
 * "Preview as child" (teacher-flow §8) — the destination, not the player.
 *
 * The route exists now so the button on a ready lesson leads somewhere honest instead of being
 * greyed out with a tooltip: `?lesson=<id>` is already the address N3's player will answer on,
 * so nothing that links here has to change when the player lands. It is deliberately not in the
 * nav rail — a teacher reaches it from a lesson, never from the menu.
 */
@Component({
  selector: 'hq-player-gallery-page',
  imports: [PageComponent, EmptyStateComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'player.gallery.title' | transloco">
      <hq-empty-state
        [message]="'player.gallery.comingIn' | transloco"
        [actionLabel]="'player.gallery.back' | transloco"
        (action)="back()"
      />
    </hq-page>
  `,
})
export class PlayerGalleryPage {
  private readonly location = inject(Location);

  /** Back, not Home: the one place worth returning to is the lesson she pressed Preview on. */
  protected back(): void {
    this.location.back();
  }
}
