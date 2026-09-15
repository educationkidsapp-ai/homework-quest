# features

One folder per feature area, lazy-loaded per role (`/admin/**`, `/teacher/**`, `/management/**`).
Empty in P1.0 on purpose — P3.1 adds the first ones.

The folder exists now because `hq/feature-flag-reference` already watches it: every
`*.routes.ts` in here and every `*.page.ts` anywhere must reference `featureGuard('key')` or
`*hqFeature="key"`, or say why it does not with

```ts
/* hq-flag: none (shell) — why this screen is not gated */
```

A feature that ships without a flag reference is a feature that can only be turned off by a
deploy, which is the thing the flag matrix exists to avoid.
