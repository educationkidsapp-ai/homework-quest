# Local end-to-end suite

`e2e/local/` runs the **built** dashboard against a **local** API, in the shape the container
will have (P3.4): one origin, `/dashboard/**` from `dist/browser`, everything else proxied to the
API, and the same Content-Security-Policy the server sends. `e2e/styleguide.spec.ts` is the other
suite and still runs against `ng serve` (`pnpm e2e`).

Neither suite runs in CI — both need a browser download, and this one needs a JDK, a database and
a seed. They run here and, from P3.4, against QA after a deploy.

## Running it

```bash
# 1. the API on in-memory H2 — JDK 21, no Docker, no Firebase, no model calls
export JAVA_HOME=…/jdk-21…            # the default `java` on this Mac is 17
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true   # once
(cd server && ./mvnw -q -B package -DskipTests)

export ADMIN_EMAIL=admin@quest.local
export ADMIN_PASSWORD='<throwaway>'   # never a real one, never committed
SPRING_PROFILES_ACTIVE=h2 LLM_PROVIDER=fake PORT=18080 \
  PUBLIC_URL=http://localhost:18080 DASHBOARD_URL=http://localhost:4300 \
  java -jar server/target/server.jar &

# 2. two schools, every role, one published lesson each
E2E_BASE_URL=http://localhost:18080 \
E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
E2E_STAFF_PASSWORD='<throwaway>' E2E_PARENT_PASSWORD='<throwaway>' \
  node e2e/seed/seed.mjs

# 3. the bundle the container will serve
cd dashboard && pnpm build --configuration=production

# 4. the suite (Playwright starts e2e/local/serve.mjs itself)
E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… pnpm e2e:local
```

Accounts come from the seed: `admin@quest.local`, `teacher.a@alnoor.test` (Ms Sara, Al Noor,
British Grade 1–2 Math) and `manager.a@alnoor.test`. Passwords are read from the environment and
never printed — a missing one fails with the variable's name, not its value.

## What it proves

| Test | What would break without it |
|---|---|
| sign-in is branded from `PlatformSettings` | a product name baked into the bundle (§A) |
| the bundle runs clean under the API's CSP | an inline script or `onload=` handler leaving the page unstyled |
| one login per role lands on its Home | the role guards and the `/` redirect |
| a wrong password is a band, in place | a 401 read as "your session expired" and a bounce |
| EN/AR flips `dir` without a reload | a cached English sentence in a `computed()` (§6) |
| the Admin rail carries every §6 screen | a nav item gated on a permission the role does not hold |
| the switcher scopes every screen | `X-School-Id` not reaching the API, or the Home not re-reading |
| a teacher is offered nothing of the Admin's | the rail, and the URL behind it |
| `?` opens the sheet, Esc closes it | the keyboard contract (§7) |
| the screenshot set | the RTL mirror, silently |

Screenshots land in `docs/screenshots/dashboard-p3.1/` (1366 × 768, EN and AR) and are committed.

## The static server

`serve.mjs` is deliberately small and deliberately not a dev server: hashed assets immutable,
`index.html` no-cache, SPA fallback, gzip for text, and the CSP. It exists so the suite measures
the artefact that ships rather than a development build — and so Lighthouse numbers taken here
mean something.
