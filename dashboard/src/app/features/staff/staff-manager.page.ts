import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { ChatApi, CoordinatorChatApi, type StaffContact } from '../../api';
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
import { type StaffContactRow, contactRow } from './staff-contacts';

/** Which of the two directories a route is, and which of the three reads answers it. */
type StaffDirectory = 'manager' | 'coordinators';
type StaffSource = 'teacher-managers' | 'teacher-coordinators' | 'coordinator-managers';

/**
 * **Manager** and **Coordinators** — the owner's list of 2026-10-01, items (a) and (b).
 *
 * A teacher and a coordinator both report upwards and neither had anywhere to see who that is. The
 * conversation existed (MG1 gave the teacher `/teacher/chat/staff-threads`, RM2 gave the
 * coordinator `POST /coordinator/chat/threads`) but only the supervisor could start it: a thread
 * she had not opened was invisible, and the phone number the owner actually wanted on a laptop
 * screen was nowhere at all.
 *
 * **One component, three rows.** The route's `screenId` says which directory this is — the
 * teacher's Coordinators, or either role's Manager — and `StaffAreaService` says which namespace to
 * read it through (`area.routes.ts`, the way `staff-accounts.page.ts` serves two admin rows). All
 * three answer T1's `StaffContact`, and all three open a thread that is idempotent per pair, so
 * the difference between them is two lines of `source()` rather than a second screen.
 *
 * Cards rather than a table: a department has one manager, two during a handover, and a teacher has
 * a coordinator per subject she teaches. A table of one row with six empty columns is what this
 * screen must not be.
 *
 * **Message is a navigation, not a composer** — `StaffThreadService`'s reasoning, one area over:
 * the route answers the thread that exists or opens one, so she lands on her own Messages screen
 * with everything already said on it.
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
    <hq-page [title]="titleKey() | transloco" [subtitle]="subtitleKey() | transloco">
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
          <hq-empty-state [message]="emptyKey() | transloco" [detail]="emptyHintKey() | transloco" />
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
      margin-inline-end: 20px;
      margin-bottom: 20px;
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

  /**
   * Which directory this is. `coordinators` is the teacher's only — a coordinator's supervisor is
   * her manager, and `GET /coordinator/coordinators` is not a thing.
   */
  private readonly of: StaffDirectory =
    inject(ActivatedRoute).snapshot.data['screenId'] === 'coordinators' ? 'coordinators' : 'manager';

  /** The id of the person whose thread is opening, so two cards cannot both spin. */
  protected readonly pending = signal('');
  protected readonly failed = signal(false);

  protected readonly titleKey = computed(() =>
    this.of === 'coordinators' ? 'nav.coordinators' : 'nav.manager',
  );
  protected readonly subtitleKey = computed(() => `staff.${this.of}.subtitle`);
  protected readonly emptyKey = computed(() => `staff.${this.of}.empty`);
  protected readonly emptyHintKey = computed(() => `staff.${this.of}.emptyHint`);

  protected readonly people = rxResource<readonly StaffContact[], StaffSource | undefined>({
    // `StaffAreaService.ready` is what keeps the first read off `/teacher/**` for a coordinator
    // who arrived on a bookmark: a request fired before `/me` lands answers 403 and a red band.
    params: () => (this.staff.ready() ? this.source() : undefined),
    stream: ({ params }) => {
      switch (params) {
        case 'coordinator-managers':
          return this.coordinator.coordinatorManagers();
        case 'teacher-coordinators':
          return this.teacher.teacherCoordinators();
        default:
          return this.teacher.teacherManagers();
      }
    },
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
    const source = this.source();
    // **Exactly one id.** `OpenStaffThreadRequest` carries `managerUserId` *or* `coordinatorUserId`
    // and T1 refuses both or neither, which is why this is a branch rather than a spread of two
    // optional fields that could both end up present.
    const request =
      source === 'coordinator-managers'
        ? this.coordinator.coordinatorStaffThread({ managerUserId: row.userId })
        : source === 'teacher-coordinators'
          ? this.teacher.teacherStaffThread({ coordinatorUserId: row.userId })
          : this.teacher.teacherStaffThread({ managerUserId: row.userId });
    request
      .pipe(
        tap((thread) => {
          this.pending.set('');
          // Her own Messages screen: `/teacher/chat` kept C1's path, the coordinator's is
          // `/coordinator/messages` (`core/notifications/notification-target.ts` says the same).
          void this.router.navigate(
            [this.staff.area() === 'coordinator' ? '/coordinator/messages' : '/teacher/chat'],
            {
              queryParams: { thread: thread.id },
            },
          );
        }),
        catchError(() => {
          this.pending.set('');
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }

  /** The one place the row and the role become a route. */
  private source(): StaffSource {
    if (this.of === 'coordinators') return 'teacher-coordinators';
    return this.staff.area() === 'coordinator' ? 'coordinator-managers' : 'teacher-managers';
  }
}
