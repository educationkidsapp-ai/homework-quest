import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { ShortcutsDialogComponent, type Shortcut } from './shortcuts-dialog.component';

const shortcuts: readonly Shortcut[] = [
  { keys: '/', description: 'Focus search' },
  { keys: '?', description: 'Show this sheet' },
];

describe('hq-shortcuts-dialog', () => {
  it('opens on ? and lists every shortcut', async () => {
    const { fixture } = await renderHq(ShortcutsDialogComponent, {
      inputs: { shortcuts, title: 'Keyboard shortcuts' },
    });

    await userEvent.keyboard('?');
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(true);
    expect(screen.getByText('Focus search')).toBeInTheDocument();
    expect(screen.getByText('Show this sheet')).toBeInTheDocument();
  });

  it('ignores ? while the user is typing', async () => {
    const { fixture } = await renderHq(ShortcutsDialogComponent, {
      inputs: { shortcuts, title: 'Keyboard shortcuts' },
    });

    const field = document.createElement('input');
    document.body.append(field);
    field.focus();
    await userEvent.keyboard('?');

    expect(fixture.componentInstance.open()).toBe(false);
    field.remove();
  });

  it('closes from the Close button', async () => {
    const { fixture } = await renderHq(ShortcutsDialogComponent, {
      inputs: { shortcuts, title: 'Keyboard shortcuts', open: true },
    });

    await userEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(fixture.componentInstance.open()).toBe(false);
  });
});
