import { Injectable, computed, inject } from '@angular/core';
import { AuthService, type Role } from './auth.service';

/**
 * Which namespace a signed-in staff account reads the same screens through.
 *
 * The server publishes one set of shapes three times — `/teacher/**` scoped to the caller's own
 * assignments, `/coordinator/**` (R3) to the sections carrying her subject, `/management/**`
 * (RM1) to every section of her department — and the dashboard draws them with one component
 * each. This is the single place that turns a role into that choice, so a screen never asks
 * "am I a coordinator?" and a fourth reader added later is one line here rather than a new
 * branch in six services.
 */
export type StaffArea = 'teacher' | 'coordinator' | 'management';

/** The URL every link out of a shared screen belongs to — a child, a lesson, an exam. */
export const AREA_BASE: Readonly<Record<StaffArea, string>> = {
  teacher: '/teacher',
  coordinator: '/coordinator',
  management: '/management',
};

/**
 * An ADMIN reads the teacher's routes, which is what she has always done — written as two named
 * roles rather than as a list of the admin-side ones so that the answer before `/me` has landed
 * stays what it was, and a fifth role does not silently acquire somebody else's namespace.
 */
export function staffAreaOf(role: Role | null): StaffArea {
  if (role === 'COORDINATOR') return 'coordinator';
  if (role === 'MANAGERIAL') return 'management';
  return 'teacher';
}

@Injectable({ providedIn: 'root' })
export class StaffAreaService {
  private readonly auth = inject(AuthService);

  readonly area = computed(() => staffAreaOf(this.auth.role()));

  /**
   * `/me` has landed, so which namespace to read is settled.
   *
   * Every resource on a shared screen waits for this. Fired a tick early — a bookmark straight
   * onto `/management/gradebook`, a hard refresh — the read goes to `/teacher/**` and comes back
   * 403: a red band, then the right request a moment later.
   */
  readonly ready = computed(() => this.auth.role() !== null);

  readonly base = computed(() => AREA_BASE[this.area()]);

  /** Neither supervisor holds a write on anything the shared screens draw (DR2, RM1). */
  readonly readOnly = computed(() => this.area() !== 'teacher');
}
