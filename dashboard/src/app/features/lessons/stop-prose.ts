/**
 * CR5: the teacher's stop text, parsed into blocks a template can render.
 *
 * **Why a parser and not a Markdown library.** The text a teacher reads and writes is
 * Markdown-*lite* and nothing more: `#` heading lines, `-` or `1.` list items, and runs of lines
 * between blank lines as paragraphs. That is what `StopText.describe` on the server emits and
 * what a teacher types back, so a full CommonMark implementation would be ~40 kB of bundle
 * (against a 520 kB initial budget) to support link reference definitions and HTML blocks nobody
 * writes here — and the HTML block is precisely the thing that must never render.
 *
 * **Why it is safe.** This returns *data*, never markup. The component renders each block with
 * ordinary interpolation, so `<script>` in a teacher's text is text; there is no `innerHTML`, no
 * `bypassSecurityTrust*` and therefore nothing to escape by hand — Angular escapes it by
 * construction, which is a stronger guarantee than an escaping function somebody can forget to
 * call. `stop-prose.spec.ts` asserts a tag survives as characters.
 */

export interface ProseHeading {
  readonly kind: 'heading';
  /** 1–3: more `#` than that is not a shape this editor offers. */
  readonly level: 1 | 2 | 3;
  readonly text: string;
}

export interface ProseParagraph {
  readonly kind: 'paragraph';
  /** The run's lines joined by `\n`; the component renders them with `white-space: pre-line`. */
  readonly text: string;
}

export interface ProseList {
  readonly kind: 'list';
  readonly ordered: boolean;
  readonly items: readonly string[];
}

export type ProseBlock = ProseHeading | ProseParagraph | ProseList;

const HEADING = /^ {0,3}(#{1,3})\s+(.*)$/;
const BULLET = /^\s*[-*]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;

/**
 * The teacher's text as headings, paragraphs and lists.
 *
 * Deliberately forgiving: a line that matches nothing is prose, so text pasted from a document
 * still reads as itself rather than disappearing. Indentation is dropped — an exit ticket's
 * questions arrive indented two spaces and belong at the same rung as everything else here,
 * because the shape that matters to a teacher is "these are the questions", not the nesting.
 */
export function parseProse(text: string): readonly ProseBlock[] {
  const blocks: ProseBlock[] = [];
  let paragraph: string[] = [];
  let items: string[] = [];
  let ordered = false;

  const flushParagraph = (): void => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', text: paragraph.join('\n') });
    paragraph = [];
  };
  const flushList = (): void => {
    if (items.length > 0) blocks.push({ kind: 'list', ordered, items });
    items = [];
  };
  const flush = (): void => {
    flushParagraph();
    flushList();
  };

  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    if (line.trim() === '') {
      flush();
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flush();
      blocks.push({ kind: 'heading', level: heading[1]!.length as 1 | 2 | 3, text: heading[2]!.trim() });
      continue;
    }

    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    if (bullet || numbered) {
      flushParagraph();
      const isOrdered = numbered !== null;
      // A bullet directly under a numbered run (or the other way round) is a second list, not a
      // continuation: the two say different things about order and must not be merged.
      if (items.length > 0 && ordered !== isOrdered) flushList();
      ordered = isOrdered;
      items.push((bullet?.[1] ?? numbered![1]!).trim());
      continue;
    }

    flushList();
    paragraph.push(line.trim());
  }

  flush();
  return blocks;
}
