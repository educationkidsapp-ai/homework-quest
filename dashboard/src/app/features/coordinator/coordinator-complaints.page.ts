import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { type ChatThread, ChatThreadStatusEnum, CoordinatorChatApi } from '../../api';
import { ChatService } from '../../core/chat/chat.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type TableColumn,
  type Tab,
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from './read-failed.component';

type StatusFilter = 'open' | 'resolved';

/**
 * The complaints inbox (R7, DR3): the `complaint` threads she is the staff peer of.
 *
 * DR3 keeps a complaint **in the conversation it arrived in** rather than in a store of its own,
 * so this screen is a filter over `GET /coordinator/complaints` and every row opens the same
 * thread the Messages screen shows. That is why it carries `chat` and not N5.2's `complaints`
 * flag: there is no complaint record here to gate, only chat threads wearing a label.
 *
 * The one thing she may write in the whole of `/coordinator/**` is a thread's status, and it is
 * the only reason this screen has a confirm band. Resolving is visible to the parent (R4 sends
 * both parties a `status` frame), so it is worth one question first.
 */
@Component({
  selector: 'hq-coordinator-complaints-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CoordinatorReadFailedComponent,
    DatePipe,
    EmptyStateComponent,
    FeatureDirective,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page
      [title]="'nav.complaints' | transloco"
      [subtitle]="'coordinator.complaints.subtitle' | transloco"
    >
      <div *hqFeature="'chat'">
        @if (pending(); as row) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="confirm().title"
            [confirmLabel]="confirm().action"
            (confirmed)="commit(row)"
            (dismissed)="pending.set(null)"
          >
            {{ confirm().body }}
          </hq-band>
        }
        @if (writeFailed()) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            (dismissed)="writeFailed.set(false)"
          >
            {{ 'coordinator.complaints.writeFailed' | transloco }}
          </hq-band>
        }

        <hq-tabs
          variant="chips"
          [tabs]="tabs()"
          [(selected)]="status"
          [label]="'coordinator.complaints.filterLabel' | transloco"
        />

        @if (threads.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="5" [label]="'ui.loading' | transloco" />
        } @else if (threads.error() !== undefined) {
          <hq-coordinator-read-failed (retry)="threads.reload()" />
        } @else {
          <hq-table
            [rows]="rows()"
            [columns]="columns()"
            [cellTemplate]="cell"
            [trackBy]="trackRow"
            [label]="'nav.complaints' | transloco"
          >
            <hq-empty-state
              table-empty
              [message]="'coordinator.complaints.empty.' + status() | transloco"
              [detail]="'coordinator.complaints.emptyHint' | transloco"
            />
          </hq-table>
        }

        <ng-template #cell let-row let-column="column">
          @switch (column.key) {
            @case ('child') {
              <a class="hq-linkbutton" [href]="'#'" (click)="open($event, row)">{{ row.childName }}</a>
            }
            @case ('parent') {
              {{ 'chat.parent' | transloco: { child: row.childName } }}
            }
            @case ('className') {
              {{ row.className }}
            }
            @case ('last') {
              <span class="hq-muted">{{ row.lastAt === null ? '—' : (row.lastAt | date: 'short') }}</span>
            }
            @case ('action') {
              <hq-button variant="secondary" (pressed)="ask(row)">
                {{
                  (row.resolved ? 'coordinator.complaints.reopen' : 'coordinator.complaints.resolve')
                    | transloco
                }}
              </hq-button>
            }
          }
        </ng-template>
      </div>
    </hq-page>
  `,
})
export class CoordinatorComplaintsPage {
  private readonly api = inject(CoordinatorChatApi);
  private readonly chat = inject(ChatService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly status = signal<StatusFilter>('open');
  /** The row a confirm band is standing over, or nothing. */
  protected readonly pending = signal<ComplaintRow | null>(null);
  protected readonly writeFailed = signal(false);

  protected readonly threads = rxResource<ChatThread[], StatusFilter>({
    params: () => this.status(),
    stream: ({ params }) => this.api.coordinatorComplaints(params),
    defaultValue: [],
  });

  protected readonly tabs = computed<readonly Tab<StatusFilter>[]>(() => {
    this.lang();
    return [
      { id: 'open', label: this.t('coordinator.complaints.open') },
      { id: 'resolved', label: this.t('coordinator.complaints.resolved') },
    ];
  });

  protected readonly columns = computed<readonly TableColumn<ComplaintRow>[]>(() => {
    this.lang();
    return [
      { key: 'child', header: this.t('coordinator.complaints.columns.child'), width: '22%' },
      { key: 'parent', header: this.t('coordinator.complaints.columns.parent'), width: '22%' },
      { key: 'className', header: this.t('coordinator.complaints.columns.className'), width: '16%' },
      { key: 'last', header: this.t('coordinator.complaints.columns.last'), width: '18%' },
      { key: 'action', header: this.t('coordinator.complaints.columns.action'), width: '22%' },
    ];
  });

  /**
   * The status of every thread the socket has spoken about, by thread id.
   *
   * `ChatService.threads` is where R4's `status` frame lands, and it holds the coordinator's own
   * list (the same rows, on the same routes). Reading it here is what makes this screen agree with
   * the conversation: the review found the frame updating the Messages header while a row in this
   * table, backed only by its own `rxResource`, sat there still saying "open".
   */
  private readonly liveStatus = computed(
    () =>
      new Map(
        this.chat
          .threads()
          .filter((thread) => thread.id !== undefined)
          .map((thread) => [thread.id!, thread.status]),
      ),
  );

  protected readonly rows = computed<readonly ComplaintRow[]>(() => {
    const live = this.liveStatus();
    return (
      this.threads
        .value()
        .map((thread) => ({
          threadId: thread.id ?? '',
          childName: thread.childName,
          className: thread.className ?? '',
          lastAt: thread.lastMessage?.createdAt ?? null,
          resolved: (live.get(thread.id ?? '') ?? thread.status) === ChatThreadStatusEnum.RESOLVED,
        }))
        // The list is a filter *on* status, so a row the socket has moved has to leave the tab it
        // no longer belongs to — not merely change the word in its last column.
        .filter((row) => row.resolved === (this.status() === 'resolved'))
    );
  });

  /** What the band says, which is the same three questions read in the direction she is going. */
  protected readonly confirm = computed(() => {
    this.lang();
    const which = this.pending()?.resolved === true ? 'reopen' : 'resolve';
    return {
      title: this.t(`coordinator.complaints.${which}Title`),
      body: this.t(`coordinator.complaints.${which}Body`),
      action: this.t(`coordinator.complaints.confirm${which === 'reopen' ? 'Reopen' : 'Resolve'}`),
    };
  });

  protected readonly trackRow = (row: ComplaintRow): string => row.threadId;

  /** Her answer to a complaint is the conversation, so a row opens the thread, not a detail page. */
  protected open(event: Event, row: ComplaintRow): void {
    event.preventDefault();
    void this.router.navigate(['/coordinator/messages'], { queryParams: { thread: row.threadId } });
  }

  protected ask(row: ComplaintRow): void {
    this.writeFailed.set(false);
    this.pending.set(row);
  }

  /**
   * `PATCH /coordinator/chat/threads/{id}/status`, then reload rather than patch the row in place:
   * the list is a filter *on* status, so a resolved thread has to leave the open tab entirely and
   * a row that merely changed its badge would sit in a tab that no longer describes it.
   */
  protected commit(row: ComplaintRow): void {
    this.pending.set(null);
    this.api
      .coordinatorThreadStatus(row.threadId, { status: row.resolved ? 'open' : 'resolved' })
      .pipe(
        tap(() => this.threads.reload()),
        catchError(() => {
          this.writeFailed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}

interface ComplaintRow {
  readonly threadId: string;
  readonly childName: string;
  readonly className: string;
  readonly lastAt: number | null;
  readonly resolved: boolean;
}
