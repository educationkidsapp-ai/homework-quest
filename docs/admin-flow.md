# The Admin's dashboard

**Scope of this file.** The Admin came first and her screens are described where they were built —
`docs/runbook.md` for the schools, users, flags and theme routes, `docs/teacher-flow.md` for the
lesson pipeline she shares with a teacher. This file starts where that stopped being enough: the
screens that are hers alone and were added after the flow docs existed. It is not yet a tour of the
whole area.

## Her rail, and the four rows it lost (MA0)

The Admin's rail is **Home · Classes · Teachers · Users · Messages**, plus **Schools** wherever
`multiSchool` is on (D13). Items 6–9 of the owner's admin-role list (2026-09-30) took four rows off
it, in the dashboard only — every `/admin/**` API route behind them is untouched, and the
runbook still describes them.

| Row              | Path                    | What it was                                                     |
| ---------------- | ----------------------- | --------------------------------------------------------------- |
| Feature flags    | `/admin/flags`          | The stub naming phase 3. No flags matrix or audit screen existed |
| All lessons      | `/admin/lessons`        | The real list (`features/lessons/lessons.page.ts`)               |
| Platform usage   | `/admin/usage`          | The stub naming phase 6                                         |
| Platform settings| `/admin/settings`       | The stub naming phase 3                                         |

All four are **redirect rows** in `core/nav/screens.ts` (`ADMIN_RETIRED`), so the four addresses
land on `/admin` rather than on `/not-found` — a bookmark, an old notification's `link` and the
runbook's own URL all still resolve. `nav.flags`, `nav.platformUsage` and `nav.platformSettings`
went from `en.json` and `ar.json` with them; `nav.allLessons` stayed, because the **coordinator's**
rail still carries it.

**What kept the lessons she authors reachable.** `/admin/lessons/new` (the wizard) and
`/admin/lessons/{id}` (the review page) are *not* retired: they are the Admin's authoring pair, and
the server's Home and the bell link straight at the second one
(`core/notifications/notification-target.ts`). The wizard's only door, though, was the retired
list's primary button, so the Home's **quick actions** are now hers — Classes, Teachers and **New
lesson**, filtered by `PermissionService.can()` — instead of the six *teacher* links it had been
showing her since the comp. Her breadcrumb out of a lesson is her Home, not "All lessons".

`lessons.page.ts` keeps its `isAdmin()` branches (the school column, the unfiltered curriculum and
grade lists). They are unreachable for an Admin now and a separate package should take them out;
the screen still serves a teacher, which is why this one left them alone.

## Messages (RM3b, DR5)

`/admin/messages` — "the manager reports to and chats with the admin", from the Admin's side.

| Reads / writes                                                                                                    | What she sees                                                                              |
| ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `GET /admin/chat/threads`, `…/{threadId}/messages`, `POST …/messages`, `POST …/read`, `POST /admin/chat/threads` | The chat screen every other role uses (`features/chat/chat.page.ts`), over `/admin/chat/**` |

Behind the **`chat`** flag and `chat.support` — the key the GET carries. Not `admin.chat`, which is
a *write* (it covers the send): gating the route on it would have taken the inbox away from a
read-only "View as" session instead of only the composer.

**What it can do.** Read a thread, reply in it, mark it read, and open a new one with a manager of
the school from **New message**, which lists `GET /admin/managers`. `POST /admin/chat/threads
{managerUserId}` is the same row `POST /management/chat/threads {adminUserId}` creates from her
side — one thread per pair, whichever of the two asks first.

**What it cannot do.** `GET /admin/chat/threads` is *wider* than her own conversations: it is every
thread of the school, for support. She may **write** only in a thread she is herself on, which the
server checks (`ChatService.ownAdminThread`), so the list will show her a parent ↔ teacher
conversation whose composer the server refuses. The screen's title and subtitle say the list is the
school's and not hers rather than pretending otherwise; narrowing the list is a server change.
There is no delete: a thread is part of the school's record.

### Which school her chat is read in

`/admin/chat/**` is read **one school at a time** — `400 Send X-School-Id: chat threads are read
one school at a time.` — and an ADMIN row carries no `school_id`, so the header is the only thing
that says which. Two cases, and the second is the one that nearly shipped broken:

- **`multiSchool` on.** The header's switcher is rendered, `SchoolScopeStore` holds what she picked
  and the interceptor sends it on every Admin request, as it has since D13. Until she picks,
  `ChatRoutes` gives her no transport and the screen asks her to choose.
- **`multiSchool` off** — which is every deployment we run today. D13 masks the stored scope (an id
  kept from a database that has since been reseeded must not scope anything) **and hides the
  switcher**, so there was nothing to pick and nothing to pick with: the first cut of this screen
  asked her to choose a school and could never load. It now resolves the **one** school from
  `GET /admin/schools` — the call the switcher itself makes, so the answer comes from the server
  and not from `localStorage`, and D13's reason for the mask still holds — and
  `authInterceptor` sends that id on `/admin/chat/**` and on nothing else. Every other Admin read
  stays cross-school with the flag off, which is what D13 is for. A one-school deployment that
  answers two rows is disagreeing with itself: no id, and the screen asks her to pick.

The header's chat icon points here for an ADMIN (it now resolves for all four roles), with the
unread count `ChatService.totalUnread` already keeps.

## Trying it

Full local seed (`SEED_SCHOOL=true`, `multiSchool` off), `chat` on for the school:

```
PUT /admin/schools/{id}/flags/chat {"enabled":true}
```

Sign in as the Admin (`E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`), open **Messages**, and use
**New message** to write to `manager.a@school.test`. She answers at `/management/messages`
(`docs/management-flow.md`).
