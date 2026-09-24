export interface ChatAttachment {
  readonly id: string;
  readonly name: string;
  readonly size: string;
  readonly type: 'image' | 'pdf';
  readonly dataUrl: string;
}

const STORAGE_PREFIX = 'hq_chat_att_';

export class ChatAttachmentStore {
  private static readonly memoryCache = new Map<string, ChatAttachment>();

  static save(attachment: ChatAttachment): void {
    this.memoryCache.set(attachment.id, attachment);
    try {
      if (typeof window !== 'undefined' && window.sessionStorage) {
        window.sessionStorage.setItem(STORAGE_PREFIX + attachment.id, JSON.stringify(attachment));
      }
    } catch {
      // Session storage full or unavailable
    }
  }

  static get(id: string): ChatAttachment | null {
    if (this.memoryCache.has(id)) {
      return this.memoryCache.get(id)!;
    }
    try {
      if (typeof window !== 'undefined' && window.sessionStorage) {
        const item = window.sessionStorage.getItem(STORAGE_PREFIX + id);
        if (item) {
          const parsed = JSON.parse(item) as ChatAttachment;
          this.memoryCache.set(id, parsed);
          return parsed;
        }
      }
    } catch {
      // Ignore
    }
    return null;
  }
}
