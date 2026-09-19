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

const HEADING = /^ {0,3}(#{1,6})\s+(.*)$/;
const BULLET = /^\s*[-*]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;
/** `---`, `***`, `___` on a line of their own: a rule, and this renderer draws no rules. */
const THEMATIC_BREAK = /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/;

/**
 * CR4: the emphasis marks, removed rather than rendered.
 *
 * A stop's text is written by hand and rarely carries any; a **converted file's** Markdown is
 * full of them, because that is what a heading in a PowerPoint or a bold run in a Word document
 * becomes. Leaving them in would show a teacher `**Photosynthesis**` where the document said
 * Photosynthesis — raw syntax, which is the one thing the preview exists to prevent — and
 * rendering them would mean `innerHTML`, which is the one thing `parseProse` exists to prevent.
 * So the word survives and its decoration does not, which is the right trade for a page whose
 * question is "what does the AI read?" rather than "how did it look?".
 *
 * Only paired, non-spaced runs are touched, so `a * b` and `snake_case_name` are left alone:
 * a lone `*` is arithmetic far more often than it is emphasis.
 */
const INLINE_MARKS = /(\*\*|__|\*|`)(?=\S)([\s\S]*?\S)\1/g;

function stripInlineMarks(text: string): string {
  let previous = text;
  // Nested runs (`**a `b`**`) need more than one pass; three is past anything a document produces.
  for (let pass = 0; pass < 3; pass += 1) {
    const next = previous.replace(INLINE_MARKS, '$2');
    if (next === previous) return next;
    previous = next;
  }
  return previous;
}

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

    if (THEMATIC_BREAK.test(line)) {
      flush();
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flush();
      // Four `#` and deeper are clamped: this editor offers three rungs, and a literal `####`
      // on the page would be exactly the raw syntax the renderer is here to absorb.
      const level = Math.min(heading[1]!.length, 3) as 1 | 2 | 3;
      blocks.push({ kind: 'heading', level, text: stripInlineMarks(heading[2]!.trim()) });
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
      items.push(stripInlineMarks((bullet?.[1] ?? numbered![1]!).trim()));
      continue;
    }

    flushList();
    paragraph.push(stripInlineMarks(line.trim()));
  }

  flush();
  return blocks;
}
