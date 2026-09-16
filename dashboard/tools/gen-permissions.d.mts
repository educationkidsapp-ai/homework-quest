/**
 * Types for `gen-permissions.mjs`, so `permissions.generated.spec.ts` can import the very
 * function the generator uses rather than reimplementing the derivation next to it — a spec
 * that re-derives differently proves nothing about the file that ships.
 */

/** The keys whose endpoints include at least one non-GET method, sorted. */
export declare function writePermissions(matrix: unknown): string[];

/** The generated module's source for a set of keys. */
export declare function render(keys: readonly string[]): string;
