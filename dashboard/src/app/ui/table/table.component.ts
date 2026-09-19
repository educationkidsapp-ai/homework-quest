import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, TemplateRef, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ListStaggerDirective } from '../motion';

export interface TableColumn<Row> {
  /** Key passed back to the cell template so one template can serve every column. */
  readonly key: keyof Row & string;
  readonly header: string;
  /** Optional column width applied to the header cell, e.g. `30%`. */
  readonly width?: string;
  readonly align?: 'start' | 'end';
}

/**
 * §3's data table: a `--hq-color-surface-sunken` header row of 12/18/500 labels, 14 px cells,
 * inner-divider row rules, a `gray-50` hover, one optional overflow menu per row and an empty
 * state that replaces the body rather than leaving a blank grid.
 *
 * The scroll wrapper is `overflow-x: auto`, so a wide table scrolls *inside* the card it sits
 * in and never gives the page a horizontal scrollbar.
 *
 * Cells come from a single template the caller provides (`cellTemplate`), switching on
 * `column.key` — that keeps the table generic without a column-component-per-type API.
 */
@Component({
  selector: 'hq-table',
  imports: [NgTemplateOutlet, TranslocoPipe, ListStaggerDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table__scroll">
      <table class="table" [class.table--wrap-headers]="wrapHeaders()" [attr.aria-label]="label()">
        <thead class="table__head">
          <tr>
            @for (column of columns(); track column.key) {
              <th
                scope="col"
                [style.text-align]="column.align ?? 'start'"
                [style.inline-size]="column.width ?? null"
              >
                {{ column.header }}
              </th>
            }
            @if (overflowTemplate()) {
              <th scope="col" class="table__overflow-head">
                <span class="hq-sr-only">{{ overflowLabel() ?? ('ui.table.actions' | transloco) }}</span>
              </th>
            }
          </tr>
        </thead>
        @if (rows().length > 0) {
          <tbody hqListStagger>
            @for (row of rows(); track trackBy()(row)) {
              <tr class="table__row" [class.is-selected]="isSelected()(row)">
                @for (column of columns(); track column.key) {
                  <td [style.text-align]="column.align ?? 'start'">
                    <ng-container
                      [ngTemplateOutlet]="cellTemplate()"
                      [ngTemplateOutletContext]="{ $implicit: row, column: column }"
                    />
                  </td>
                }
                @if (overflowTemplate(); as overflow) {
                  <td class="table__overflow">
                    <ng-container
                      [ngTemplateOutlet]="overflow"
                      [ngTemplateOutletContext]="{ $implicit: row }"
                    />
                  </td>
                }
              </tr>
            }
          </tbody>
        }
      </table>
    </div>
    @if (rows().length === 0) {
      <div class="table__empty"><ng-content select="[table-empty]" /></div>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .table__scroll {
      max-inline-size: 100%;
      overflow-x: auto;
      overflow-y: auto;
      max-block-size: 100%;
    }

    .table {
      inline-size: 100%;
      border-collapse: collapse;
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
    }

    .table__head {
      position: sticky;
      inset-block-start: 0;
      z-index: var(--hq-z-sticky);
      background: var(--hq-color-surface-sunken);
    }

    th {
      padding: var(--hq-space-cell);
      font-size: var(--hq-text-theme-2xs);
      line-height: calc(var(--hq-text-theme-2xs-line) / var(--hq-text-theme-2xs));
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-soft);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      white-space: nowrap;
    }

    // A header of two or three words is the widest thing in a column of single digits, and on a
    // seven-column table that is what decides whether the whole thing fits its card. Opt-in
    // rather than the default: a header that wraps is a row of different heights, which is worth
    // paying only where the alternative is a table nobody can reach the end of.
    .table--wrap-headers th {
      white-space: normal;
    }

    td {
      block-size: var(--hq-size-row-height);
      padding: var(--hq-space-cell);
      color: var(--hq-color-ink);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    // The header already draws the rule under itself; a second one on the first row would be
    // the only double line in the system.
    tbody tr:first-child td {
      border-block-start: 0;
    }

    .table__row {
      @include m.motion-safe('background-color');

      &:hover,
      &.is-selected {
        background: var(--hq-color-surface-sunken);
      }
    }

    .table__overflow-head,
    .table__overflow {
      inline-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-12);
      text-align: end;
    }

    .table__empty {
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }
  `,
})
export class TableComponent<Row> {
  readonly rows = input.required<readonly Row[]>();
  readonly columns = input.required<readonly TableColumn<Row>[]>();
  /** One template for every cell; the column arrives as `let-column="column"`. */
  readonly cellTemplate = input.required<TemplateRef<{ $implicit: Row; column: TableColumn<Row> }>>();
  /** Optional trailing cell — the row's overflow menu. */
  /** Let a many-columned table's headers wrap rather than force the table past its card. */
  readonly wrapHeaders = input(false);

  readonly overflowTemplate = input<TemplateRef<{ $implicit: Row }> | null>(null);
  /** Accessible name: say what the table lists. */
  readonly label = input.required<string>();
  /** Accessible name for the overflow column; falls back to the translated default. */
  readonly overflowLabel = input<string | null>(null);
  readonly trackBy = input<(row: Row) => unknown>((row) => row);
  /** Which rows read as selected — §3 gives selected and hover the same tint. */
  readonly isSelected = input<(row: Row) => boolean>(() => false);
}
