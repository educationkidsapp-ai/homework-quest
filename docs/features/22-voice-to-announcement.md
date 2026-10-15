# Feature 22 — Voice note to announcement (stretch)

**Status:** Done · **Area:** dashboard and mobile app (Admin, Manager), server · **Depends on:** — ·
**Feature switch:** `voiceAnnouncement` (camelCase, like the other switches). The button also needs `announcements`.

**Decision:** speech to text runs **on the device**, as the owner chose.
- **Dashboard:** the browser's speech recognition.
- **Android:** `SpeechRecognizer`.
- **iPhone:** `SFSpeechRecognizer`.

All three use `ar-SA`. The server receives text only. No audio file is ever created or uploaded, so nothing needs
deleting.

**Leftovers:**
- **Nothing has run on a real phone yet.** That covers the Android recogniser's restarts through two minutes, the
  microphone and speech permission prompts, and the iPhone path. All of it is checked by build and unit tests only.
- **"Under 20 seconds" and dialect quality are not measured.** They need real devices and the real AI model on QA.
  Recognition quality also varies by phone and browser.
- **Browser limits.** Chrome sends the speech to Google's servers for recognition; our code uploads no audio.
  Firefox has no speech recognition, so the button is hidden there.
- **The Admin picks one academy per announcement.** The composer now lists the school's real academies and grades
  for her. A manager who covers two academies can pick a grade too. There is no "All academies" choice yet; the
  server would accept one if every class were named.
- **The app has no date picker.** The expiry is typed as `yyyy-MM-dd` and checked.
- **The transcript itself can name a child.** No child or parent data goes to the AI from the school's records, but
  whatever the leader says is in the transcript. Unsent drafts and their transcripts are deleted after 7 days.
- **Not deployed yet.** The QA deploy follows the merge into `develop`. The switch is off until the school turns it
  on.

## Goal

A leader records a short voice note in Saudi dialect and gets a formal announcement in Arabic and English,
addressed to the right parents, ready to review and send. The AI prepares; the human always decides.

## Where to look first

- Announcements and events, their audiences and expiry (milestones 06 and 07).
- The existing AI writing assistant used by the lesson builder (milestones 02 and 05) — reuse its provider setup.
- Push notifications for announcements (milestone 11).

## Behaviour

1. On the Announcements page (dashboard) and in the staff app for Admin and Managers: a **"سجّل رسالة صوتية"**
   button. Recording up to 2 minutes.
2. The audio is transcribed (Arabic, including dialect). Use a speech-to-text service behind a small interface so
   the provider can change.
3. The assistant turns the transcript into:
   - a title and a formal announcement in Modern Standard Arabic and in English;
   - a suggested audience from the school's real grades and academies ("أولياء أمور الصف الثالث – الأكاديمية
     البريطانية");
   - a suggested expiry date when the note mentions one.
4. The leader sees an editable preview of all of it, with the transcript underneath.
5. **Send** goes through the existing announcement flow. Nothing is sent without the leader pressing it.

### Privacy
- The audio is deleted after transcription. The transcript is kept only with the announcement draft.
- Children's personal data is never sent to the AI provider.

## Out of scope

- Consent forms with replies, scheduled sending, voice in other features.

## Acceptance criteria

- [ ] A dialect voice note becomes a correct formal Arabic and English announcement in under 20 seconds.
- [ ] The suggested audience exists in the school's data and can be changed.
- [ ] Nothing is sent until the leader presses Send.
- [ ] The audio file is gone after transcription.

## Tests

- Audience suggestion only returns real grades and academies of this school.
- Send requires an explicit action; role checks (Admin, Manager only). School isolation.
