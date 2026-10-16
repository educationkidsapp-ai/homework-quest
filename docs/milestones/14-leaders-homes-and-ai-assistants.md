# Milestone 14 — Leaders' homes and their AI assistants

**Finished:** 2026-10-15 · **Status:** live on QA, each switch off until the school turns it on · **Area:** dashboard (Admin, Manager), server

## Goal

Give the Admin and the department managers a home page that answers one question — what needs me today? — and three
assistants that save them the reading and the typing.

## What was delivered

### "What needs me today?" (feature 21)
- **The Admin's and the manager's home opens with what needs a decision today:** attendance, the requests waiting
  for her, complaints past their time, and unread messages.
- **Each card leads straight to the screen that acts on it.**

### A voice note becomes an announcement (feature 22)
- **A leader speaks a note on her phone or at her desk and gets a formal announcement in Arabic and English**, which
  she reads, corrects and sends.
- **The speech never leaves her device** — the platform receives text only.

### The morning brief (feature 25)
- **From 07:15 each school morning, her home opens with a short brief of yesterday and today**, in Arabic and
  English, for her own academy.
- **Every number in it is the platform's own count.** The assistant is allowed to phrase the facts and nothing else:
  a sentence carrying a number the records do not hold is thrown away, and a plain bilingual summary is shown
  instead. No invented figure can reach a leader.
- **The brief sits above the cards:** the brief says where to look, the cards act.

### The document reader (feature 26)
- **She uploads a circular — a PDF, a Word file or a photograph of a letter — and reads what it says in both
  languages**, with its dates, the actions it asks for, and a draft announcement she can send on.
- **The file itself is never stored.** Only the summary is kept, and only for 30 days.

### Complaint triage (feature 27)
- **A new complaint arrives already sorted for the staff who answer it:** its category, how urgent it is, a short
  summary in both languages, and a suggested reply.
- **The suggestion is never sent automatically.** A member of staff edits it and sends it herself.
- **An urgent complaint is raised to the department's manager** the moment it is classified.
- **The parent's complaint is unaffected** by whether the assistant succeeds, is slow or is switched off.

## Business value

- A leader's first five minutes are spent deciding, not searching.
- A twelve-page circular becomes four lines and a date, in the language she reads.
- Urgent complaints surface on their own instead of waiting their turn.
- Every assistant costs nothing when it is switched off, and every one of them falls back to something true when
  the model fails.

## Who benefits

The Admin and the department managers. The staff who answer complaints get the context before they start typing,
and parents get faster answers to the complaints that matter most.
