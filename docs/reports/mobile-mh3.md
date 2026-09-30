# MH3 `mobile/parent-plans-announcements` — Weekly plan and Announcements, plan images, the parent's phone

The owner's manager list 2, items 6–7 on the parent side. RM4's single **School news** screen is two screens with two
badges, the weekly plan is the manager's **image** rather than a line of text, and a parent can finally give the school
her mobile number from the app.

## What the app reads

| Call | Shape | Note |
|---|---|---|
| `GET /children/{id}/weekly-plans` | `WeeklyPlanArchive` | twelve weeks, newest first, **past weeks included**, with the parent's own `unread` — her badge, since a parent has no bell |
| `GET /children/{id}/broadcasts` | `BroadcastFeed` | unchanged since RM4 — and it **still carries the weekly plans** (see below) |
| `GET /media/attachments/{id}` | bytes | **authenticated.** 200 with the parent's bearer, **401 without**, verified by curl |
| `GET \| PATCH /parent/me` | `ParentProfile` | her address (read-only, it is her Firebase identity) and her mobile number |

`childWeeklyPlans`, `parentProfile` and `updateParentProfile` were already declared on `ContentApi` by MH1 with no
implementation on either side; this PR adds both — `RemoteContentApi` and the `FakeContentApi` mirror — so the app still
runs with no server. **No `shared-api` change was needed.**

### The plans are filtered client-side, and that is the server's doing

`BroadcastService.forChild` answers *every* live row for the child's section, plans included, and MH1 did not change
that. Verified on the local server: with one weekly plan posted and nothing else, `GET /children/{id}/broadcasts`
answered `unread 1` and one `weekly_plan` row. So `announcementRows` drops `kind == weekly_plan` in
`BroadcastGroups.kt`, and the Announcements badge is counted from the rows (`unreadAnnouncements`) rather than taken
from `BroadcastFeed.unread`, which counts the plans that belong to the other badge. If the planner would rather the
server stopped sending plans on that feed, it is a one-line predicate there and this filter can go.

## Per screen

* **Parent home** — RM4's one `📣 School news · 3` button is **`🗓️ Weekly plan · 1`** and **`📣 Announcements`**, both
  inside the same `FeatureGate(Flags.ANNOUNCEMENTS)`, each with its own count. Two requests on the home load, both only
  when the flag is on, because `announcements` is off in `DEFAULT_FLAGS` and asking first would cost every school a
  refused request per load.
* **Weekly plan** (`Routes.WeeklyPlan`, new) — this week's plan for the child's grade pinned as the **image**; earlier
  weeks below as collapsed rows that expand to their own image on a tap. The author line is RM4's
  (*From the British department manager*), the pinned plan is marked read the moment the page opens — it is on the
  screen — and an earlier week when she opens it. Pull to refresh. The image opens **full screen on a tap**, pinch to
  zoom 1×–6×, drag to pan, tap to close.
* **Announcements** (`Routes.Broadcasts`) — RM4's list minus the plans. Expiry was already hidden. An **image**
  attachment is now drawn in the card the same way; #171's external `http(s)` link still opens in the platform viewer;
  anything else is still named with *Available on the dashboard*, which is the honest answer for a document.
* **Settings** — a **Mobile number** section between Language and Change PIN, shown once `GET /parent/me` has answered.
  `quest.feature.parent.domain.Phones` is a line-by-line mirror of the server's `Phones`: separators dropped, leading
  `00` → `+`, a `+` anywhere but the front refused, seven to fifteen digits. What is sent is the **normalised** number,
  so "Saved" means the field on the screen.

## How the image is fetched and cached

`AttachmentImageStore` (`feature/broadcasts/data`) resolves the root-relative `attachment.url` against the API base and
fetches it with `getAuthedBytes` — the parent's bearer, refreshed once on a 401, exactly as `LessonImages` fetches a
stop's page. Handing the URL to the platform viewer would send no token and land on a 401, which is what RM4's
"available on the dashboard" line was standing in for.

The bytes are written to the app's private media directory under the **`attachments` row id** (`MediaFiles.pathOf`, a
new one-line `expect`/`actual` on all three platforms), and a later read is served from there with no request. The id is
a server-minted key that never names different bytes — a re-posted plan is a new row — so there is nothing to
invalidate and no ETag to keep. A row written before MH1 carries a URL the composer typed and no id: nothing is fetched
for it. Decoded bitmaps are kept per id for the life of the process, as `SchoolLogo` does.

**The archive JSON is cached too, and only it** (`BroadcastsRepositoryImpl`, in `SettingsStore` per child). Without it
the page offline was an error card and the cached image was unreachable. The fallback fires **only when the request
could not be made** — a network failure; a 404 (the flag went off) or any other answer the server actually gave
replaces what the device remembers. The announcements feed is still not cached: RM4's reason holds.

## Verified against a local H2 server, `full` seed

`SPRING_PROFILES_ACTIVE=h2 LLM_PROVIDER=fake SEED_PROFILE=full FAKE_AUTH=true`, `SEED_STAFF_PASSWORD` generated for the
run and never printed. The British manager is **Huda Salem** (`manager.a@school.test`). `announcements` had to be turned
on first (`PUT /admin/schools/default/flags/announcements {"enabled":true}`) or `POST /management/broadcasts` is a 404 —
the flag interceptor failing closed.

```
POST /media/attachments            plan.png  → 201 {id, name, type: image/png, sizeBytes}
POST /management/broadcasts        weekly_plan + grade 1 + attachmentId → 201, title written by the server
GET  /children/{id}/weekly-plans   → unread 1, one week, attachment {id, type image/png}
GET  /children/{id}/broadcasts     → unread 1, the plan  ← the feed still carries it
GET  /media/attachments/{id}       → 200 image/png with the parent's bearer; 401 without
POST /children/{id}/broadcasts/{id}/read → read true; the archive's unread drops to 0
PATCH /parent/me {"phone":"+971 50 123 4567"} → "+971501234567"
PATCH /parent/me {"phone":"050+1002030"}      → 400 "phone may only begin with a +."   ← the client refuses it first
```

### On the emulator (Pixel_9, `emulator-5554`)

`assembleQaDebug -Pquest.fakeAuth=true -Pquest.qa.apiBaseUrl=http://10.0.2.2:8080`, installed as `app.homeworkquest.qa`.
Parent mode drew **Weekly plan · 1** and **Announcements**; opening the plan page downloaded the PNG with the bearer and
drew it, the badge was **gone on the next launch**, the full-screen viewer filled the screen, an earlier week expanded
and lost its New chip, and the Announcements page showed the announcement and the event with the plan **not** on it.
With the server killed, a cold start still drew the pinned plan from the archive cache and the image from the file
cache. Screenshots `60`–`67` in `docs/screenshots/mobile-mh3/`; `47`, `54*`, `56*` are the desktop screenshot tests.

## What the #171 redesign changed under this PR

Rebased onto `e3f72f6` (the universal dashboard theme and bottom navigation). Four files conflicted:

1. **`ParentHomeScreen`** — the home keeps its quick-actions list, so the two entries went in where RM4's one was; the
   route now feeds `ParentShell` a `DashboardTab.HOME` and a tab handler, and `onWeeklyPlan` was added to both
   signatures.
2. **`ParentGraph`** — `Routes.WeeklyPlan` is a **detail page with a back arrow and no bottom bar**, like Calendar and
   Progress: the bar's four tabs are Home, Announcements (`NOTIFICATION`), Messages and Settings, and the plan is none
   of them.
3. **`BroadcastsScreen`** — #171's tabbed `ParentShell` and its tappable external-link chip were both kept; the title is
   now `strings.announcements` and an image attachment is drawn above that chip's branch.
4. **`BroadcastGroups`** — #171 had added a **Saturday rule** (on Saturday, pin next week's plan if it exists). That rule
   is about plans, so it moved with them into `weeklyPlans()`, with #171's test, and the announcements feed no longer
   pins anything at all.

#171 also dropped `register`/`signInWithGoogle` from `AuthProvider`; the fake in `AttachmentImageStoreTest` follows.
Nothing in the new design system needed restyling here: `ParentCard`, `SectionTitle`, `ParentButton` and `Chip` are
themed centrally, so the new page picked up the rounded dashboard cards for free, and `BroadcastCard` — which #171 left
on `Palette.parent*` — is what `PlanCard` matches.

## Tests

`./gradlew :shared:desktopTest` (which runs `checkFeatureGates`, `ArchitectureTest` and `:shared-ui:checkTokens`) and
`:androidApp:assembleQaDebug` both green; `xcodebuild … -sdk iphonesimulator` green **with `ARCHS=arm64
ONLY_ACTIVE_ARCH=NO`**. New: `WeeklyPlansTest` (grouping, the grade tie-break, the Saturday rule, `isImage`, the a11y
line), `WeeklyPlanCacheTest` (offline fallback, per child, a server answer never papered over),
`AttachmentImageStoreTest` (bearer, base-URL resolution, the cache hit with no second request, nothing fetched without
an id or a token, the path-safe cache name), `PhonesTest` (the server's rule, case by case), and the Weekly plan view
model in `BroadcastsViewModelTest` beside the feed's. All view models are cancelled before `Dispatchers.resetMain`.

### `xcodebuild` needs an explicit arch on this Mac

A plain `xcodebuild … -sdk iphonesimulator build` fails in `:shared:syncComposeResourcesForIos` with *"Could not infer
iOS target architectures"* — the task reads `ARCHS` from the Xcode environment and does not get one. It is not this
PR's: the task fails the same way standalone, and `:shared:compileKotlinIosSimulatorArm64` (which is what
`Media.ios.kt` touches) is green. Adding `-destination 'generic/platform=iOS Simulator' ARCHS=arm64
ONLY_ACTIVE_ARCH=NO` builds it. **Worth checking whether CI's iOS job passes an arch**, and worth a line in the runbook
if it does not.

## Review round (PR #175, `quality-performance`)

Three blockers, one commit each on top of `918e046`.

1. **`4a1c2c3` — the decoded-bitmap cache was unbounded and decoded at full size for the card.** `LruCache`
   (`quest/core`, unit-tested) holds **three** entries — the pinned plan, the one earlier week she has open, and the
   viewer's copy — keyed by attachment *and* decode bound. `decodeBoundedImage` is a new `expect`/`actual`: Android
   downsamples inside the decoder with `inSampleSize`, desktop and iOS share one skiko actual in a new `skikoMain`
   source set. The card asks for 1440 px on the longer edge (more than a 420 dp card can draw); only the viewer asks
   for the whole image, and it decodes bytes the disk cache already holds rather than downloading again.
2. **`c03943b` — a pre-MH1 external image lost #171's tappable chip.** `isImage` now also requires `id != null` — an
   image is one this app can *fetch*, not one whose name ends in `.png` — and the card tests `isWebUrl` first.
3. **`ad0bc7f` — the phone save collapsed three outcomes into one.** The mirror refusing the shape marks the field
   invalid with no request; the server answering `bad_request` (a rule the mirror lacks) also marks it invalid;
   anything else keeps her number in the field under a new *Could not save just now* line (EN/AR). `SettingsPhoneTest`
   covers all three plus load and the clear-on-typing.

Not taken, and the reviewer agreed each is non-blocking: the `require(url.startsWith("/"))` hardening in `absolute()`
(the server only ever writes a root-relative URL beside an id, `BroadcastService.java:442`); clearing the archive key
and the cached images on **sign-out**, which needs either a prefix delete on `SettingsStore` or a listing on
`MediaFiles` and is a follow-up of its own; and `isOffline()` treating a deserialization failure of a 200 as offline,
which is `RemoteContentApi` decoding outside its `try` rather than anything here.

## Left for the planner

0. **Sign-out leaves the archive key and the cached plan images on the device** — a privacy follow-up the reviewer
   raised and agreed was not a blocker. It needs a prefix delete on `SettingsStore` or a listing on `MediaFiles`, so it
   is its own small package rather than a line in this one.
1. **`~700` non-test lines against the brief's `~450`**, the same overrun RM4 reported for the same reason: a screen
   from scratch (about a third KDoc in house style), a full-screen image viewer, a platform `expect`/`actual`, and four
   deliverables in one package. Nothing the brief asked for was cut. The archive cache (~35 lines) was added after the
   emulator showed the page offline was an error card, which deliverable 5 does not allow.
2. **A cached archive can show a stale New chip offline** — the read reached the server, the snapshot on the device
   predates it. Fixing it means writing the read back into the cached JSON; it seemed worse than the noise.
3. **The parent-mode text field cannot show "error" apart from "focused"**: both draw the accent border, so an invalid
   phone reads as invalid only from the sentence under it. A `DashboardTokens` error border would be a design-system
   change, not this PR's.
4. **The home badges refresh on launch and child switch, not on return** — `LaunchedEffect(vm)` does not re-fire when
   the parent comes back from a page. Pre-existing since RM4; both new badges inherit it.
5. The offline children list can show **the same child twice** when the server's ids changed underneath the local
   SQLDelight cache (seen after an H2 restart). Pre-existing, in `ChildrenRepositoryImpl`, not touched here.
