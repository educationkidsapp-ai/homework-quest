/* hq-flag: none (shell) — the page that turns the switches on cannot be behind a switch: a flag
   over it would have no way back on, which is exactly the corner the owner was in. Its row in
   `core/nav/screens.ts` carries `flag.read` and no flag, and the server's `FlagController` is
   documented as infrastructure for the same reason. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type FeatureFlagDefinition,
  type FlagAuditEntry,
  type FlagMatrix,
  FeatureFlagsApi,
  apiErrorOf,
} from '../../api';
import { SoleSchoolService } from '../../core/auth/sole-school.service';
import { FlagService } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  PageComponent,
  SkeletonComponent,
  ToggleComponent,
} from '../../ui';
import { DESCRIBED_KEYS, OTHER_GROUP_ID, SWITCH_GROUPS, type SwitchEntry } from './switches.catalogue';

/** One switch as the page draws it: everything resolved, nothing for the template to work out. */
interface SwitchRow {
  readonly key: string;
  readonly name: string;
  readonly what: string;
  readonly feature: string | null;
  /** "Works with Announcements" — shown whatever the other switch says. */
  readonly needs: string | null;
  /** "Announcements is off. Turn that on first." — only while one of them is off. */
  readonly needsOff: string | null;
  readonly note: string | null;
  readonly enabled: boolean;
  readonly busy: boolean;
  readonly lastChanged: string | null;
}

interface RenderedGroup {
  readonly id: string;
  readonly title: string;
  readonly rows: readonly SwitchRow[];
}

const EMPTY_MATRIX: FlagMatrix = { definitions: [], schools: [] };
const AUDIT_LIMIT = 200;

/**
 * **Feature switches** (`مفاتيح الميزات`) — the Admin's one page for turning a feature on.
 *
 * Every feature since the one-school build ships off, because a half-finished screen in front of
 * a parent is worse than no screen. The page that turned them on was retired with the rest of the
 * platform screens on 2026-09-30 (MA0), which left the owner reading "The staff app is not enabled
 * for your school yet" with nothing on the dashboard that could enable it. This is that page back,
 * at `switches` rather than the retired `flags` — the old path still redirects to her Home and a
 * bookmark must not quietly land on a different screen than it did.
 *
 * **Not the old matrix.** The old one was a grid of keys by school: right for a platform with many
 * schools, meaningless for an Admin who has exactly one. This is a list of switches grouped by who
 * notices them, each a sentence rather than a key, and the toggle writes the one school's row.
 *
 * **The school.** An Admin's account carries none, so it is {@link SoleSchoolService}'s — the
 * server's answer to "which school is hers", not a value from this browser — and the matrix's own
 * single row stands in if that has not settled.
 *
 * **Optimistic, with the red band.** The toggle moves at once and the PUT follows. The server
 * answers with the school's whole flag set, which replaces the row; a failure puts the switch back
 * where it was and says why in the band. Nothing is a toast: a switch that silently failed to flip
 * is a school that thinks it has a feature.
 *
 * **And the rail follows.** `FlagService.reload()` after every success, so the row the new switch
 * unlocks appears without a reload — the Admin turning `staffAttendance` on expects Attendance
 * settings to be there when she looks for it.
 *
 * `flag.write` is ADMIN's alone; a manager holds `flag.read`, so for her every toggle is disabled
 * and the page says so once at the top rather than thirty times.
 */
@Component({
  selector: 'hq-switches-page',
  imports: [
    PageComponent,
    CardComponent,
    ToggleComponent,
    BandComponent,
    ButtonComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './switches.page.html',
  styleUrl: './switches.page.scss',
})
export class SwitchesPage {
  private readonly api = inject(FeatureFlagsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly permissions = inject(PermissionService);
  private readonly sole = inject(SoleSchoolService);
  private readonly flagService = inject(FlagService);
  private readonly lang = activeLang();

  protected readonly canWrite = computed(() => this.permissions.can('flag.write'));

  protected readonly matrix = rxResource<FlagMatrix, true>({
    params: () => true,
    stream: () => this.api.matrix(),
    defaultValue: EMPTY_MATRIX,
  });

  /** Who flipped what: one read, the newest 200 rows, re-read after every successful write. */
  private readonly auditLog = rxResource<readonly FlagAuditEntry[], true>({
    params: () => true,
    stream: () => this.api.audit(AUDIT_LIMIT),
    defaultValue: [],
  });

  /** The school whose row this page writes. */
  protected readonly schoolId = computed(
    () => this.sole.id() ?? this.matrix.value().schools?.[0]?.schoolId ?? null,
  );

  protected readonly schoolName = computed(() => this.row()?.schoolName ?? null);

  /** The school's flag set, reset whenever the server's answer changes and written optimistically. */
  private readonly flags = linkedSignal<Record<string, boolean>, Record<string, boolean>>({
    source: () => this.row()?.flags ?? {},
    computation: (flags) => ({ ...flags }),
  });

  private readonly busy = signal<ReadonlySet<string>>(new Set());
  protected readonly failure = signal<string | null>(null);

  protected readonly loading = computed(() => this.matrix.isLoading() && this.definitions().length === 0);
  protected readonly unreachable = computed(() => this.matrix.status() === 'error');

  protected readonly groups = computed<readonly RenderedGroup[]>(() => {
    const defined = this.definitions();
    const byKey = new Map(defined.map((definition) => [definition.key ?? '', definition]));
    const named = SWITCH_GROUPS.map((group) => ({
      id: group.id,
      title: this.t(`admin.switches.groups.${group.id}`),
      // A key this table names but the server does not define yet (a branch ahead of its
      // migration) is not drawn: a toggle the PUT would 400 on is worse than its absence.
      rows: group.entries.filter((entry) => byKey.has(entry.key)).map((entry) => this.rowOf(entry)),
    }));
    const other = defined
      .filter((definition) => definition.key !== undefined && !DESCRIBED_KEYS.has(definition.key))
      .map((definition) => this.unnamedRow(definition));
    return [
      ...named.filter((group) => group.rows.length > 0),
      ...(other.length > 0
        ? [{ id: OTHER_GROUP_ID, title: this.t(`admin.switches.groups.${OTHER_GROUP_ID}`), rows: other }]
        : []),
    ];
  });

  protected reload(): void {
    this.failure.set(null);
    this.matrix.reload();
    this.auditLog.reload();
  }

  /**
   * Flip one switch: the row moves now, the PUT follows, and a failure puts it back.
   *
   * The server answers with the school's whole set rather than the one cell, so the row is
   * replaced wholesale — the only honest thing to do when turning one key on may have turned
   * another off underneath.
   */
  protected onToggle(key: string, next: boolean): void {
    const schoolId = this.schoolId();
    if (!this.canWrite() || schoolId === null || this.busy().has(key)) return;
    const before = this.flags()[key] === true;
    if (before === next) return;

    this.failure.set(null);
    this.flags.update((flags) => ({ ...flags, [key]: next }));
    this.busy.update((keys) => new Set(keys).add(key));

    this.api.setSchoolFlag(schoolId, key, { enabled: next }).subscribe({
      next: (flags) => {
        this.flags.set({ ...flags });
        this.release(key);
        // The rail is built from the flag map, not from this page's copy: without this the
        // Admin turns a feature on and has to reload to find the screen it unlocked.
        this.flagService.reload();
        this.auditLog.reload();
      },
      error: (error: unknown) => {
        this.flags.update((flags) => ({ ...flags, [key]: before }));
        this.release(key);
        this.failure.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private release(key: string): void {
    this.busy.update((keys) => {
      const next = new Set(keys);
      next.delete(key);
      return next;
    });
  }

  private definitions(): readonly FeatureFlagDefinition[] {
    return this.matrix.value().definitions ?? [];
  }

  /** The one school's row of the matrix — hers by id, or the single row a one-school server sent. */
  private row() {
    const schools = this.matrix.value().schools ?? [];
    const id = this.sole.id();
    return (id === null ? undefined : schools.find((school) => school.schoolId === id)) ?? schools[0];
  }

  private rowOf(entry: SwitchEntry): SwitchRow {
    const off = (entry.needs ?? []).filter((key) => this.flags()[key] !== true);
    return {
      key: entry.key,
      name: this.t(`admin.switches.keys.${entry.id}.name`),
      what: this.t(`admin.switches.keys.${entry.id}.what`),
      feature: entry.feature === undefined ? null : this.t('admin.switches.feature', { n: entry.feature }),
      needs:
        entry.needs === undefined ? null : this.t('admin.switches.needs', { names: this.names(entry.needs) }),
      needsOff: off.length === 0 ? null : this.t('admin.switches.needsOff', { names: this.names(off) }),
      note: entry.noteKey === undefined ? null : this.t(`admin.switches.notes.${entry.noteKey}`),
      enabled: this.flags()[entry.key] === true,
      busy: this.busy().has(entry.key),
      lastChanged: this.lastChanged(entry.key),
    };
  }

  /**
   * A key the server defines and {@link SWITCH_GROUPS} has no words for: its own key as the name
   * and the server's description as the sentence. Blunter than the rest of the page, and the
   * alternative is a feature nobody can turn on because this file was not edited.
   */
  private unnamedRow(definition: FeatureFlagDefinition): SwitchRow {
    const key = definition.key ?? '';
    return {
      key,
      name: key,
      what: definition.description ?? this.t('admin.switches.noDescription'),
      feature: null,
      needs: null,
      needsOff: null,
      note: null,
      enabled: this.flags()[key] === true,
      busy: this.busy().has(key),
      lastChanged: this.lastChanged(key),
    };
  }

  /** The names of the switches a key rides on, read out of this page's own words for them. */
  private names(keys: readonly string[]): string {
    const ids = new Map(
      SWITCH_GROUPS.flatMap((group) => group.entries).map((entry) => [entry.key, entry.id]),
    );
    const separator = this.t('admin.switches.listSeparator');
    return keys
      .map((key) => {
        const id = ids.get(key);
        return id === undefined ? key : this.t(`admin.switches.keys.${id}.name`);
      })
      .join(separator);
  }

  /** "Last changed by hana@… on 12 Oct, 09:14", from the audit, or null when it holds nothing. */
  private lastChanged(key: string): string | null {
    const schoolId = this.schoolId();
    const entry = (this.auditLog.value() ?? []).find(
      (row) =>
        row.flagKey === key &&
        // A null `schoolId` is the platform-wide action (§4's "enable for all"), which did change
        // this school's value and is the newest thing that did.
        (row.schoolId === undefined || row.schoolId === null || row.schoolId === schoolId),
    );
    if (entry === undefined) return null;
    const who = entry.actorEmail ?? this.t('admin.switches.someone');
    const when =
      entry.createdAt === undefined
        ? null
        : new Intl.DateTimeFormat(this.lang(), {
            day: 'numeric',
            month: 'short',
            hour: 'numeric',
            minute: '2-digit',
          }).format(new Date(entry.createdAt));
    return when === null
      ? this.t('admin.switches.lastChangedBy', { who })
      : this.t('admin.switches.lastChanged', { who, when });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    // Read the active language, so every computed that resolves a sentence through here re-runs
    // when it changes. `TranslocoService.translate` is a plain call and reports nothing itself.
    this.lang();
    return this.transloco.translate<string>(key, params);
  }
}
