import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { FlagService } from '../../core/flags/flag.service';
import {
  type LeaveDraft,
  draftOf,
  errorsOf,
  requestOf,
} from './attendance-settings.models';
import { LeaveCardComponent } from './leave-card.component';

function providersWith(flagOn: boolean): (Provider | EnvironmentProviders)[] {
  return [{ provide: FlagService, useValue: { isOn: () => flagOn, ready: () => true } }];
}

/** What `GET /admin/attendance-settings` answers for a school that has set the leave rules. */
const SETTINGS = {
  leaveChain: 'MANAGER_THEN_ADMIN',
  permissionMaxMinutes: 120,
  leaveTypes: [
    { type: 'ANNUAL', enabled: true },
    { type: 'SICK', enabled: true, documentAfterDays: 2 },
    { type: 'EMERGENCY', enabled: false },
    { type: 'UNPAID', enabled: true },
    { type: 'OTHER', enabled: true },
  ],
  leaveBalances: [
    { type: 'ANNUAL', category: 'TEACHER', yearlyDays: 30 },
    { type: 'SICK', category: 'WORKER', yearlyDays: 15 },
  ],
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

/**
 * The card edits the page's draft through its `leave` model, which the page binds two-way. The
 * spec reads that model back: what the card wrote is what `attendance-settings.page.html` puts in
 * the draft its one Save sends.
 */
async function renderCard(leave: LeaveDraft = draftOf(SETTINGS).leave, flagOn = true) {
  const rendered = await renderHq(LeaveCardComponent, {
    providers: providersWith(flagOn),
    inputs: { leave, errors: errorsOf({ ...draftOf(SETTINGS), leave }) },
  });
  const written = (): LeaveDraft => rendered.fixture.componentInstance.leave();
  return { rendered, written };
}

describe('feature 34 — the Leave card', () => {
  beforeEach(() => localStorage.clear());

  it('is absent while staffLeave is off', async () => {
    await renderCard(draftOf(SETTINGS).leave, false);

    expect(screen.queryByText('Leave')).not.toBeInTheDocument();
  });

  it('shows every type, its document rule, the chain and the permission limit as the server sent them', async () => {
    await renderCard();

    expect(screen.getByRole('switch', { name: 'Annual' })).toBeChecked();
    // A type the school turned off is still listed, so she can turn it back on.
    expect(screen.getByRole('switch', { name: 'Emergency' })).not.toBeChecked();
    expect(screen.getAllByRole('switch')).toHaveLength(5);

    expect(screen.getByLabelText(/Document needed after, Sick/)).toHaveValue(2);
    // A type with no document rule says so rather than showing a nought.
    expect(screen.getByLabelText(/Document needed after, Annual/)).toHaveValue(null);

    expect(screen.getByLabelText(/Who approves/)).toHaveValue('MANAGER_THEN_ADMIN');
    expect(screen.getByLabelText(/Longest permission in a day/)).toHaveValue(120);
  });

  it('shows the yearly balance per type and category, and nothing where there is none', async () => {
    await renderCard();

    expect(screen.getByLabelText(/^Annual leave, Teacher/)).toHaveValue(30);
    expect(screen.getByLabelText(/^Sick leave, Worker/)).toHaveValue(15);
    // Unpaid leave has no balance: an empty box is "no limit", which is what the server stores.
    expect(screen.getByLabelText(/^Unpaid leave, Teacher/)).toHaveValue(null);
  });

  it('writes an edited balance back to the page draft, and sends only the pairs that have one', async () => {
    const { rendered, written } = await renderCard();

    await userEvent.clear(screen.getByLabelText(/^Annual leave, Teacher/));
    await userEvent.type(screen.getByLabelText(/^Annual leave, Teacher/), '25');
    await settle(rendered);

    const request = requestOf({ ...draftOf(SETTINGS), leave: written() });
    expect(request.leaveBalances).toEqual([
      { type: 'ANNUAL', category: 'TEACHER', yearlyDays: 25 },
      { type: 'SICK', category: 'WORKER', yearlyDays: 15 },
    ]);
  });

  it('writes the chain, the permission limit and a type switch back to the page draft', async () => {
    const { rendered, written } = await renderCard();

    await userEvent.selectOptions(screen.getByLabelText(/Who approves/), 'MANAGER');
    await settle(rendered);
    await userEvent.click(screen.getByRole('switch', { name: 'Unpaid' }));
    await settle(rendered);
    await userEvent.clear(screen.getByLabelText(/Longest permission in a day/));
    await userEvent.type(screen.getByLabelText(/Longest permission in a day/), '90');
    await settle(rendered);

    const request = requestOf({ ...draftOf(SETTINGS), leave: written() });
    expect(request.leaveChain).toBe('MANAGER');
    expect(request.permissionMaxMinutes).toBe(90);
    expect(request.leaveTypes).toEqual([
      { type: 'ANNUAL', enabled: true },
      { type: 'SICK', enabled: true, documentAfterDays: 2 },
      { type: 'EMERGENCY', enabled: false },
      { type: 'UNPAID', enabled: false },
      { type: 'OTHER', enabled: true },
    ]);
  });

  it('says what is wrong rather than letting the server answer 400', async () => {
    const over: LeaveDraft = {
      ...draftOf(SETTINGS).leave,
      permissionMax: '900',
      balances: { 'ANNUAL:TEACHER': '400' },
    };
    await renderCard(over);

    expect(screen.getByText('Enter 1 to 600 minutes.')).toBeInTheDocument();
    expect(screen.getByText('Enter 0 to 365 days, or leave it empty.')).toBeInTheDocument();
  });

  it('leaves every field alone for a manager, who holds the read and not the write', async () => {
    await renderHq(LeaveCardComponent, {
      providers: providersWith(true),
      inputs: { leave: draftOf(SETTINGS).leave, disabled: true },
    });

    expect(screen.getByLabelText(/Who approves/)).toBeDisabled();
    expect(screen.getByLabelText(/^Annual leave, Teacher/)).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Annual' })).toBeDisabled();
  });
});
