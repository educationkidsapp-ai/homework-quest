import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BASE_PATH, type RosterChild } from '../../api';
import { renderHq } from '../../../testing/render';
import { BandService } from '../../core/band/band.service';
import { PlaceChildComponent } from './place-child.component';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const UNPLACED: readonly RosterChild[] = [
  { id: 'ch-1', name: 'Amina', parentEmail: 'amina@home.test', hasParent: true },
  { id: 'ch-2', name: 'Yousef', hasParent: false },
];

const URL = '/teacher/classes/c-1a/children/unassigned';

/** `rxResource` answers a microtask after the flush; the DOM is one tick behind that. */
async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

async function renderDialog(children: readonly RosterChild[] = UNPLACED) {
  const rendered = await renderHq(PlaceChildComponent, {
    providers,
    inputs: { open: true, classId: 'c-1a' },
  });
  const backend = TestBed.inject(HttpTestingController);
  await settle();
  backend.expectOne(URL).flush(children);
  await settle();
  return { rendered, backend };
}

describe('place an existing child', () => {
  it('lists the children the server says this section could take', async () => {
    await renderDialog();

    expect(screen.getByRole('button', { name: 'Place Amina' })).toBeInTheDocument();
    expect(screen.getByText('amina@home.test')).toBeInTheDocument();
    // `hasParent` is the hint, not the email: Yousef has neither, Amina has both.
    expect(screen.getAllByText('Registered from the app')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: /^Place/ })).toHaveLength(2);
  });

  /**
   * The sentence the brief asks for word for word. A teacher who opens this on an empty list has
   * to learn that the *parent* starts this, or she goes looking for a button that is not there.
   */
  it('says a parent adds the child in the app first when nobody is waiting', async () => {
    await renderDialog([]);

    expect(
      screen.getByText('No unplaced children of this grade yet — a parent adds a child in the app first.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Place/ })).toBeNull();
  });

  it('narrows the list by name or parent email', async () => {
    await renderDialog();

    await userEvent.type(screen.getByLabelText(/Search/), 'yous');
    await settle();

    expect(screen.getByRole('button', { name: 'Place Yousef' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Place Amina' })).toBeNull();
  });

  it('attaches the child, closes, and reports her to the tab', async () => {
    const { rendered, backend } = await renderDialog();
    const placed: RosterChild[] = [];
    rendered.fixture.componentInstance.placed.subscribe((child) => placed.push(child));

    await userEvent.click(screen.getByRole('button', { name: 'Place Amina' }));
    const request = backend.expectOne('/teacher/classes/c-1a/roster/attach');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ childId: 'ch-1' });

    request.flush({ id: 'ch-1', classId: 'c-1a', name: 'Amina' });
    await settle();

    expect(placed).toEqual([expect.objectContaining({ id: 'ch-1', name: 'Amina', classId: 'c-1a' })]);
    expect(rendered.fixture.componentInstance.open()).toBe(false);
  });

  /** CR5: a failure is a band on the page, never a toast, and never the server's JSON. */
  it('shows the server’s sentence in the band and leaves the list where it was', async () => {
    const { rendered, backend } = await renderDialog();

    await userEvent.click(screen.getByRole('button', { name: 'Place Amina' }));
    backend
      .expectOne('/teacher/classes/c-1a/roster/attach')
      .flush(
        { code: 'conflict', message: 'Amina is already in another class. ({"childId":"ch-1"})' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle();

    expect(TestBed.inject(BandService).current()?.message).toBe('Amina is already in another class.');
    expect(rendered.fixture.componentInstance.open()).toBe(true);
    expect(screen.getByRole('button', { name: 'Place Amina' })).toBeInTheDocument();
  });

  /**
   * The list itself failing is not the same as nobody waiting, and a sheet that shows the empty
   * state for a 500 tells the teacher a lie she cannot check. The band is *inside* the dialog,
   * because a band behind a modal is a band nobody sees.
   */
  it('says so inside the dialog when the list cannot be read, and offers the request again', async () => {
    const rendered = await renderHq(PlaceChildComponent, {
      providers,
      inputs: { open: true, classId: 'c-1a' },
    });
    const backend = TestBed.inject(HttpTestingController);
    await settle();
    backend
      .expectOne(URL)
      .flush(
        { code: 'server_error', message: 'The roster could not be read.' },
        { status: 500, statusText: 'Error' },
      );
    await settle();

    expect(screen.getByText('The roster could not be read.')).toBeInTheDocument();
    expect(screen.queryByText(/No unplaced children/)).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await settle();
    backend.expectOne(URL).flush(UNPLACED);
    await settle();
    expect(screen.getByRole('button', { name: 'Place Amina' })).toBeInTheDocument();
    expect(rendered.fixture.componentInstance.open()).toBe(true);
  });
});
