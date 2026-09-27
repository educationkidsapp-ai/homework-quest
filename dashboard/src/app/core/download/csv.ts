/**
 * Rows built in the browser as a spreadsheet Excel will open correctly.
 *
 * Two details, both learned the hard way and both kept in one place rather than in each screen
 * that exports something:
 *
 * - **A byte-order mark and `\r\n`.** Excel on Windows reads a bare `\n` UTF-8 CSV as one column
 *   of mojibake, and an Arabic roster is exactly that case.
 * - **A tab before a leading `=`, `+`, `-` or `@`.** A child called "-Ali" is a formula to Excel,
 *   and a file a manager opens from her desktop is precisely where that matters.
 *
 * The rows are the ones already on the screen, never a second read that could disagree with
 * them — `/coordinator/**` and `/management/**` publish no `.csv` of their own.
 */
export function csvOf(headers: readonly string[], rows: readonly (readonly string[])[]): string {
  const lines = [headers, ...rows].map((row) => row.map(quote).join(','));
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

function quote(field: string): string {
  const safe = /^[=+\-@]/.test(field) ? `\t${field}` : field;
  return `"${safe.replace(/"/g, '""')}"`;
}
