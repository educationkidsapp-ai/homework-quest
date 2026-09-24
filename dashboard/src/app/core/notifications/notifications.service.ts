import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';

export interface AppNotification {
  readonly id: string;
  readonly title: string;
  readonly message: string;
  readonly category: 'classes' | 'lessons' | 'chat' | 'system';
  readonly read: boolean;
  readonly createdAt: string;
  readonly link?: string;
}

const STORAGE_KEY_PREFIX = 'hq.notifications.';

const DEFAULT_NOTIFICATIONS: readonly AppNotification[] = [
  {
    id: 'notif-1',
    title: 'Attendance Reminder',
    message: 'Class 1A · Math has pending attendance for today.',
    category: 'classes',
    read: false,
    createdAt: new Date(Date.now() - 1000 * 60 * 15).toISOString(),
    link: '/teacher/classes/cls_1a?tab=attendance',
  },
  {
    id: 'notif-2',
    title: 'Lesson Published',
    message: 'Lesson "Counting & Cardinality" has been successfully published.',
    category: 'lessons',
    read: false,
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 2).toISOString(),
    link: '/teacher/lessons',
  },
  {
    id: 'notif-3',
    title: 'New Message from Parent',
    message: 'Adam\'s mother sent a question regarding homework.',
    category: 'chat',
    read: false,
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 4).toISOString(),
    link: '/teacher/chat',
  },
  {
    id: 'notif-4',
    title: 'New Class Assigned',
    message: 'You have been assigned to Grade 1 Section B (Science).',
    category: 'classes',
    read: true,
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24).toISOString(),
    link: '/teacher/classes',
  },
  {
    id: 'notif-5',
    title: 'System Update',
    message: 'EduManage dashboard updated with new theme and multi-subject support.',
    category: 'system',
    read: true,
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 48).toISOString(),
  },
];

@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly doc = inject(DOCUMENT);
  private readonly auth = inject(AuthService);

  private readonly items = signal<readonly AppNotification[]>([]);

  readonly notifications = this.items.asReadonly();
  readonly unreadCount = computed(() => this.items().filter((n) => !n.read).length);

  constructor() {
    effect(() => {
      const userId = this.auth.user()?.id;
      if (!userId) {
        this.items.set([]);
        return;
      }
      const stored = this.loadFromStorage(userId);
      if (stored && stored.length > 0) {
        this.items.set(stored);
      } else {
        this.items.set(DEFAULT_NOTIFICATIONS);
        this.saveToStorage(userId, DEFAULT_NOTIFICATIONS);
      }
    });
  }

  markAsRead(id: string): void {
    const userId = this.auth.user()?.id;
    this.items.update((list) =>
      list.map((item) => (item.id === id ? { ...item, read: true } : item)),
    );
    if (userId) this.saveToStorage(userId, this.items());
  }

  markAllAsRead(): void {
    const userId = this.auth.user()?.id;
    this.items.update((list) => list.map((item) => ({ ...item, read: true })));
    if (userId) this.saveToStorage(userId, this.items());
  }

  remove(id: string): void {
    const userId = this.auth.user()?.id;
    this.items.update((list) => list.filter((item) => item.id !== id));
    if (userId) this.saveToStorage(userId, this.items());
  }

  clearAll(): void {
    const userId = this.auth.user()?.id;
    this.items.set([]);
    if (userId) this.saveToStorage(userId, []);
  }

  private loadFromStorage(userId: string): readonly AppNotification[] | null {
    try {
      const raw = this.doc.defaultView?.localStorage.getItem(STORAGE_KEY_PREFIX + userId);
      return raw ? (JSON.parse(raw) as AppNotification[]) : null;
    } catch {
      return null;
    }
  }

  private saveToStorage(userId: string, data: readonly AppNotification[]): void {
    try {
      this.doc.defaultView?.localStorage.setItem(STORAGE_KEY_PREFIX + userId, JSON.stringify(data));
    } catch {
      // Storage unavailable
    }
  }
}
