# Milestone 03 — Marking, gradebook and exams

**Finished:** 2026-09-21 (exam security completed 2026-10-03) · **Status:** live on QA · **Area:** dashboard (teacher), mobile app (child and parent)

## Goal

Turn children's work into fair, reliable results. Teachers can mark and release them, and parents can see them.

## What was delivered

### Dashboard: teacher
- **Automatic scoring.** Scores follow clear rules: a child's first try counts, and scores fall into performance bands.
- **Marking.** Teachers mark open answers, such as written sentences and exit tickets, and can override a score with a comment.
- **Release control.**
  - Homework results are released when the lesson is published.
  - Exam results are released by the teacher, or automatically when the exam closes.
- **Gradebook.** One table per class, with export to CSV and Excel.
- **Exams.**
  - Create an exam with an opening and a closing time.
  - Each child sits it once, and the sitting can be resumed if interrupted.
  - The teacher can reopen an exam once.
  - Results show the class's score spread and the difficulty of each question.
  - Printable PDF.
- **Child page.** One child's full record in one place.

### Mobile app
- **Child exam mode.**
  - Every answer gets the same neutral "Answer saved", which gives no hint about whether it was right.
  - No hints are shown.
  - The child sees a countdown.
  - Answers given offline are sent automatically when the phone reconnects.
- **Parent.** Sees the released score, the level and the teacher's comment.

### Exam integrity (completed 2026-10-03)
- Exams are graded only on the server. The correct answers never reach the child's phone.
- Results stay hidden until the teacher releases them.
- The first answer counts, so changing it later has no effect.

## Business value

- Reliable, tamper-resistant results the school can stand behind with parents.
- Less marking time for teachers, and instant exports for reports.
- Parents get timely, clear feedback on their child's progress.

## Who benefits

Teachers save marking time, school leadership gets trustworthy results, and parents get visibility.
