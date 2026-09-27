# RM4 `mobile/parent-broadcasts-manager` — School news, and writing to the department manager

Phase R, DR4/DR5/DR6. The app had **no announcements screen at all** (R8's report, item 1:
`ContentApi.announcements()` had no caller), so this is the screen RM2's feed was built for, plus the parent's
side of the manager conversation. Nothing new was added to the flag set: the feed sits behind `announcements`,
the key the feature it supersedes already carried, and the picker behind `chat`.

## What the app reads

| Call | Shape | Note |
|---|---|---|
| `GET /children/{id}/broadcasts` | `BroadcastFeed` | `unread` plus the newest live rows. **404 while the flag is off**, with `code = not_found` — that is the "not enabled" state, not an error |
| `POST /children/{id}/broadcasts/{bid}/read` | `BroadcastView` | answers the row it changed, so the list is patched rather than refetched |
| `GET /children/{id}/managers` | `ChatThread[]` | the same unwritten thread row `/coordinators` answers; `subject` is null and `className` is the **child's section**, not a department |

`childBroadcasts`, `markBroadcastRead` and `childManagers` were already on `ContentApi` (RM2) with no implementation
on either side; this PR adds both — `RemoteContentApi` and the `FakeContentApi` mirror — so the app still runs
with no server.

## Per screen

* **School news** (`feature/broadcasts`, `Routes.Broadcasts`) — *This week's plan* pinned, then *Announcements*,
  *Events* and any earlier plan that has not expired. The week is snapped to **Sunday** exactly as the server snaps
  `weekStart`, so "this week" means the same on both sides, and an expired row is dropped again against the device
  clock (a screen left open must not keep showing an event that finished at noon). The author line comes from
  `authorRole` with `subject`/`curriculum`: *From your Math coordinator*, *From the British department manager*,
  and the author's own name when the row claims neither. EN/AR body by app language with Arabic falling back to
  English; a tap marks read and patches the row in place; unread is **weight plus a New chip, never colour** (§7);
  pull to refresh; the attachment opens in the platform viewer through `LocalUriHandler`, resolved against the API
  host because RM2's attachment is a reference (`/media/…`), not an upload.
* **Parent home** — a `📣 School news · 3` button inside `FeatureGate(Flags.ANNOUNCEMENTS)`. A parent has no bell
  (runbook "Broadcasts"), so the count rides on the home load; a school without the flag 404s and the badge is zero.
* **The picker** (R8's `CoordinatorPickerScreen`, now titled *Start a conversation*) — a second headed section,
  *Department manager*. The two lookups are independent: one 404 must not empty the list the other answered.
* **Thread list and conversation** — a third group for the manager, rows reading `Department manager · British`
  (the row carries the child's *section*, so the department word comes from her curriculum), and the complaint
  switch is now offered to a manager too, which is what the server's `requireTopic` already accepted.

**Not cached on the device.** Chat is not either, the feed is evaluated server-side on every read, and a stale
weekly plan is worse than an empty screen. If the planner wants offline broadcasts it is its own package.

## Verified against a local H2 server, `full` seed

`SPRING_PROFILES_ACTIVE=h2 LLM_PROVIDER=fake SEED_PROFILE=full FAKE_AUTH=true` with `SEED_STAFF_PASSWORD` set to a
value generated for this run and never printed. The British manager in that profile is **Huda Salem**
(`manager.a@school.test`); sign-in is `POST /auth/sign-in`, not `/auth/login`.

```
POST /management/broadcasts   weekly_plan + event   → 201, authorRole MANAGERIAL, curriculum british
POST /coordinator/broadcasts  announcement          → 201, authorRole COORDINATOR, subject math
GET  /children/{id}/broadcasts                      → unread 3, the three rows above
POST /children/{id}/broadcasts/{bid}/read           → read=true, unread 2
GET  /children/{id}/managers                        → Huda Salem, MANAGERIAL, className "1A British", subject null
POST …/chat/threads/{manager}/messages topic=complaint → 201; the row reads topic=complaint, status=open
PUT  /admin/schools/default/flags/announcements false → GET …/broadcasts = 404 not_found  ← the "not enabled" state
```

### On the emulator (Pixel_9, `emulator-5554`)

`assembleQaDebug -Pquest.fakeAuth=true -Pquest.qa.apiBaseUrl=http://10.0.2.2:8080`, installed as
**`app.homeworkquest.qa`** (the qa flavour's own package — a stale `app.homeworkquest` from an earlier session cost
this run twenty minutes).

**#159 holds: the flags reach the app for the `default` school.** Parent mode drew both the `announcements`-gated
*School news* button and the `chat`-gated *Messages* button, which R8 could not (`SchoolSessionImpl` used to drop
the default school). The feed, the read mark, the picker's two sections and the manager conversation all ran
against the local server end to end — screenshots `60`–`64` in `docs/screenshots/mobile-rm4/`, `54`/`55` being the
desktop screenshot tests (EN and AR).

Two things the emulator showed that are **not** RM4's and were left alone:

1. `MapScreen`'s *Grown-ups* link sits below the last map item and the scroll stops short of it at the default
   density — reaching parent mode needed `wm density 320`. It predates this PR.
2. `ChatConversationScreen`'s header is not inside `ParentShell`, so it draws under the status bar (visible in
   `64`). Also pre-existing; R8's screenshot `50` has it too.

## Left for the planner

1. **Add a child has no school-code or class-code field on this build**, so a child created in the app is unplaced
   and had to be attached to a section through `POST /admin/classes/{id}/roster/attach`. That is D16 slice 3
   territory, not RM4's, but it means a parent on a real phone cannot place her own child today.
2. **`~700` non-test lines against the brief's `~450`.** The screen is new from scratch (336 lines, about a third
   of it KDoc in house style) and the `FakeContentApi` mirror is another 60. Nothing was cut that the brief asked
   for; if the number matters more than the coverage, the earlier-plans group and the AR seed rows are what would go.
3. The legacy `ParentAnnouncement` list still has no caller and now never will — the feed carries a coordinator's
   announcements because the composer mirrors into both. `ContentApi.announcements()` is dead weight the contract
   could drop.
