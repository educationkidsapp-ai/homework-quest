/* hq-flag: none (shell) — every account has a profile; there is no school that does not. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeacherApi, apiErrorOf } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import {
  ButtonComponent,
  CardComponent,
  DialogComponent,
  InputComponent,
  PageComponent,
  TextareaComponent,
  ToastComponent,
} from '../../ui';

/**
 * Profile (§6, Shared screen 3): name, photo, password, and a word to the coordinator.
 *
 * **Name and photo are read-only here.** The contract has `PATCH /admin/users/{id}` — an
 * Admin editing someone — and no self-edit: a teacher cannot change her own display name or
 * photo through the API as it stands. Showing the fields disabled says what the account holds
 * and who can change it, which is truer than a form whose Save would 403. The gap is reported
 * to the backend worker rather than papered over with a hand-written call.
 *
 * **U1 item 2 took three things off this screen.** The guided tour ("Show me around") was a
 * second way into something the header's own menu offers, and the language card was a third
 * place to change a language the header changes in one click — a setting with two homes is a
 * setting people look for in the wrong one. What is left is what only this screen has.
 *
 * **Message to coordinator** is the one action here. It is `POST /teacher/messages/coordinator`
 * (U1 item 2), which writes a notification to every coordinator of her school: there was no
 * path for a teacher writing *to* the school — questions go to children, announcements to
 * parents, chat is per child — so the smallest new endpoint carries it. A send that worked says
 * so in a green strip; a send that failed says why in the red band, as every failure here does.
 */
@Component({
  selector: 'hq-profile-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    TextareaComponent,
    ButtonComponent,
    DialogComponent,
    ToastComponent,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'profile.title' | transloco" [subtitle]="auth.user()?.email ?? null">
      <div class="profile">
        <hq-card [title]="'profile.details' | transloco">
          <div class="profile__fields">
            <hq-input [label]="'auth.displayName' | transloco" [value]="displayName()" [disabled]="true" />
            <hq-input
              [label]="'profile.photoUrl' | transloco"
              [value]="photoUrl()"
              [disabled]="true"
              [hint]="'profile.managedByAdmin' | transloco"
            />
            <hq-input [label]="'profile.role' | transloco" [value]="roleLabel()" [disabled]="true" />
          </div>
        </hq-card>

        <hq-card [title]="'profile.security' | transloco">
          <div class="profile__fields">
            <p class="profile__hint">{{ 'profile.passwordHint' | transloco }}</p>
            <a class="hq-linkbutton profile__change" routerLink="/change-password">{{
              'auth.change.title' | transloco
            }}</a>
          </div>
        </hq-card>

        @if (isTeacher()) {
          <hq-card [title]="'profile.coordinator.title' | transloco">
            <div class="profile__fields">
              <p class="profile__hint">{{ 'profile.coordinator.hint' | transloco }}</p>
              <hq-button class="profile__change" variant="primary" (pressed)="openMessage()">
                {{ 'profile.coordinator.action' | transloco }}
              </hq-button>
            </div>
          </hq-card>
        }
      </div>

      <hq-toast
        tone="success"
        [open]="sent()"
        [message]="'profile.coordinator.sent' | transloco"
        (expired)="sent.set(false)"
      />

      <hq-dialog
        [(open)]="messageOpen"
        [sheet]="true"
        [title]="'profile.coordinator.title' | transloco"
        [confirmLabel]="'profile.coordinator.send' | transloco"
        [confirmDisabled]="message().trim().length === 0 || messageLeft() < 0"
        [loading]="sending()"
        (confirmed)="send()"
      >
        <!--
          The limit is the server's, named once: NotificationService.BODY_MAX, which is what the
          message becomes. The textarea stops at it and the hint counts down to it, so nobody
          learns about it from a 400 — and nothing is clipped behind her back.
        -->
        <hq-textarea
          [label]="'profile.coordinator.label' | transloco"
          [required]="true"
          [rows]="6"
          [maxLength]="messageMax"
          [placeholder]="'profile.coordinator.placeholder' | transloco"
          [hint]="'profile.coordinator.counter' | transloco: { left: messageLeft(), max: messageMax }"
          [value]="message()"
          (valueChange)="message.set($event)"
        />
      </hq-dialog>
    </hq-page>
  `,
  styles: `
    .profile {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-grid-gap);
    }

    .profile__fields {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .profile__hint {
      color: var(--hq-color-ink-soft);
    }

    // The link is a control, so it wears the secondary box; it should not stretch the column.
    .profile__change {
      align-self: flex-start;
    }
  `,
})
export class ProfilePage {
  private readonly transloco = inject(TranslocoService);
  private readonly teacherApi = inject(TeacherApi);
  private readonly band = inject(BandService);
  private readonly lang = activeLang();

  protected readonly auth = inject(AuthService);

  protected readonly displayName = computed(() => this.auth.user()?.displayName ?? '');
  protected readonly photoUrl = computed(() => this.auth.user()?.photoUrl ?? '');
  protected readonly isTeacher = computed(() => this.auth.role() === 'TEACHER');
  protected readonly roleLabel = computed(() => {
    this.lang();
    const role = this.auth.role();
    return role === null ? '' : this.transloco.translate(`role.${role}`);
  });

  // ---- a word to the coordinator -------------------------------------------------------------

  protected readonly messageOpen = signal(false);
  protected readonly message = signal('');
  /** `NotificationService.BODY_MAX`: the message becomes a notification body, so that is the limit. */
  protected readonly messageMax = 500;
  protected readonly messageLeft = computed(() => this.messageMax - this.message().length);
  protected readonly sending = signal(false);
  /** "Sent to your coordinator", in green, for the four seconds the strip lives. */
  protected readonly sent = signal(false);

  protected openMessage(): void {
    this.message.set('');
    this.messageOpen.set(true);
  }

  protected send(): void {
    const body = this.message().trim();
    if (!body || this.sending()) return;
    this.sending.set(true);
    this.teacherApi.messageCoordinator({ body }).subscribe({
      next: () => {
        this.sending.set(false);
        this.messageOpen.set(false);
        this.message.set('');
        this.sent.set(true);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        // The sheet stays open with her words in it: a failure that also loses the message is two
        // failures. The band says what the server said.
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
