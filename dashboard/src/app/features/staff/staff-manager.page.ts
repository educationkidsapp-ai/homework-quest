import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { ChatApi, CoordinatorChatApi } from '../../api';
import { StaffAreaService } from '../../core/auth/staff-area';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { type StaffContact, type StaffContactRow, contactRow } from './staff-contacts';

/**
 * **Manager** — the owner's list of 2026-10-01, items (a) and (b).
 *
 * A teacher and a coordinator both report to a department manager and neither had anywhere to see
 * who that is. The conversation existed (MG1 gave the teacher `/teacher/chat/staff-threads`, RM2
 * gave the coordinator `POST /coordinator/chat/threads`) but only the manager could start it: a
 * thread she had not opened was invisible, and the phone number the owner actually wanted on a
 * laptop screen was nowhere at all.
 *
 * **One component, two namespaces**, the way Announcements and Messages are: `StaffAreaService`
 * says which of `GET /teacher/managers` and `GET /coordinator/managers` to read, and the same
 * choice picks which `staff-threads` route opens the conversation. Both answer the same shape,
 * both are idempotent, and a second copy of a directory card would drift within a phase.
 *
 * **The card is the screen.** A department has one manager, or two while a handover is in
 * progress; a table of one row with six columns of nothing is what this screen must not be. The
 * phone is a `tel:` and the address a `mailto:` because she is reading this beside her phone,
 * which is item (a) in as many words.
 *
 * **Message is a navigation, not a composer** — `StaffThreadService`'s reasoning, one area over:
 * the route answers the thread that exists or opens one, so she lands on her own Messages screen
 * with everything already said on it, which is the context the message she is about to write
 * depends on.
 */
@Component({
  selector: 'hq-staff-manager-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    PageComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.manager' | transloco" [subtitle]="'staff.manager.subtitle' | transloco">
      <!--
        The chat flag, on the screen as well as on the route: GET /teacher/managers,
        GET /coordinator/managers and both staff-thread routes carry it (runbook, "The teacher's
        staff threads"), so with chat off there is no directory to read and no way to write.
      -->
      <div *hqFeature="'chat'">
        @if (failed()) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            (dismissed)="failed.set(false)"
          >
            {{ 'staff.messageFailed' | transloco }}
          </hq-band>
        }

        @if (people.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="4" [label]="'ui.loading' | transloco" />
        } @else if (people.error()) {
          <hq-coordinator-read-failed (retry)="people.reload()" />
        } @else if (rows().length === 0) {
          <hq-empty-state
            [message]="'staff.manager.empty' | transloco"
            [detail]="'staff.manager.emptyHint' | transloco"
          />
        } @else {
          <ul class="staff-cards">
            @for (row of rows(); track row.userId) {
              <li>
                <hq-card [title]="row.name">
                  <ul class="staff-card__job">
                    @for (line of row.job; track line) {
                      <li>{{ line }}</li>
                    }
                  </ul>

                  <dl class="staff-card__contact">
                    @if (row.phone) {
                      <dt>{{ 'staff.phone' | transloco }}</dt>
                      <dd>
                        <a [href]="'tel:' + row.phone" dir="ltr">{{ row.phone }}</a>
                      </dd>
                    }
                    @if (row.email) {
                      <dt>{{ 'staff.email' | transloco }}</dt>
                      <dd>
                        <a [href]="'mailto:' + row.email" dir="ltr">{{ row.email }}</a>
                      </dd>
                    }
                  </dl>

                  <div card-footer class="staff-card__footer">
                    <hq-button
                      variant="primary"
                      [loading]="pending() === row.userId"
                      (pressed)="message(row)"
                    >
                      {{ 'staff.message' | transloco }}
                    </hq-button>
                  </div>
                </hq-card>
              </li>
            }
          </ul>
        }
      </div>
    </hq-page>
  `,
  styles: `
    /* A department has one manager, two during a handover — a stack of readable cards rather
       than a grid of one, capped so the contact lines do not run the width of the content column. */
    .staff-cards {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
      max-inline-size: calc(var(--hq-size-stop-list-width) * 2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .staff-card__job,
    .staff-card__contact {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .staff-card__job {
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-font-body-size);
    }

    .staff-card__contact {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--hq-space-4) var(--hq-space-12);
      margin-block-start: var(--hq-space-12);
    }

    .staff-card__contact dt {
      color: var(--hq-color-ink-soft);
    }

    .staff-card__contact dd {
      margin: 0;
    }

    .staff-card__footer {
      display: flex;
      justify-content: flex-end;
    }
  `,
})
export class StaffManagerPage {
  private readonly teacher = inject(ChatApi);
  private readonly coordinator = inject(CoordinatorChatApi);
  private readonly staff = inject(StaffAreaService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** The id of the manager whose thread is opening, so two cards cannot both spin. */
  protected readonly pending = signal('');
  protected readonly failed = signal(false);

  protected readonly people = rxResource<readonly StaffContact[], 'teacher' | 'coordinator' | undefined>({
    // `StaffAreaService.ready` is what keeps the first read off `/teacher/**` for a coordinator
    // who arrived on a bookmark: a request fired before `/me` lands answers 403 and a red band.
    params: () => {
      if (!this.staff.ready()) return undefined;
      const area = this.staff.area();
      return area === 'coordinator' ? 'coordinator' : 'teacher';
    },
    stream: ({ params }) =>
      params === 'coordinator' ? this.coordinator.coordinatorManagers() : this.teacher.teacherManagers(),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly StaffContactRow[]>(() => {
    this.lang();
    return this.people
      .value()
      .map((person) => contactRow(this.transloco, person))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected message(row: StaffContactRow): void {
    if (row.userId === '' || this.pending() !== '') return;
    this.pending.set(row.userId);
    this.failed.set(false);
    const coordinator = this.staff.area() === 'coordinator';
    const request = coordinator
      ? this.coordinator.coordinatorStaffThread({ managerUserId: row.userId })
      : this.teacher.teacherStaffThread({ managerUserId: row.userId });
    request
      .pipe(
        tap((thread) => {
          this.pending.set('');
          // Her own Messages screen: `/teacher/chat` kept C1's path, the coordinator's is
          // `/coordinator/messages` (`core/notifications/notification-target.ts` says the same).
          void this.router.navigate([coordinator ? '/coordinator/messages' : '/teacher/chat'], {
            queryParams: { thread: thread.id },
          });
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
