/* hq-flag: chat — a complaint is stored and delivered as a conversation (B6) */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map } from 'rxjs';
import { type Complaint, type ComplaintList, ChatMessageSenderEnum, ComplaintStatusEnum } from '../../api';
import { ComplaintsService, type ComplaintFilter } from '../../core/complaints/complaints.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type TableColumn,
  type Tab,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
} from '../../ui';
import { ComplaintConversationComponent } from './complaint-conversation.component';

/**
 * **Complaints** (D5, owner 2026-10-03: "Complaints must be separate from messages").
 *
 * One screen for the four areas that have it — the teacher, the coordinator and the manager it
 * is addressed to (and, read-only, the ones they supervise), and the Admin's support view —
 * reading `/{area}/complaints` through {@link ComplaintsService}. The list filters on status
 * (Open · Resolved · All, with B6's counts on the tabs) and searches what a row says; `?open={id}`
 * opens one, which is also what every `complaint.*` bell row links to.
 *
 * The list and the conversation are the same route, so the bell, a bookmark and Back all land
 * where they say; the conversation is drawn instead of the list rather than beside it, because a
 * complaint is read in full and a five-column table does not fit beside a conversation.
 */
@Component({
  selector: 'hq-complaints-page',
  imports: [
    ComplaintConversationComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    RouterLink,
    SkeletonComponent,
    TableComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.complaints' | transloco" [subtitle]="subtitle() | transloco">
      <div *hqFeature="'chat'">
        @if (openId(); as id) {
          <hq-complaint-conversation
            [complaintId]="id"
            (closed)="close()"
            (changed)="stale = true"
            (missing)="close(true)"
          />
        } @else {
          <div class="complaints__filters">
            <hq-tabs
              variant="chips"
              [tabs]="tabs()"
              [(selected)]="status"
              [label]="'complaints.filterLabel' | transloco"
            />
            <div data-hq-search class="complaints__search">
              <hq-input
                type="search"
                keycap="/"
                [label]="'complaints.search' | transloco"
                [hideLabel]="true"
                [placeholder]="'complaints.search' | transloco"
                [value]="search()"
                (valueChange)="search.set($event)"
              />
            </div>
          </div>

          @if (list.isLoading() && !list.hasValue()) {
            <hq-skeleton [loading]="true" [lines]="5" [label]="'ui.loading' | transloco" />
          } @else if (list.error() !== undefined) {
            <hq-empty-state
              [message]="'complaints.readFailed' | transloco"
              [actionLabel]="'ui.retry' | transloco"
              (action)="list.reload()"
            />
          } @else {
            <hq-table
              [bordered]="true"
              [rows]="rows()"
              [columns]="columns()"
              [cellTemplate]="cell"
              [trackBy]="trackRow"
              [label]="'nav.complaints' | transloco"
            >
              <hq-empty-state
                table-empty
                [message]="
                  (search().trim() === '' ? 'complaints.empty.' + status() : 'complaints.noMatch') | transloco
                "
                [detail]="'complaints.emptyHint' | transloco"
              />
            </hq-table>
          }

          <ng-template #cell let-row let-column="column">
            @switch (column.key) {
              @case ('title') {
                <a
                  class="complaints__open"
                  [routerLink]="[]"
                  [queryParams]="{ open: row.id }"
                  queryParamsHandling="merge"
                >
                  <span class="complaints__title" dir="auto">{{ row.title }}</span>
                </a>
                <span class="complaints__last hq-muted" dir="auto">{{ row.lastMessage?.body ?? '' }}</span>
              }
              @case ('child') {
                <span class="complaints__line complaints__title">{{ row.childName }}</span>
                @if (row.className) {
                  <span class="complaints__line hq-muted">{{ row.className }}</span>
                }
              }
              @case ('parent') {
                <span class="complaints__line">{{
                  row.parentName ?? ('chat.parent' | transloco: { child: row.childName })
                }}</span>
              }
              @case ('recipient') {
                <span class="complaints__line">{{ row.recipientName }}</span>
                <span class="complaints__line hq-muted">{{
                  'complaints.role.' + row.recipientRole | transloco
                }}</span>
              }
              @case ('status') {
                <span class="complaints__status">
                  <span
                    class="hq-badge"
                    [class.hq-badge--warning]="row.status === open"
                    [class.hq-badge--success]="row.status !== open"
                  >
                    {{ 'complaints.status.' + row.status | transloco }}
                  </span>
                  @if (row.unread > 0) {
                    <span class="hq-badge hq-badge--solid hq-badge--primary">
                      {{ 'complaints.unread' | transloco: { count: row.unread } }}
                    </span>
                  }
                </span>
              }
            }
          </ng-template>
        }
      </div>
    </hq-page>
  `,
  styles: `
    .complaints__filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      margin-block-end: var(--hq-space-16);
    }

    .complaints__search {
      flex: 0 1 calc(var(--hq-space-48) * 7);
    }

    .complaints__open {
      color: inherit;
      text-decoration: none;

      &:hover .complaints__title,
      &:focus-visible .complaints__title {
        text-decoration: underline;
      }
    }

    .complaints__title {
      font-weight: var(--hq-text-weight-medium);
    }

    // Table cells size to their content, so nothing here may refuse to wrap: a long address or
    // last message would push Status off the card.
    .complaints__line,
    .complaints__open {
      display: block;
      overflow-wrap: anywhere;
    }

    .complaints__last {
      display: -webkit-box;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
      line-clamp: 2;
      overflow: hidden;
      overflow-wrap: anywhere;
    }

    .complaints__status {
      display: inline-flex;
      flex-wrap: wrap;
      gap: var(--hq-space-4);
    }
  `,
})
export class ComplaintsPage {
  private readonly complaints = inject(ComplaintsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly open = ComplaintStatusEnum.OPEN;
  protected readonly status = signal<ComplaintFilter>('open');
  protected readonly search = signal('');
  /**
   * She wrote in, or moved, the complaint she had open (or a frame moved one): the list is read
   * again on the way back — however she goes back, by the button, Back or a bell link.
   */
  protected stale = false;

  /** `?open=` — reactive, like Messages' `?thread=`: the bell can change it under an open page. */
  protected readonly openId = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('open')?.trim() || null)),
    { initialValue: null },
  );

  protected readonly subtitle = computed(() =>
    this.complaints.area() === 'admin' ? 'complaints.subtitle.admin' : 'complaints.subtitle.staff',
  );

  protected readonly list = rxResource<ComplaintList, ComplaintFilter>({
    params: () => this.status(),
    stream: ({ params }) => this.complaints.list(params),
  });

  protected readonly tabs = computed<readonly Tab<ComplaintFilter>[]>(() => {
    this.lang();
    const counts = this.complaints.counts();
    return [
      { id: 'open', label: this.t('complaints.filter.open'), badge: counts.open },
      { id: 'resolved', label: this.t('complaints.filter.resolved'), badge: counts.resolved },
      { id: 'all', label: this.t('complaints.filter.all') },
    ];
  });

  protected readonly columns = computed<readonly TableColumn<Complaint>[]>(() => {
    this.lang();
    return [
      { key: 'title', header: this.t('complaints.columns.title'), width: '30%' },
      { key: 'child', header: this.t('complaints.columns.child'), width: '18%' },
      { key: 'parent', header: this.t('complaints.columns.parent'), width: '16%' },
      { key: 'recipient', header: this.t('complaints.columns.recipient'), width: '18%' },
      { key: 'status', header: this.t('complaints.columns.status'), width: '18%' },
    ];
  });

  protected readonly rows = computed<readonly Complaint[]>(() => {
    const all = this.list.hasValue() ? this.list.value().complaints : [];
    return filterComplaints(all, this.status(), this.search());
  });

  protected readonly trackRow = (row: Complaint): string => row.id;

  constructor() {
    let wasOpen = false;
    effect(() => {
      const open = this.openId() !== null;
      untracked(() => {
        if (!open && wasOpen && this.stale) {
          this.stale = false;
          this.list.reload();
        }
        wasOpen = open;
      });
    });

    // Live: a parent's message moves its row to the top with one more unread; a status frame or a
    // bell row (a new complaint, a move somebody else made) is a re-read — the list is a filter on
    // status, so a moved row has to leave the tab rather than change a word.
    this.complaints.signals.pipe(takeUntilDestroyed()).subscribe((signal) => {
      if (signal.kind === 'message') {
        const message = signal.message;
        if (!this.list.hasValue() || !this.list.value().complaints.some((c) => c.id === message.threadId))
          return;
        const viewing = this.openId() === message.threadId;
        const current = this.list.value();
        const row = current.complaints.find((c) => c.id === message.threadId)!;
        const fromParent = message.sender === ChatMessageSenderEnum.PARENT;
        const updated = {
          ...row,
          lastMessage: message,
          unread: row.unread + (fromParent && !viewing && row.canReply ? 1 : 0),
        };
        this.list.set({
          ...current,
          complaints: [updated, ...current.complaints.filter((c) => c.id !== row.id)],
        });
      } else if (signal.kind === 'status' || signal.kind === 'changed') {
        if (this.openId() === null) this.list.reload();
        else this.stale = true;
      }
    });
  }

  /**
   * Back to the list. `missing`: the link named a complaint she cannot open, so it leaves the
   * address without a trace in the history and without a word (the D1/D2 rule for a stale link).
   */
  protected close(missing = false): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { open: null },
      queryParamsHandling: 'merge',
      ...(missing ? { replaceUrl: true } : {}),
    });
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}

/**
 * The rows a tab and a search keep. The server already filtered by status; this keeps a row the
 * socket moved out of the tab it no longer belongs to until the re-read lands, and matches the
 * search against everything a row says — title, child, class, parent, recipient, last message.
 */
export function filterComplaints(
  rows: readonly Complaint[],
  status: ComplaintFilter,
  search: string,
): readonly Complaint[] {
  const needle = search.trim().toLocaleLowerCase();
  return rows
    .filter((row) => status === 'all' || (row.status as string) === status)
    .filter(
      (row) =>
        needle === '' ||
        [
          row.title,
          row.childName,
          row.className,
          row.parentName,
          row.recipientName,
          row.subject,
          row.lastMessage?.body,
        ]
          .filter((text): text is string => typeof text === 'string')
          .some((text) => text.toLocaleLowerCase().includes(needle)),
    );
}
