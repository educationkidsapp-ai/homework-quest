import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';
import type { Role } from '../auth/auth.service';

export interface TourStep {
  /** The element to spotlight, marked in a template with `data-hq-tour="…"`. */
  readonly target: string;
  /** Translation keys under `tour.`. */
  readonly titleKey: string;
  readonly bodyKey: string;
}

const SEEN_PREFIX = 'hq.tour.';

/**
 * The four-step guided tour (§7, "Onboarding").
 *
 * Four, per role, on first sign-in only, and dismissible at any point. Four because a tour
 * is a promise about how long it will take, and the fourth step is always the profile menu —
 * where the language toggle and "Show me around" live, so the last thing it teaches is how to
 * see it again.
 *
 * "Seen" is a localStorage flag rather than a server field: it is per browser, it is not worth
 * a column, and the worst case of losing it is seeing a four-step tour twice.
 */
@Injectable({ providedIn: 'root' })
export class TourService {
  private readonly doc = inject(DOCUMENT);
  private readonly steps = signal<readonly TourStep[]>([]);
  private readonly index = signal(0);

  readonly running = computed(() => this.steps().length > 0);
  readonly step = computed<TourStep | null>(() => this.steps()[this.index()] ?? null);
  readonly position = computed(() => ({ index: this.index() + 1, total: this.steps().length }));

  /** First sign-in for this role in this browser: run it. Otherwise do nothing. */
  offer(role: Role): void {
    if (this.running() || this.seen(role)) return;
    this.start(role);
  }

  /** "Show me around" in the profile menu — always runs, whatever the flag says. */
  start(role: Role): void {
    this.index.set(0);
    this.steps.set(TOURS[role]);
  }

  next(): void {
    if (this.index() + 1 >= this.steps().length) this.finish();
    else this.index.update((value) => value + 1);
  }

  back(): void {
    this.index.update((value) => Math.max(0, value - 1));
  }

  /** Both "Skip" and finishing the last step mark it seen — it is not offered twice. */
  finish(role?: Role): void {
    if (role) this.remember(role);
    this.steps.set([]);
    this.index.set(0);
  }

  dismiss(role: Role): void {
    this.remember(role);
    this.finish();
  }

  private seen(role: Role): boolean {
    return this.storage()?.getItem(SEEN_PREFIX + role) === 'true';
  }

  private remember(role: Role): void {
    this.storage()?.setItem(SEEN_PREFIX + role, 'true');
  }

  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}

/** Each role is taught the four things that role's day starts with. */
const TOURS: Readonly<Record<Role, readonly TourStep[]>> = {
  ADMIN: [
    { target: 'nav', titleKey: 'tour.admin.nav.title', bodyKey: 'tour.admin.nav.body' },
    { target: 'switcher', titleKey: 'tour.admin.switcher.title', bodyKey: 'tour.admin.switcher.body' },
    { target: 'cards', titleKey: 'tour.admin.cards.title', bodyKey: 'tour.admin.cards.body' },
    { target: 'profile', titleKey: 'tour.profile.title', bodyKey: 'tour.profile.body' },
  ],
  TEACHER: [
    { target: 'nav', titleKey: 'tour.teacher.nav.title', bodyKey: 'tour.teacher.nav.body' },
    { target: 'cards', titleKey: 'tour.teacher.cards.title', bodyKey: 'tour.teacher.cards.body' },
    { target: 'classes', titleKey: 'tour.teacher.classes.title', bodyKey: 'tour.teacher.classes.body' },
    { target: 'profile', titleKey: 'tour.profile.title', bodyKey: 'tour.profile.body' },
  ],
  MANAGERIAL: [
    { target: 'nav', titleKey: 'tour.management.nav.title', bodyKey: 'tour.management.nav.body' },
    { target: 'cards', titleKey: 'tour.management.cards.title', bodyKey: 'tour.management.cards.body' },
    { target: 'needsYou', titleKey: 'tour.needsYou.title', bodyKey: 'tour.needsYou.body' },
    { target: 'profile', titleKey: 'tour.profile.title', bodyKey: 'tour.profile.body' },
  ],
  COORDINATOR: [
    { target: 'nav', titleKey: 'tour.coordinator.nav.title', bodyKey: 'tour.coordinator.nav.body' },
    { target: 'cards', titleKey: 'tour.coordinator.cards.title', bodyKey: 'tour.coordinator.cards.body' },
    { target: 'needsYou', titleKey: 'tour.needsYou.title', bodyKey: 'tour.needsYou.body' },
    { target: 'profile', titleKey: 'tour.profile.title', bodyKey: 'tour.profile.body' },
  ],
};
