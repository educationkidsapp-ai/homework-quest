# R8 `mobile/parent-coordinator` — parents message the coordinator

Phase R, DR3. The parent app can now open a conversation with the **subject coordinator** of her child's section,
mark that first message a **complaint**, and see the coordinator's **Resolved** on the row and in the conversation.
Everything sits behind the existing `chat` flag; nothing new was added to the flag set.

## What the app reads

| Call | Shape | Note |
|---|---|---|
| `GET /children/{id}/coordinators` | `ChatThread[]` | **not** a coordinator DTO — the same row as a thread, `id` null until the parent writes. `subject` names what she holds |
| `POST /children/{id}/chat/threads/{staffUserId}/messages` | `{body, clientId, topic?}` | `topic: "complaint"` is read **only** on the message that creates the thread; to a teacher it is `400 complaint_needs_coordinator` |
| `GET /children/{id}/chat/threads` | `ChatThread[]` | now carries `staffRole`, `topic`, `status`, `resolvedAt`; a coordinator row appears once a thread exists |
| `/ws/chat` | `{"type":"status", threadId, status, at}` | the field is **`at`**, not `resolvedAt`. `ChatSocketClient` already decoded it into `ChatFrame.Status`; R8 handles it |

All four R4 fields have contract defaults, so a row from a server older than R4 decodes to *teacher, question, open* —
the C3 screens read unchanged. `AppJson` keeps `ignoreUnknownKeys = true`, so nothing here is a breaking wire change.

## Per screen

* **Thread list** (`ChatThreadsScreen`) — two headed groups, *Your child's teachers* and *Subject coordinators*.
  Each row reads `Subject coordinator · Math · 1A British`, with a **Complaint** badge, an **Open**/**Resolved**
  chip (only where a thread exists) and the unread count. A `status` frame updates the row in place, with no refetch —
  `applyStatus` in `feature/chat/domain`. A `+ Message a coordinator` button sits at the foot.
* **Coordinator picker** (`CoordinatorPickerScreen`, `Routes.ChatCoordinators`) — the current child's coordinators
  from the new endpoint, subject shown, no status chip (nothing exists to have a status yet).
* **Conversation** (`ChatConversationScreen`) — the header names the role and subject and carries the Complaint
  badge; a **Resolved** banner appears above the messages and the composer **stays live**, because the server accepts
  a parent reply on a resolved thread (verified: `HTTP 201`). A `This is a complaint` switch is offered only while
  the thread does not exist yet. `complaint_needs_coordinator` is shown as its own translated sentence.
* **i18n** — EN/AR for all of it, parent-mode tokens only, `clearAndSetSemantics` on each row so a screen reader
  hears the chips as words.

## Verified against a local H2 server

QA has no parent password in reach (`qa-e2e.env` holds the staff ones only), so the round trip was run against the
**local H2 server with the `full` seed** (`FAKE_AUTH=true`, `LLM_PROVIDER=fake`, `SEED_PROFILE=full`), which is the
fallback the brief allows. The `full` profile's math coordinator is **Rasha Kamal**, not Lina — Lina belongs to the
`acceptance` profile.

```
GET  /children/{id}/coordinators        → 2 rows, staffRole COORDINATOR, subject math / english
POST …/threads/{coordinator}/messages   → 201, thread created with topic=complaint
POST …/threads/{teacher}/messages       → 400 complaint_needs_coordinator
GET  /coordinator/complaints            → the thread, unread 1          (as coordinator.math@school.test)
PATCH /coordinator/chat/threads/{id}/status {"status":"resolved"}
GET  /children/{id}/chat/threads        → status resolved, resolvedAt set
POST …/messages (resolved thread)       → 201   ← the composer may stay enabled
/ws/chat (parent)                       → {"type":"status", threadId, status, at}
```

### The emulator, and how far it got

Pixel_9 (`emulator-5554`), `assembleQaDebug -Pquest.fakeAuth=true -Pquest.qa.apiBaseUrl=http://10.0.2.2:8080` — the
first build without that URL silently used `FakeContentApi` (`apiFields` in `androidApp/build.gradle.kts` sets
`USE_FAKE_API` when no flavor URL is given), which is worth knowing before anyone repeats this. Against the local
server the app signed in, joined `HQ0001`, resolved class code `EC2AVJ` to **1A British · Grade 1**, created Maya
(`POST /children -> 201`) and reached parent mode with her placed.

**It could not open Messages, for a reason that is not R8's.** The tile sits inside `FeatureGate(Flags.CHAT)`, and
`SchoolSessionImpl` line 99 — `schoolId.takeIf { it.isNotBlank() && it != DEFAULT_SCHOOL } ?: return forget()` —
never fetches flags or a theme for the school whose id **is** `default`, which is the only school the `full` seed
makes. So on a local H2 server the `chat` flag can never reach the app, whatever the Admin sets. Reaching the
Messages screens on an emulator needs a second school (QA has two), or a parent password for QA, which this session
does not have. The screens themselves are covered by the desktop screenshot tests, and the contract by the curl run
above.

Screenshots: `docs/screenshots/mobile-r8/` — `49*`/`51*`/`52`/`53` are the screenshot tests (EN and AR), `60`/`61`
the emulator against the local server.

## Left for the planner

1. **Announcements (deliverable 4) is not in this PR.** The app has **no announcements screen at all** — `feature/school`
   has flags and theming only, and `ContentApi.announcements()` has no caller. It is a screen from scratch, not a
   change to one.
2. **`ParentAnnouncement` carries no author role or subject** (`id, teacherName, teacherPhotoUrl, bodyEn, bodyAr,
   publishedAt, expiresAt`). A coordinator's announcement reaches the parent through the same read, but nothing on the
   payload says it came from a coordinator. That needs a server/contract change before the app can label it.
   `AnnouncementService.forChild` already drops expired rows server-side.
3. **`GET /children/{id}/chat/threads` drops a coordinator's subject** — `ChatService.parentThreads` passes `null` for
   `subject` on the coordinator rows, although `/coordinators` fills it. The list row therefore reads
   `Subject coordinator · 1A British` once a thread exists, and `Subject coordinator · Math · 1A British` before it.
4. `ContentApi.parentCoordinators` is the one `shared-api` addition — a defaulted method, needed because the app talks
   to the server only through that interface and `FakeContentApi` mirrors it.
