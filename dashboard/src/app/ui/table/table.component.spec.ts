import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { screen, within } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { EmptyStateComponent } from '../empty-state/empty-state.component';
import { TableComponent, type TableColumn } from './table.component';

interface Row {
  readonly id: string;
  readonly name: string;
  readonly grade: string;
}

const columns: readonly TableColumn<Row>[] = [
  { key: 'name', header: 'Lesson' },
  { key: 'grade', header: 'Grade' },
];

@Component({
  selector: 'hq-table-host',
  imports: [TableComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-table [rows]="rows()" [columns]="columns" [cellTemplate]="cell" [overflowTemplate]="menu" label="Lessons">
      <hq-empty-state table-empty message="No lessons yet." />
    </hq-table>
    <ng-template #cell let-row let-column="column">{{ column.key === 'name' ? row.name : row.grade }}</ng-template>
    <ng-template #menu let-row>
      <button type="button">Actions for {{ row.name }}</button>
    </ng-template>
  `,
})
class TableHost {
  readonly columns = columns;
  readonly rows = signal<readonly Row[]>([
    { id: '1', name: 'Counting by 2s', grade: '1' },
    { id: '2', name: 'The "sh" sound', grade: '2' },
  ]);
}

describe('hq-table', () => {
  it('renders a header and one row per record, with the overflow slot', async () => {
    await renderHq(TableHost);

    const table = screen.getByRole('table', { name: 'Lessons' });
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent?.trim())).toEqual([
      'Lesson',
      'Grade',
      'Actions',
    ]);
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(screen.getByRole('button', { name: 'Actions for Counting by 2s' })).toBeInTheDocument();
  });

  it('replaces the body with the empty-state slot when there are no rows', async () => {
    const { fixture } = await renderHq(TableHost);

    fixture.componentInstance.rows.set([]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(screen.queryAllByRole('row')).toHaveLength(1);
    expect(screen.getByText('No lessons yet.')).toBeInTheDocument();
  });
});
