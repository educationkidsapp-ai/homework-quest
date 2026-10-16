# Milestone 15 — Parents and classrooms

**Finished:** 2026-10-16 · **Status:** live on QA, each switch off until the school turns it on · **Area:** mobile app (parent), dashboard, server

## Goal

Meet the parent where she is — in her own language, at the end of her day — and give the teacher help inside the
lesson she has already built.

## What was delivered

### Remote study days (feature 23)
- **The school declares a remote study day for everyone, for one academy, or for chosen grades**, with a reason.
- **Parents of the classes affected receive one bilingual announcement**, which disappears from the feed by itself
  at the end of that day. Cancelling the day withdraws it and tells nobody twice.
- **Staff in scope check in from home** that day, and the board shows the day as remote.

### The evening card (feature 28)
- **From six in the evening, a parent gets a short note about one child's day** in Arabic and English — what the
  class did, and one thing to ask her about.
- **It never carries a score or a percentage.** The wording is checked against the teacher's own lesson text, and a
  fixed bilingual note is used whenever the assistant's answer does not pass.

### The parent's own language (feature 29)
- **A parent chooses the language she reads in** — Arabic, English, Urdu, Hindi, Bengali, Filipino, French or
  Indonesian — and translates any message, announcement or complaint reply with one tap.
- **Staff translate what she writes**, in the dashboard, in the same way.
- **The original is always the record.** A translation sits beside it, marked as machine-translated, and is cached
  so the same text is never paid for twice.

### Smart hints and targeted practice (feature 30)
- **Every question in a lesson gets two hints, and each weak skill gets one short practice set**, written while the
  lesson is being built.
- **The teacher approves them first.** Nothing reaches a child until she has, and she can edit or reject anything.
- **No child's screen ever talks to the model.** Everything a child sees was written at build time and approved; an
  automatic check on every build proves no child-facing screen can reach it.

## Business value

- A parent who does not read Arabic or English is no longer outside the conversation.
- The evening card turns "how was school?" into a real conversation, without turning it into a grade.
- Hints keep a child moving instead of stuck, in the teacher's own words once she has approved them.

## Who benefits

Parents, in their own language and at the hour they are free. Children, who get help instead of a wrong answer.
Teachers, who get the hints written for them and keep the last word.
