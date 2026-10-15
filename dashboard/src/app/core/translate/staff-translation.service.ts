import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import {
  LanguageBridgeApi,
  TranslateRequestKindEnum,
  TranslateRequestLangEnum,
  type Translation,
} from '../../api';
import { BandService } from '../band/band.service';
import { FLAGS, FlagService } from '../flags/flag.service';
import { silentErrors } from '../http/error.interceptor';
import { activeLang } from '../i18n/active-lang';

/** The two sources a member of staff may ask `POST /staff/translate` about (feature 29). */
export type StaffSource = TranslateRequestKindEnum.CHAT | TranslateRequestKindEnum.COMPLAINT;

/**
 * Feature 29's staff half: one parent message, read in the language this dashboard is in.
 *
 * The owner's rule decides the whole shape — *"the original text is always stored and shown as
 * the record; translations are cached per language and marked as machine-translated"*. So this
 * service never touches a message: it holds translations **beside** them, keyed by
 * message and language, and the screen draws one under the untouched original with the
 * machine-translation label on it. "Show original only" drops it from {@link shown} and the
 * bubble is exactly what it was before she asked.
 *
 * The target language is the dashboard's own (`en` or `ar`), not a choice of its own: a member
 * of staff reading an Arabic screen wants the parent's Urdu in Arabic, and the button says so.
 *
 * **One call per message and language, for the session.** The server caches too (V51's
 * `translations`, unique on source + language + hash), but a second click should not reach it at
 * all — the answer is already on this screen. A language change makes a different key, so Arabic
 * and English never share a row here either.
 *
 * Failures are the red band, never a toast (§7): the request is sent with
 * {@link silentErrors} so the sentence is ours and in her language rather than the server's
 * English, and 429 — the spec's sixty an hour — says what to do about it.
 */
@Injectable({ providedIn: 'root' })
export class StaffTranslationService {
  private readonly api = inject(LanguageBridgeApi);
  private readonly band = inject(BandService);
  private readonly flags = inject(FlagService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** §4's dashboard half: with the switch off there is no action at all, as there is no route. */
  readonly on = computed(() => this.flags.isOn(FLAGS.languageBridge));

  /** What she reads the dashboard in, which is what a translation is asked for in. */
  readonly target = computed<TranslateRequestLangEnum>(() =>
    this.lang() === 'ar' ? TranslateRequestLangEnum.AR : TranslateRequestLangEnum.EN,
  );

  /** `kind:id:lang` → the answer, kept for the session. */
  private readonly cache = new Map<string, Translation>();
  private readonly shown = signal<ReadonlySet<string>>(new Set());
  private readonly working = signal<ReadonlySet<string>>(new Set());

  /** The translation drawn under this message right now, or `null` while the original stands alone. */
  translation(kind: StaffSource, id: string): Translation | null {
    const key = this.keyOf(kind, id);
    return this.shown().has(key) ? (this.cache.get(key) ?? null) : null;
  }

  /** Her click is still in the air: the action says so rather than looking like it did nothing. */
  busy(kind: StaffSource, id: string): boolean {
    return this.working().has(this.keyOf(kind, id));
  }

  /** Show the translation — from this session's cache when it is already here, else fetch it once. */
  translate(kind: StaffSource, id: string): void {
    const key = this.keyOf(kind, id);
    if (this.working().has(key)) return;
    if (this.cache.has(key)) {
      this.reveal(key);
      return;
    }
    this.working.update((keys) => new Set(keys).add(key));
    this.api
      .translateForStaff({ kind, id, lang: this.target() }, 'body', false, { context: silentErrors() })
      .subscribe({
        next: (translation) => {
          this.cache.set(key, translation);
          this.settle(key);
          this.reveal(key);
        },
        error: (error: unknown) => {
          this.settle(key);
          this.band.fail(this.transloco.translate<string>(reasonKeyOf(error)));
        },
      });
  }

  /** "Show original only": the addition leaves, the message was never changed, the answer is kept. */
  hide(kind: StaffSource, id: string): void {
    const key = this.keyOf(kind, id);
    this.shown.update((keys) => {
      const next = new Set(keys);
      next.delete(key);
      return next;
    });
  }

  private reveal(key: string): void {
    this.shown.update((keys) => new Set(keys).add(key));
  }

  private settle(key: string): void {
    this.working.update((keys) => {
      const next = new Set(keys);
      next.delete(key);
      return next;
    });
  }

  private keyOf(kind: StaffSource, id: string): string {
    return `${kind}:${id}:${this.target()}`;
  }
}

/** Her sentence for a refused translation: the rate limit by name, everything else the fallback. */
export function reasonKeyOf(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.status === 429) return 'translate.tooMany';
  return 'translate.failed';
}
