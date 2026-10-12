# Milestone 11 — Push notifications and app lock

**Finished:** 2026-10-03 · **Status:** Android on testers' phones. iPhone is waiting for an Apple Developer account · **Area:** mobile app (parent), server

## Goal

Make sure parents never miss something from the school, while keeping the child's phone safe.

## What was delivered

- **Push notifications on Android** (Firebase Cloud Messaging), for anything a manager, coordinator or teacher sends:
  - a new chat message,
  - an announcement or event,
  - a weekly plan,
  - homework or an exam published,
  - an exam result released,
  - a question to the child,
  - a new complaint, a complaint reply, or a complaint status change.
- **A tap opens the exact page.**
  - A message opens that chat.
  - An announcement opens the announcement.
  - A weekly plan opens the weekly plan.
  - The same applies to every other case.
- **Parent check first.** Before opening the page, the app asks for Face ID, fingerprint, the phone's screen lock or the parent password, so a child cannot open parent content.
- **Notifications page stays in the bottom navigation**, as a full history.
- **App lock works on every Android phone.** It is offered on phones with face unlock, fingerprint or only a PIN or pattern. Parents who updated while already signed in are asked too.
- **Exam countdown timer** for children.

## Business value

- Messages and news reach parents in seconds, which means faster replies and better attendance at events.
- Privacy is protected: parent conversations stay private even on a shared family phone.
- Fewer "I didn't see the message" situations.

## Who benefits

Parents get timely news, and the school reaches families reliably.

## Not yet done

Push notifications on iPhone need an Apple Developer account and an Apple push key. The app code is ready for them.
