import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, of, tap } from 'rxjs';
import { type StaffThreadRequest, ManagementChatApi } from '../../api';

/**
 * **"Message" on a row** (MH2 items 1–3): open the thread with that person, then show it.
 *
 * `POST /management/chat/threads` is idempotent by design — it answers the existing conversation
 * with that coordinator, teacher or parent, or opens one — so the button is a navigation rather
 * than a compose sheet: she lands on `/management/messages?thread=<id>` with whatever was said
 * before already on screen, which is the context the message she is about to write depends on.
 *
 * Three screens press it (Coordinators, Teachers, Children) and none of them holds a second reason
 * to know about chat, so the request, the pending row and the one failure this can have live here
 * instead of three times over.
 *
 * `pending` is the row's own id, not a boolean: two rows must not both show a spinner because one
 * of them was clicked. `failed` is a **child with no registered parent** and nothing else — the
 * error interceptor has already put every other refusal in the red band, while 404 `no_parent` is
 * a sentence about that row rather than about the request, and the button is disabled for it
 * anyway (`parentId === null`).
 */
@Injectable({ providedIn: 'root' })
export class StaffThreadService {
  private readonly api = inject(ManagementChatApi);
  private readonly router = inject(Router);

  /** The id of the person whose thread is being opened, or `''`. */
  readonly pending = signal('');
  readonly failed = signal(false);

  open(rowId: string, body: StaffThreadRequest): void {
    if (rowId === '' || this.pending() !== '') return;
    this.pending.set(rowId);
    this.failed.set(false);
    this.api
      .managementStaffThread(body)
      .pipe(
        tap((thread) => {
          this.pending.set('');
          void this.router.navigate(['/management/messages'], { queryParams: { thread: thread.id } });
        }),
        catchError(() => {
          this.pending.set('');
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }
}
