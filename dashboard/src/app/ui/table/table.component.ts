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
 * The data table: 56 px rows, a sticky header, one optional overflow menu per row,
 * and an empty state that replaces the body rather than leaving a blank grid.
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
      <table class="table" [attr.aria-label]="label()">
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
              <tr class="table__row">
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
    :host {
      display: block;
    }

    .table__scroll {
      overflow: auto;
      max-block-size: 100%;
    }

    .table {
      inline-size: 100%;
      border-collapse: collapse;
      font-size: var(--hq-font-body-size);
    }

    .table__head {
      position: sticky;
      inset-block-start: 0;
      z-index: var(--hq-z-sticky);
      background: var(--hq-color-bg);
    }

    th {
      block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-space-16);
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
      color: var(--hq-color-ink-soft);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
      white-space: nowrap;
    }

    td {
      block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-space-16);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .table__overflow-head,
    .table__overflow {
      inline-size: var(--hq-size-touch-target);
      text-align: end;
    }

    .table__empty {
      border: var(--hq-size-rule) solid var(--hq-color-line);
      border-block-start: 0;
    }
  `,
})
export class TableComponent<Row> {
  readonly rows = input.required<readonly Row[]>();
  readonly columns = input.required<readonly TableColumn<Row>[]>();
  /** One template for every cell; the column arrives as `let-column="column"`. */
  readonly cellTemplate = input.required<TemplateRef<{ $implicit: Row; column: TableColumn<Row> }>>();
  /** Optional trailing cell — the row's overflow menu. */
  readonly overflowTemplate = input<TemplateRef<{ $implicit: Row }> | null>(null);
  /** Accessible name: say what the table lists. */
  readonly label = input.required<string>();
  /** Accessible name for the overflow column; falls back to the translated default. */
  readonly overflowLabel = input<string | null>(null);
  readonly trackBy = input<(row: Row) => unknown>((row) => row);
}
