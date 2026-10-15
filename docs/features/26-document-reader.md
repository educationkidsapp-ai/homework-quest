# Feature 26 — Document reader

**Status:** Done · **Area:** server, dashboard (Admin, Manager) · **Depends on:** feature 24 (AI service
layer) · **Feature switch:** `documentReader`

**Leftovers:**
- **Summary quality, OCR speed on scanned pages, and whether the 80 kB text cut is right** need the real provider
  and real circulars on QA.
- **A DOCX is page-counted after conversion.** A 50-page DOCX is still refused (422 `too_long`), just after it has
  been read.
- **"Open" on the recent list re-shows the stored summary.** `GET /management/documents/{id}` exists, but the
  dashboard does not call it.
- **A read-only "View as" session has no Document reader.** Reading is a write on the server.
- **Unreadable files return the existing `unreadable_file` error.** Only `too_long` is new.

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

## Goal

A circular arrives from the ministry, the owner or another school — a PDF, a Word file or a photograph of a paper
letter. The Admin or a department manager uploads it and gets back, in seconds, what it says: a summary in Arabic and
in English, the dates it names, what the school has to do, and a one-tap **"Draft announcement"** that opens the
compose sheet she already uses, pre-filled. The AI reads; the human decides and sends.

## Where to look first

- Feature 24's `quest.server.ai.AiService` and `AiInputGuard` — every AI call goes through them.
- The lesson pipeline's Convert step (`analysis/ConversionService`, milestone 02): anydoc for documents, Tesseract
  `eng+ara` for images and for the pages of a scan. Its reusable half is what reads the circular.
- Feature 22 (`docs/features/22-voice-to-announcement.md`): the same "prepare, review, press Send" shape, and the
  same rule that the compose sheet is pre-filled rather than posted.
- Announcements and their audiences (milestones 06 and 07) — Send is the existing `POST /management/broadcasts`.

## Behaviour

1. A **"قارئ المستندات / Document reader"** page for the Admin and department managers. One file at a time:
   **PDF, DOCX, or a JPEG, PNG or WebP photograph**, recognised by its first bytes rather than by its name.
2. Limits, so that one upload can never be the whole server: **at most 10 MB** (`413 too_large`) and **at most 20
   pages** (`422 too_long`).
3. The text is extracted on our own machines: anydoc for the PDF and the Word file, Tesseract `eng+ara` for a
   photograph and for the pages of a PDF that has no text layer. A document with no readable text at all is
   `422 unreadable_file` — "take the photograph again, straight on, in better light".
4. The extracted text goes to the assistant through feature 24's one loop, with the input guard on, and comes back as:
   - a **title**;
   - a **summary** in Arabic and in English (at most 1,200 characters each);
   - the **key dates** it names — at most ten, each a real calendar date with an Arabic and an English label;
   - the **action items** in both languages, at most ten each;
   - optionally a **suggested announcement** (title and both bodies) when the circular is clearly something parents
     must be told.
5. The summary is shown with a **"صياغة إعلان / Draft announcement"** button. It opens the existing compose sheet
   with the suggested announcement in it. **Nothing is sent until she presses Send**, which is
   `POST /management/broadcasts`, unchanged.
6. She can re-open what she has read before: her own last twenty summaries, newest first.

### Privacy

- **The uploaded file is never stored.** It is read from a temporary file that is deleted in a `finally`, and it is
  never written to the bucket. Only the summary is kept.
- E-mail addresses and telephone numbers are masked before the text reaches the provider (`AiInputGuard`).
- A summary is **its author's own**: another manager of the same school does not read it, and another school's is a
  404.
- Summaries are swept **after 30 days**.

## Out of scope

- More than one file per upload, spreadsheets, and the ministry's portal.
- Editing a stored summary, sharing it with another member of staff, or attaching the original to an announcement
  (there is no original to attach).
- Translating the whole circular; this is a summary, not a translation.

## Acceptance criteria

- [ ] A one-page circular, as PDF and as a photograph, becomes a correct bilingual summary with its dates and actions.
- [ ] Every key date is a real calendar date (`yyyy-MM-dd`), and a model that invents one is asked again.
- [ ] An 11 MB file is `413 too_large`; a 30-page file is `422 too_long`.
- [ ] The uploaded file is gone the moment the answer comes back — no bucket object and no temporary file.
- [ ] "Draft announcement" only fills the compose sheet; the announcement goes out when she presses Send.
- [ ] Only the Admin and department managers reach the page; a teacher, a coordinator and a parent are `403`.
- [ ] With the switch off, the routes are `404`.

## Tests

- A PDF and an image are read; the key dates come back as real dates, and an invented date is retried and then
  `422 rephrase`.
- Nothing is left behind: no `FileStore` object and no temporary file after a read, successful or failed.
- `413` over 10 MB, `422 too_long` over 20 pages, `502 model_failed` when the provider fails.
- Role checks (teacher, coordinator, parent → `403`), school isolation (+ an `IsolationTest` case), the flag off → 404.
- A summary of another author or another school is a 404; the 30-day sweep deletes an old one and keeps a fresh one.
- The lesson pipeline is unchanged: `ConvertStepTest` and `AdminPipelineTest` pass untouched.
