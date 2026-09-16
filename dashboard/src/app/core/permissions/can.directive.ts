import { Directive, TemplateRef, ViewContainerRef, computed, effect, inject, input } from '@angular/core';
import { PermissionService } from './permission.service';

/**
 * `*hqCan="'lesson.publish'"` — renders its content only when the account holds that key.
 *
 * The mirror of `@PreAuthorize("@permit.has('lesson.publish')")` on the server, reading the
 * same `permissions.json` through `GET /me/permissions`. Structural rather than a `hidden`
 * binding so the element is genuinely absent: a disabled-looking button that a screen reader
 * still announces is worse than no button.
 */
@Directive({ selector: '[hqCan]' })
export class CanDirective {
  private readonly template = inject<TemplateRef<unknown>>(TemplateRef);
  private readonly container = inject(ViewContainerRef);
  private readonly permissions = inject(PermissionService);

  readonly hqCan = input.required<string>();

  private readonly allowed = computed(() => this.permissions.can(this.hqCan()));

  constructor() {
    let rendered = false;
    effect(() => {
      const allowed = this.allowed();
      if (allowed === rendered) return;
      rendered = allowed;
      if (allowed) this.container.createEmbeddedView(this.template);
      else this.container.clear();
    });
  }
}
