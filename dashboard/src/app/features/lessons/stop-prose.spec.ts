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
    expect(parseProse('> quoted\nplain again')).toEqual([{ kind: 'paragraph', text: '> quoted\nplain again' }]);
  });

  // ---- CR4: the same renderer, over a converted file's Markdown ------------------------------

  it('clamps a deeper heading rather than printing its hashes', () => {
    expect(parseProse('#### four hashes')).toEqual([{ kind: 'heading', level: 3, text: 'four hashes' }]);
  });

  it('keeps a converter\u2019s page headings, which are what a teacher navigates by', () => {
    expect(parseProse('## Page 2\n\nShapes we know')).toEqual([
      { kind: 'heading', level: 2, text: 'Page 2' },
      { kind: 'paragraph', text: 'Shapes we know' },
    ]);
  });

  it('keeps the word and drops its emphasis marks', () => {
    expect(parseProse('**Shapes** we `know`, and 3 * 4 = 12')).toEqual([
      { kind: 'paragraph', text: 'Shapes we know, and 3 * 4 = 12' },
    ]);
    expect(parseProse('- __one__ apple')).toEqual([
      { kind: 'list', ordered: false, items: ['one apple'] },
    ]);
  });

  it('leaves a name with underscores in it alone', () => {
    expect(parseProse('the file is lesson_one_final.pdf')).toEqual([
      { kind: 'paragraph', text: 'the file is lesson_one_final.pdf' },
    ]);
  });

  it('draws no horizontal rule, because it has none to draw', () => {
    expect(parseProse('one\n\n---\n\ntwo')).toEqual([
      { kind: 'paragraph', text: 'one' },
      { kind: 'paragraph', text: 'two' },
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
