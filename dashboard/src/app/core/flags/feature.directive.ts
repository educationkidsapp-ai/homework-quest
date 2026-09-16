import { Directive, TemplateRef, ViewContainerRef, computed, effect, inject, input } from '@angular/core';
import { FlagService } from './flag.service';

/**
 * `*hqFeature="'complaints'"` — renders its content only while that flag is on for the school
 * in scope.
 *
 * The dashboard half of §4's three-place rule (server 404s, dashboard hides the item, app
 * hides the screen). Flipping a flag in the Admin matrix re-reads the map and the item
 * appears or disappears without a rebuild, which is the point of the whole mechanism.
 */
@Directive({ selector: '[hqFeature]' })
export class FeatureDirective {
  private readonly template = inject<TemplateRef<unknown>>(TemplateRef);
  private readonly container = inject(ViewContainerRef);
  private readonly flags = inject(FlagService);

  readonly hqFeature = input.required<string>();

  private readonly on = computed(() => this.flags.isOn(this.hqFeature()));

  constructor() {
    let rendered = false;
    effect(() => {
      const on = this.on();
      if (on === rendered) return;
      rendered = on;
      if (on) this.container.createEmbeddedView(this.template);
      else this.container.clear();
    });
  }
}
