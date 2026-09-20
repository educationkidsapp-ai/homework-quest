# Mobile M1 — the app on the teacher-role model

**Package** `mobile/teacher-model-compat` (decision D16, lifted 2026-09-21). **Base** `develop` at `49bec19`.
**Against** QA `https://homework-quest-api-625882725080.me-central1.run.app`, school `HQ0001` *Default school*.
**Device** emulator `Pixel_8a` (1080×2400), package `app.homeworkquest.qa`, built with
`./gradlew :androidApp:assembleQaRelease -Pquest.qa.apiBaseUrl=…`.
**Parent** a throwaway `qa-parent+<ts>@test.com` with a generated password (neither is in this repo).

The package answers `docs/reports/mobile-parent-acceptance.md` findings **F5** and **F6**, and the two things
`docs/runbook.md` "The app and the contract" and `docs/teacher-flow.md` §2 / step 9 have been waiting for.

## Slices

| # | What | Unit tests | On the emulator |
|---|------|-----------|-----------------|
| 1 | The app decodes with `ignoreUnknownKeys = true` (`AppJson`), separately from the strict `SchemaValidator.json` | `ContractToleranceTest` — a `PublishedLesson` and a `ProgressResponse` with fields no build has heard of | **pass** — the app built on `AppJson` signs in, joins, adds children and loads maps against live QA |
| 2 | The school code is asked once per parent; Back from the map goes to the child list | `JoinSchoolTest` (3 new cases) | **pass** — see below |
| 3 | Optional **class join code** on Add child; the child is created placed | `ClassCodeTest` (5 cases) | **pass** — walked live with `1A British`'s real code, see *Second pass* |
| 4 | Released score, band and teacher comment in the parent view | `ReleasedResultsTest` (5 cases, incl. the §6 guard) | see *Second pass* |

## What was walked on the device

1. Register a throwaway parent → lands on *Add a child*. **pass**
2. Type `HQ0001` → *Default school* appears → **Join this school** → "Joined · HQ0001". **pass**
3. Type a class code the school does not have (`ZZZ999`) → red band *"We couldn't find that class code."*
   `POST /classes/lookup` reached QA and answered its uniform 404. **pass** — screenshot `01`.
4. Save `ChildOne` with no class code → the map opens and shows **"How it works, 20 Sep" twice**, one copy per
   section of her course. That is the duplication slice 3 exists to remove, and the line under the class-code
   field now warns about it before the parent meets it. Screenshot `02`.
5. **Back from the map** → *Who is playing?* with ChildOne, **not** the pre-filled Add-child form, and the app
   does not exit. **F5 fixed.** Screenshot `03`.
6. Add a second child → the form opens on **"Your school · Default school · Joined · HQ0001"**, with no empty
   School code field. **F6 fixed.** Screenshot `04`. Saved `ChildTwo`; the child list shows both. Screenshot `05`.

Screenshots `06` and `07` are rendered by the desktop screenshot tests (`46b-progress-released`,
`41d-add-child-class-code`): the released-results section and the class card that takes the course choosers
off the form.

## Second pass — with a teacher on QA

The coordinator seeded the teacher side: section `1A British` join code `ECYER6`, and lesson
`bb38fee4-009f-4708-ba9f-92b47f09c46d` *"M1 device check — counting in 2s"* published **to 1A British only**.
Same APK, same throwaway parent.

| # | Step | Expected | Observed | Result | Screenshot |
|---|------|----------|----------|--------|------------|
| 1 | Add child, class code `ECYER6` | the card names the section | **"1A British · British · Grade 1 · Default school"**, and the Curriculum and Grade choosers came off the form — the card answers the course | pass | `08` |
| 2 | Save `M1Placed` | created placed | saved; the map opened on *M1Placed's quest* | pass | — |
| 3 | The map | the lesson **once** | *"M1 device check — counting in 2s"* appears **exactly once** (beside the unrelated seed lesson *How it works*). The two unplaced children on the same account still see their duplicates, so this is the placement doing the work, not the lesson | pass | `09` |
| 4 | Child list | shows the section | **"M1Placed · 1A British"**, while `ChildOne` and `ChildTwo` still read "British · Grade 1" | pass | `10` |
| 5 | Play it to the end | 7 stops, certificate | played all 7 (Learn → missing number → socks → Ben's count → fill the gap → true/false → 3-question exit ticket); *"The Twos Soup Pot is full!"* → certificate, 21 of 21 stars, new sticker | pass | `11` |
| 6 | The play reaches the server | the island turns done | force-stopped and relaunched; the map, which is fetched fresh, shows the island **✓ Done** with its stars — so the attempts were uploaded | pass | — |

**§6 held throughout.** Nothing in child mode showed a number for the work: the stop headers count stops
("3 / 7") and ingredients ("2 of 7"), the certificate shows three stars and a star count, and there is no
score, no percentage, no red X and no timer anywhere in the seven stops or on the finish screen.

**Two content notes for the backend, not the app.** Stop 5 renders *"Fill the gap: 10, 12, __, 16, 20"* — the
same dropped `18` slot as **F7** in the September pass, on a freshly generated lesson, so F7 is not fixed. And
`Child` still has no id the parent's device can show, which is why step 3 below is identified by name.

### Still open at the time of writing

The teacher's mark and release (`PUT /teacher/marks`, `POST /teacher/lessons/{id}/release`) are the
coordinator's to run. Once released, the parent check is: **Grown-ups → PIN → Progress**, which must show
*Marked by the teacher* with the score, the band and Maya's comment, and **the lesson panel** for that lesson,
which must show the same. Child mode must still show no number.

## Two bugs the emulator found, which the unit tests had not

**The default school is a school.** QA's acceptance school *is* the default school: it has the code `HQ0001`
and no theme. `SchoolSession.use("default")` forgets the theme, correctly, and was forgetting the **code** with
it — so the first build of slice 2 changed nothing on QA and Add child still asked. The code is now stored
before the theme is decided, survives a relaunch, and is dropped only by *Use a different code*.
`FakeContentApi` gained `HQ0001` so the fake has a school that is joined but not themed.

**`SettingsStore` lost writes.** Its in-memory mirror was an unguarded map, and a `get` that missed would read
the database and then cache what it found *on top of* a `set` that landed while the read was in flight. The
value written a millisecond earlier then read back as `null` for the life of the process. It is behind a
`Mutex` now, and a concurrent `set` wins. This affects every setting — the PIN hash, the current child, the
school — not only the new section name.

## Blocked — what could not be verified here

* **Direct API calls from this session are refused** by the permission layer, so nothing on the teacher side
  could be driven from here: publishing, reading a section's join code, marking an open stop and releasing a
  result were all done by the coordinator. The app's own calls are unaffected — every device step above went
  over the network from the emulator to QA.
* **The child's id cannot be read off the device.** The QA flavour is a release build, so `adb run-as` and
  `adb root` are both refused and the app's SQLite file is unreadable; the app shows a child's name and section
  and never an id. A child created in the app is therefore identified to the teacher side **by name and
  section** — here `M1Placed` on `1A British`.

## Server gaps for the planner

1. **`Child` carries no class.** `GET /children` answers `id, name, avatarColor, curriculum, grade, languages,
   schoolId` and nothing about the section, so the app cannot tell a placed child from an unplaced one. The
   section name shown in the child list is a device-side note written when the parent used a class code; a child
   the *teacher* places later still reads "British · Grade 1". Asking for `classId` and `className` on `Child`.
2. **`ClassLookup` has no teacher.** The brief expected the card to read "1A British · Grade 1 · Maya";
   `POST /classes/lookup` deliberately answers `classId, name, grade, curriculum, schoolName` only. The card
   names the school instead. If the teacher's name is meant to be on it, the route has to say so.
3. **`/classes/lookup` is `POST`, not `GET …?code=`**, and the body is `{"code":"…"}`. Correct — a join code is a
   credential — but `SectionService.lookup`'s own javadoc still calls it `GET /classes/lookup?code=`.
4. **F9 stands.** The children created for this pass (`ChildOne`, `ChildTwo`, unsectioned, plus the earlier
   `ChildA`/`ChildB`) still cannot be removed by an admin, and the Firebase user is still there. A QA reseed
   clears the rows.

## Also worth knowing

* `./gradlew :androidApp:lintQaRelease` **fails on `develop` too**, before this branch: the
  `NonNullableMutableLiveDataDetector` lint check crashes with `IncompatibleClassChangeError` (an AGP /
  lifecycle-lint version mismatch). Not caused by this package; owner is whoever next touches the AGP pins.
* The sign-in screen said *"Homework Quest"* during this pass, not *"Schools Dashboard"* as in the September
  acceptance pass — `GET /platform-settings` is answering a different name, or had not answered yet.
* **The owner must reinstall.** The QA flavour installs as `app.homeworkquest.qa`; uninstalling clears the
  device's settings, so the school code is asked once more after a reinstall and then not again.
