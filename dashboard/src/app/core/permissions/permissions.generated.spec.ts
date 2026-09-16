import { describe, expect, it } from 'vitest';
// The server's own matrix. Imported rather than copied so this cannot describe a past version
// of it: `permissions.json` is a contract file and moves without this workspace being touched.
import matrix from '../../../../../server/src/main/resources/permissions.json';
// The same function `pnpm gen:permissions` runs (typed by tools/gen-permissions.d.mts): a spec
// that re-derived the set its own way would prove nothing about the file that ships.
import { writePermissions } from '../../../../tools/gen-permissions.mjs';
import { WRITE_PERMISSIONS } from './permissions.generated';

describe('permissions.generated.ts', () => {
  it('is what the server’s matrix says — re-derived, not trusted', () => {
    expect([...WRITE_PERMISSIONS].sort()).toEqual(writePermissions(matrix));
  });

  /**
   * The bug this file exists to prevent: `usage.platform` and `usage.school` are pure reads,
   * and the string heuristic they replaced ("anything not ending `.read`") called them writes —
   * so an Admin viewing as a Managerial user lost "School usage", which is most of the reason
   * to use View-as at all.
   */
  it('counts a GET-only permission as a read', () => {
    for (const read of ['usage.platform', 'usage.school', 'billing.read', 'platform.manage'])
      expect(`${read}:${WRITE_PERMISSIONS.has(read)}`).toBe(`${read}:false`);
  });

  it('counts a permission with any non-GET endpoint as a write', () => {
    for (const write of ['lesson.publish', 'user.invite', 'theme.write', 'flag.write'])
      expect(`${write}:${WRITE_PERMISSIONS.has(write)}`).toBe(`${write}:true`);
  });
});
