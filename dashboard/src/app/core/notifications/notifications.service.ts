import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { NotificationsApi, type NotificationView } from '../../api';
import { AuthService } from '../auth/auth.service';

/** How many the bell's dropdown shows; the page asks for a page's worth. */
const RECENT = 4;
const PAGE_SIZE = 50;
/** The socket is the channel (D26); this is only what covers a socket that is not open. */
const FALLBACK_POLL_MS = 30_000;

export type NotificationKind = NotificationView['kind'];

/** `notifications.kind.lesson.ready.title` and `.body` — EN/AR, from the kind alone. */
export function titleKeyOf(kind: NotificationKind): string {
  return `notifications.kind.${kind}.title`;
}

export function bodyKeyOf(kind: NotificationKind): string {
  return `notifications.kind.${kind}.body`;
}

/**
 * The bell, over E2's rows and E2's socket frame.
 *
 * Until E3 this was five hard-coded objects in `localStorage` that re-seeded themselves when
 * emptied — a demo. It is now `GET /me/notifications`, its unread count, and the `notification`
 * frame `ChatService` hands over. Nothing is cached anywhere but in these signals: a row the
 * server has not written does not exist.
 *
 * **Nothing is replayed.** The socket sends a frame once; a client that was away during it is
 * simply behind. So every (re)connect refetches the count (and the list, if it is open), and
 * when there is no socket at all the count is polled every 30 s — enough for a bell, and two
 * orders of magnitude cheaper than the lesson poll it replaces.
 *
 * `title` and `body` off the wire are English fallbacks; the screens translate from `kind`
 * ({@link titleKeyOf}) and only `lesson.failed` shows the server's `body`, which is the reason
 * the pipeline gave.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly api = inject(NotificationsApi);
  private readonly auth = inject(AuthService);
  private readonly doc = inject(DOCUMENT);

  private readonly items = signal<readonly NotificationView[]>([]);
  private readonly unread = signal(0);

  readonly notifications = this.items.asReadonly();
  readonly unreadCount = this.unread.asReadonly();
  /** What the bell's dropdown shows. */
  readonly recent = computed(() => this.items().slice(0, RECENT));
  readonly loading = signal(false);

  /** The one that just arrived, for the app-wide toast. The shell clears it when it expires. */
  readonly toast = signal<NotificationView | null>(null);
  /** Set by `ChatService`; false turns the fallback poll on. */
  readonly socketOpen = signal(false);

  constructor() {
    effect((onCleanup) => {
      if (!this.auth.signedIn()) {
        this.items.set([]);
        this.unread.set(0);
        this.toast.set(null);
        return;
      }
      // The socket's own (re)connect refetches the count (`onSocketOpen`), so this effect asks
      // only while there is no socket to ask for it — it re-runs when `socketOpen` flips, and
      // fetching here too made every connect ask twice.
      if (this.socketOpen()) return;
      this.refreshUnreadCount();
      const timer = setInterval(() => this.refreshUnreadCount(), FALLBACK_POLL_MS);
      onCleanup(() => clearInterval(timer));
    });
  }

  refreshUnreadCount(): void {
    this.api
      .unreadNotificationCount()
      .pipe(
        tap((result) => this.unread.set(result.count)),
        catchError(() => of(null)),
      )
      .subscribe();
  }

  /** The bell's dropdown and the page read the same list; the bell only shows the first four. */
  refresh(): void {
    if (!this.auth.signedIn()) return;
    this.loading.set(true);
    this.api
      .listNotifications(undefined, PAGE_SIZE)
      .pipe(
        tap((list) => {
          this.items.set(list);
          this.unread.set(list.filter((item) => item.readAt === undefined).length);
          this.loading.set(false);
        }),
        catchError(() => {
          this.loading.set(false);
          return of([]);
        }),
      )
      .subscribe();
  }

  markRead(id: string): void {
    const already = this.items().find((item) => item.id === id)?.readAt !== undefined;
    this.applyRead(id);
    this.api
      .markNotificationRead(id)
      .pipe(
        tap((updated) => this.replace(updated)),
        catchError(() => {
          if (!already) this.refreshUnreadCount();
          return of(null);
        }),
      )
      .subscribe();
  }

  markAllRead(): void {
    const now = Date.now();
    this.items.update((list) =>
      list.map((item) => (item.readAt === undefined ? { ...item, readAt: now } : item)),
    );
    this.unread.set(0);
    this.api
      .markAllNotificationsRead()
      .pipe(
        tap((result) => this.unread.set(result.count)),
        catchError(() => {
          this.refreshUnreadCount();
          return of(null);
        }),
      )
      .subscribe();
  }

  /** A `notification` frame off the socket: straight into the list, the badge and the toast. */
  receive(notification: NotificationView): void {
    this.items.update((list) =>
      list.some((item) => item.id === notification.id) ? list : [notification, ...list],
    );
    if (notification.readAt === undefined) this.unread.update((count) => count + 1);
    this.toast.set(notification);
  }

  /** (Re)connect: nothing was replayed while we were away, so ask again. */
  onSocketOpen(): void {
    this.socketOpen.set(true);
    this.refreshUnreadCount();
    if (this.items().length > 0) this.refresh();
  }

  onSocketClosed(): void {
    this.socketOpen.set(false);
  }

  // ---- the browser's own notification ---------------------------------------------------

  /** Worth offering "Notify me" only while the answer is still open. */
  canAskPermission(): boolean {
    const view = this.doc.defaultView;
    return !!view && 'Notification' in view && view.Notification.permission === 'default';
  }

  /**
   * Asked from a click and never from an effect: a permission prompt that appears because a
   * page rendered is the reason browsers now bury the prompt for the whole origin.
   */
  async askPermission(): Promise<void> {
    const view = this.doc.defaultView;
    if (!view || !('Notification' in view)) return;
    await view.Notification.requestPermission();
  }

  /**
   * The desktop notification, and only for the teacher who is looking at something else — a
   * banner over the tab she is already reading is noise, the toast has her attention.
   */
  notifyIfHidden(title: string, body: string): void {
    const view = this.doc.defaultView;
    if (!view || !('Notification' in view)) return;
    if (!this.doc.hidden || view.Notification.permission !== 'granted') return;
    try {
      new view.Notification(title, { body, icon: '/favicon.ico' });
    } catch {
      // Some clients declare `Notification` and refuse to construct one. Nothing is lost.
    }
  }

  private applyRead(id: string): void {
    const now = Date.now();
    let changed = false;
    this.items.update((list) =>
      list.map((item) => {
        if (item.id !== id || item.readAt !== undefined) return item;
        changed = true;
        return { ...item, readAt: now };
      }),
    );
    if (changed) this.unread.update((count) => Math.max(0, count - 1));
  }

  private replace(updated: NotificationView): void {
    this.items.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
  }
}
