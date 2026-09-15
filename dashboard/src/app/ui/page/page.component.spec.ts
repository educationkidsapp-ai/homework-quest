import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { PageComponent } from './page.component';

describe('hq-page', () => {
  it('renders the title as the page heading', async () => {
    await renderHq(PageComponent, {
      inputs: { title: 'Schools' },
      providers: [provideRouter([])],
    });

    expect(screen.getByRole('heading', { level: 1, name: 'Schools' })).toBeInTheDocument();
  });

  it('marks the last breadcrumb as the current page', async () => {
    await renderHq(PageComponent, {
      inputs: {
        title: 'Greenfield Primary',
        breadcrumbs: [{ label: 'Schools', link: '/admin/schools' }, { label: 'Greenfield Primary' }],
        breadcrumbLabel: 'Breadcrumb',
      },
      providers: [provideRouter([])],
    });

    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(crumbs).toHaveTextContent('Schools');
    expect(screen.getByText('Greenfield Primary', { selector: '[aria-current="page"]' })).toBeInTheDocument();
  });

  it('omits the breadcrumb nav when there is nothing to go back to', async () => {
    await renderHq(PageComponent, { inputs: { title: 'Home' }, providers: [provideRouter([])] });

    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
