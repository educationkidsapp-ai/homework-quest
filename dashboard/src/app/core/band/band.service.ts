import { Injectable, signal } from '@angular/core';
import type { BandVariant } from '../../ui';

export interface BandMessage {
  /** Already-resolved text — the server's `ApiError.message`, or a translated sentence. */
  readonly message: string;
  /** Translation key for the band's heading, resolved by the shell. */
  readonly titleKey?: string;
  readonly variant?: BandVariant;
}

/**
 * The red band, as a service.
 *
 * §7: "failures roll back with the red band, never a toast." A toast is dismissed by time,
 * which means a failure can disappear before it has been read, and it appears in a corner
 * rather than next to what failed. The band stays until it is dismissed or the next
 * navigation replaces it, and it pushes the page down rather than covering it.
 *
 * One band at a time by design: a second failure replaces the first rather than stacking,
 * because two red bands are two things to read and no indication of which to act on.
 */
@Injectable({ providedIn: 'root' })
export class BandService {
  private readonly message = signal<BandMessage | null>(null);

  readonly current = this.message.asReadonly();

  show(message: BandMessage): void {
    this.message.set(message);
  }

  /** A server failure: its own sentence under a translated heading. */
  fail(message: string, titleKey = 'band.failed'): void {
    this.message.set({ message, titleKey, variant: 'error' });
  }

  dismiss(): void {
    this.message.set(null);
  }
}
