#!/usr/bin/env node
/**
 * Placeholder for the generated API client.
 *
 * P3.1 replaces this with an `openapi-generator-cli` (typescript-angular) run over
 * `server/openapi.json` into `src/app/api/generated/` (git-ignored, run from `postinstall`
 * and in CI). `server/openapi.json` is produced by the backend worker's `OpenApiExportTest`
 * in P1.1 and does not exist yet, so this script is deliberately a no-op that succeeds:
 * `pnpm install` must not fail on a repository state where the contract is not published.
 */
process.stdout.write('openapi.json not yet published — P3.1\n');
