# Feature 29 — Parent language bridge

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** server, mobile app (parent), dashboard (Messages, Complaints) ·
**Depends on:** feature 24 (`AiService`) · **Feature switch:** `languageBridge` (camelCase, like the other
switches). Translating a message also needs `chat`; translating a broadcast or an announcement also needs
`announcements`.

**Leftovers:**
- **A rejected AI answer is no longer saved to disk.** Since the review fixes, a rejected answer about a message is
  not written to `data/llm-failures`. Only the error list is logged, and the `ai_calls` ledger keeps numbers only.
- **Translation quality needs the real model on QA.** Tests use the fake model.
- **The weekly plan page has no Translate action yet.** Chat, broadcasts, announcements and complaint replies have
  it.
- **A reading language cannot be cleared back to "none".** A parent picks Arabic or English to stop translating.
- **The cache key is taken after phone and email masking.** Correcting only a phone number reuses the old
  translation.
- **The limit of 60 translations an hour counts cached answers too.**
- **The app's interface stays in Arabic and English.** Only the translated content uses the reading language.

**The owner's rule, which decides every detail below:** *"The original text is always stored and shown as the record;
translations are cached per language and marked as machine-translated."*

## Goal

A parent who reads neither Arabic nor English well reads the school's messages in her own language, and a member of
staff reads what that parent writes. Nothing the school sent is ever replaced: a translation is an addition the reader
asks for, labelled as a machine translation, with the original one tap away.

## Where to look first

- Chat and complaints (milestones 04 and 08): `chat/ChatService`, `chat/ComplaintService` — who may read which
  thread is already decided there, per role, and this feature adds no rule of its own.
- Broadcasts and announcements (milestones 06 and 07): `broadcasts/BroadcastService.forChild` and
  `teacher/AnnouncementService.forChild` — what a child's feed shows.
- Feature 24's `AiService`, `AiTask`, `AiInputGuard` and the `ai_calls` ledger.
- Feature 22 (`VoiceDraftService`) for the shape of one AI call behind one endpoint.

## Behaviour

### 1. Reading language

- `parents.reading_language` (V51, additive, null until she picks one).
- `PUT /parent/me/reading-language {"lang":"ur"}`, and `readingLanguage` is part of `GET /parent/me`.
- The options are **ar, en, ur, hi, bn, fil, fr and id** — Arabic, English, Urdu, Hindi, Bengali, Filipino, French
  and Indonesian, which is what the pilot school's families actually read. Anything else is `400`.
- The app's own interface stays Arabic and English. Only the *content* of a message is translated.

### 2. Translate

- **Parent:** `POST /children/{id}/translate {"kind":"chat|broadcast|announcement|complaint","id":"…","lang":"ur"}`.
  The source must be something **this parent may read for this child** — a message in her own thread, a broadcast or
  an announcement in this child's feed, a message in her own complaint. Anything else is `404`, never `403`: whether
  another family's message exists is not hers to learn.
- **Staff:** `POST /staff/translate {"kind":"chat|complaint","id":"…","lang":"en"}` — a message in a thread or a
  complaint **the caller may already read**, decided by her own role's existing checks (a teacher's sections, a
  coordinator's reach, a manager's department, the floor supervisor's floors, the Admin's own threads). A teacher of
  another class is `404`.
- Both answer
  `{"text":"…","lang":"ur","machine":true,"original":"…","sourceKind":"chat","sourceId":"…"}`.
  `machine` is always `true`, because every translation here is one.
- Which text is translated: a chat or complaint message is its `body`; a broadcast is its title and the one of
  `bodyAr`/`bodyEn` that is *not* the target language; an announcement is the same rule without a title.
- **The original row is never written to.** The translation lives in its own table.

### 3. Cache

- V51 `translations(id, school_id, source_kind, source_id, source_hash, lang, text, created_at)`, unique on
  (`source_kind`, `source_id`, `lang`, `source_hash`).
- `source_hash` is the SHA-256 of the exact text that was sent, so **an edited source is translated again** while the
  old row stays harmlessly beside it, and **a cache hit makes no AI call at all**.
- Each language is its own row, so Urdu and Hindi never share one.

### 4. The AI call

- Feature `languageBridge`, prompt `Prompts.TRANSLATE`, through `AiService` — so the attempt, its tokens and its
  outcome are in the `ai_calls` ledger like every other feature's.
- `AiInputGuard` masks **e-mail addresses and telephone numbers only**. Names stay: "tell Nada's mother" is the
  content, and masking it would hand the parent a sentence she cannot act on.
- The validator refuses an empty answer, one longer than three times the source plus 200 characters, and one that
  adds a link the source did not have.
- Provider failure is `502 model_failed`; two rejected answers are `422 rephrase`.
- **At most 60 translations an hour per person** (`429 rate_limited`): one reader working through her inbox, not a
  client translating the school.

## Out of scope

- Translating a parent's message as she types it, or translating anything automatically.
- Lesson content, the app's own interface, and PDFs or images.
- Any language outside the eight above.

## Acceptance criteria

- [ ] A parent reads a staff message in her own language, labelled as machine-translated, with the original one tap
      away — and the stored message is unchanged.
- [ ] Asking twice costs nothing: the second answer comes from the cache.
- [ ] Editing the source gets a new translation.
- [ ] A member of staff reads a parent's message in English or Arabic.
- [ ] Her reading language survives a sign-out.

## Tests

- The original message, broadcast and announcement are byte-for-byte unchanged after translating.
- A second request is served from the cache with **zero** `LlmClient` interactions.
- An edited source is translated again; each language is cached separately; `machine` is always true.
- A parent cannot translate another family's message, nor a broadcast that is not in her child's feed (404).
- Staff are limited to the threads and complaints they may read — a teacher of another class is 404.
- An unsupported language is 400; the reading language is saved and comes back on `/parent/me`.
- The 61st translation in an hour is 429.
- The switch off is 404; school isolation, including an `IsolationTest` case for `translations`.
