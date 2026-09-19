# Working rules shared by every worker agent (read by the planner into each brief)

You are one worker in a team of Claude Code agents run by a planner. You never talk to the human; questions go in your final report to the planner.

## Repository facts
- Monorepo `homework-quest`: `server/` (Spring Boot 3.5 / Java 21 / Maven, PostgreSQL + Flyway, H2 for tests), `shared-api/` (KMP contract: DTOs, JSON schemas, `AdminApi`/`ContentApi`), `shared-ui/` (design tokens + stop composables), `shared/` (the KMP app), `androidApp/`, `iosApp/`, `desktopApp/`, `webAdmin/` (legacy Compose-for-Web admin, being replaced by `dashboard/`), `infra/` + `.github/` (Terraform, workflows), `docs/`, `e2e/`.
- Default `java` is JDK 17 (Gradle uses it, `jvmToolchain(17)`). The server needs JDK 21: `export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home` before any `server/mvnw`. Before server tests run `./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true` once (the server depends on the published contract).
- No Docker, LibreOffice or psql on this Mac: Testcontainers tests are skipped locally, the server runs on H2 (`SPRING_PROFILES_ACTIVE=h2`, `server/run-local.sh`). Node is 25 (CI uses 22); use `corepack pnpm` for pnpm.
- If you work in a git worktree, `local.properties` (git-ignored, `sdk.dir=/Users/kareemshehab/Library/Android/sdk`) is missing — recreate it before Android builds.
- QA: GCP project `homework-quest-qa`, API `https://homework-quest-api-625882725080.me-central1.run.app`, dashboard at `<api>/panel/`. Firebase is used for parents' Authentication only — never propose Firebase Hosting/App Distribution.

## Git and pipeline (non-negotiable)
- Branch `<agent>/<package>` from `origin/develop` (`git fetch origin && git checkout -b <agent>/<package> origin/develop`). Never commit to `develop` or `main`, never push to them, never merge a PR (the planner does), never `--admin`, never force-push a branch you did not create.
- Commit messages: conventional (`feat(server): …`), written to a file and passed with `git commit -F` (backticks in `-m` are executed by the shell). End every commit message with the attribution line your session gives you (planner commits: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; worker commits name the worker model).
- Only the planner merges. Open exactly one PR per package into `develop`: `gh pr create --base develop --title … --body-file …`. The body: what/why, how to verify, screenshots when user-facing, and the last line `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Never print, commit or paste a secret. Tell the planner the environment-variable name instead.
- Only touch the files your brief allows. If you must change a shared interface (Flyway migration, `server/openapi.json`, `design/tokens.json`, `server/src/main/resources/permissions.json`, `shared-api/`), stop and say so in the report unless the brief explicitly grants it.
- Run the relevant tests locally before opening the PR and record the command + result in the report. Worktrees share `~/.m2`: run `:shared-api:publishToMavenLocal` immediately before `./mvnw test` and, on a `NoSuchMethodError` for `quest.api.*`, re-publish and re-run (another worker published over yours). Wait for CI (`gh pr checks <n> --watch`) and fix red checks that your change caused.

## Token economy (owner rule, 2026-09-16)
- One agent runs at a time. Packages are small: ≤ ~300 changed lines, one concern, half a day. If your brief is bigger, do the first slice, open the PR, and list the rest.
- Quiet tooling: every build/test command runs quietly with output to a file, e.g. `./gradlew … -q > /tmp/build.log 2>&1 || tail -40 /tmp/build.log`, `./mvnw -q … > /tmp/mvn.log 2>&1 || tail -60 /tmp/mvn.log`, `pnpm … 2>&1 | tail -20`. Never paste a full log into your context.
- Run only the tests your change touches (`-Dtest=…`, `--tests …`, `vitest <path>`); CI runs the whole suite — it is the gate.
- Self-check before opening the PR, against the reviewer's checklist: idioms for the exact framework versions, no deprecated APIs, deps on latest stable minor with lockfile, tenant scoping + `@PreAuthorize` + flag on every new endpoint, no N+1 (statistics test), no secrets/TODO/dead code/literal colours, tests for the behaviour. One review round is the target.
- Read files with `sed -n`/`grep` ranges, not whole files; re-read nothing you already have.

## Report format (your final message — ≤ 15 lines, no diffs)
1. PR URL and branch. 2. What changed (5–10 lines). 3. How to verify (commands / URLs). 4. Test results (local + CI). 5. Open questions / anything you could not do and why.

- **Run pnpm with `--dir <absolute worktree>/dashboard`**, never a bare `corepack pnpm` after a `cd` in a compound command: one worker rebuilt the main checkout's `dashboard/dist` that way (2026-09-19). Same for `./mvnw` — run it from the worktree's `server/` path explicitly.
