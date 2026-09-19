import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { StopProseComponent } from './stop-prose.component';
import { parseProse } from './stop-prose';

describe('parseProse', () => {
  it('reads # lines as headings, at their level', () => {
    expect(parseProse('# Which shape?\n## Options\n### Hint')).toEqual([
      { kind: 'heading', level: 1, text: 'Which shape?' },
      { kind: 'heading', level: 2, text: 'Options' },
      { kind: 'heading', level: 3, text: 'Hint' },
    ]);
  });

  it('keeps a run of lines as one paragraph and a blank line as the break', () => {
    expect(parseProse('Pip says: hello.\nAnd then this.\n\nA second paragraph.')).toEqual([
      { kind: 'paragraph', text: 'Pip says: hello.\nAnd then this.' },
      { kind: 'paragraph', text: 'A second paragraph.' },
    ]);
  });

  it('groups - lines into one list and numbered lines into another', () => {
    expect(parseProse('- a circle (correct)\n- a square\n1. first\n2. second')).toEqual([
      { kind: 'list', ordered: false, items: ['a circle (correct)', 'a square'] },
      { kind: 'list', ordered: true, items: ['first', 'second'] },
    ]);
  });

  it('drops the indentation an exit ticket’s questions arrive with', () => {
    expect(parseProse('Exit ticket:\n\n  Count the apples\n  - three (correct)')).toEqual([
      { kind: 'paragraph', text: 'Exit ticket:' },
      { kind: 'paragraph', text: 'Count the apples' },
      { kind: 'list', ordered: false, items: ['three (correct)'] },
    ]);
  });

  it('treats an unknown line as prose rather than losing it', () => {
    expect(parseProse('> quoted\n#### four hashes')).toEqual([
      { kind: 'paragraph', text: '> quoted\n#### four hashes' },
    ]);
  });
});

describe('the prose component', () => {
  it('renders headings, paragraphs and lists as elements', async () => {
    await renderHq(StopProseComponent, {
      inputs: { text: '# Which shape?\n\nPip says: pick one.\n\n- a circle\n- a square' },
    });

    expect(screen.getByRole('heading', { name: 'Which shape?' })).toBeInTheDocument();
    expect(screen.getByText('Pip says: pick one.')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['a circle', 'a square']);
  });

  /**
   * The point of the whole renderer: a teacher's text is data. Angular interpolates every block,
   * so a tag she (or the model) writes is characters on the screen and never an element.
   */
  it('shows a tag as text and never as markup', async () => {
    const { container } = await renderHq(StopProseComponent, {
      inputs: { text: '# <img src=x onerror="alert(1)">\n\n<script>alert(2)</script>' },
    });

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(screen.getByRole('heading', { name: '<img src=x onerror="alert(1)">' })).toBeInTheDocument();
    expect(screen.getByText('<script>alert(2)</script>')).toBeInTheDocument();
  });
});
