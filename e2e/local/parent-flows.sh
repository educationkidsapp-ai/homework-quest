#!/usr/bin/env bash
# T3 — staff-side helpers for the parent-flows pass (docs/reports/app-parent-flows.md).
#
# Source this file, then call the functions. Nothing here prints a secret; every password comes from the environment:
#   API                  base URL, default http://localhost:8080 (a LOCAL server — never QA, it holds the owner's school)
#   ADMIN_PASSWORD       the platform admin (ADMIN_EMAIL, default admin@quest.local)
#   SEED_STAFF_PASSWORD  the acceptance seed's staff password (maya@test.com, manager@test.com, coord.math@test.com)
#   PARENT_PASSWORD      the password POST /admin/children sets on the parent login (≥ 8 chars)
#
#   source e2e/local/parent-flows.sh
#   t3_tokens                      # ADMIN, TEACHER, MANAGER, COORD tokens into the shell
#   t3_flags on                    # exams, gradebook, chat, complaints, announcements for the default school
#   t3_parent_child "Hala Ahmed" parent@test.local   # → CHILD id (idempotent: the same address is reused)
#   t3_exam_create "Autumn test" 20                  # exam on 1A British, open now, closes in 20 min → EXAM id
#   t3_exam_fill $EXAM 5           # five hand-written choice stops in level 1
#   t3_exam_publish $EXAM
#   t3_exam_results $EXAM          # the teacher's results table
#   t3_exam_release $EXAM          # release to parents
#   t3_exam_reopen $EXAM $CHILD    # second chance for one child
#   t3_plan_image 2026-09-27 1 plan.png   # weekly plan as an image for grade 1
#   t3_plan_pdf 2026-09-27 1 plan.pdf     # weekly plan as a PDF for grade 1
#   t3_event "Sports day" "Friday 10:00 on the field" [expiresAtMillis]
#   t3_announcement "Title" "Body"
#   t3_parent_token parent@test.local     # the FakeAuth bearer the app uses for that address (FAKE_AUTH servers only)
#   t3_parent_token_for_child             # the same bearer, read from the parent row the app's sign-in created
#   t3_exam_comment $EXAM "Well done"     # lesson-level teacher comment (needs the openStopMarking flag)
#   t3_parent_progress                    # released results as the parent's Progress page reads them
set -o pipefail
API=${API:-http://localhost:8080}
ADMIN_EMAIL=${ADMIN_EMAIL:-admin@quest.local}
SCHOOL=${SCHOOL:-default}
CLASS_1A=${CLASS_1A:-default:british:1:1a british}

_t3_signin() { # email, password-variable-name (indirection written for bash and zsh alike)
    local pw; eval "pw=\${$2}"
    curl -s -X POST "$API/auth/sign-in" -H 'Content-Type: application/json' \
        -d "$(jq -nc --arg e "$1" --arg p "$pw" '{email:$e,password:$p}')" | jq -r .token
}
_t3_json() { curl -s -H 'Content-Type: application/json' "$@"; }

t3_tokens() {
    : "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}" "${SEED_STAFF_PASSWORD:?set SEED_STAFF_PASSWORD}"
    ADMIN=$(_t3_signin "$ADMIN_EMAIL" ADMIN_PASSWORD)
    TEACHER=$(_t3_signin maya@test.com SEED_STAFF_PASSWORD)
    MANAGER=$(_t3_signin manager@test.com SEED_STAFF_PASSWORD)
    COORD=$(_t3_signin coord.math@test.com SEED_STAFF_PASSWORD)
    export ADMIN TEACHER MANAGER COORD
    local v t
    for v in ADMIN TEACHER MANAGER COORD; do eval "t=\${$v}"; [ "$t" != null ] && [ -n "$t" ] && echo "$v ok" || echo "$v FAILED"; done
}

# t3_flags on|off — the flags every scenario of the pass needs, for the default school
t3_flags() {
    local enabled=true; [ "${1:-on}" = off ] && enabled=false
    for key in exams gradebook openStopMarking chat complaints announcements teacherQuestions; do
        printf '%s: ' "$key"
        _t3_json -X PUT "$API/admin/schools/$SCHOOL/flags/$key" -H "Authorization: Bearer $ADMIN" \
            -d "{\"enabled\":$enabled}" | jq -c --arg k "$key" '.[$k] // .'
    done
}

# t3_parent_child "Child name" parent@address [classId] → prints the 201 body, exports CHILD
t3_parent_child() {
    : "${PARENT_PASSWORD:?set PARENT_PASSWORD}"
    local class=${3:-$CLASS_1A}
    local body
    body=$(jq -nc --arg n "$1" --arg c "$class" --arg e "$2" --arg p "$PARENT_PASSWORD" \
        '{name:$n, classId:$c, grade:1, curriculum:"british", parentName:"Test Parent", parentPhone:"0500000001", parentEmail:$e, parentInitialPassword:$p}')
    local out
    out=$(_t3_json -X POST "$API/admin/children" -H "Authorization: Bearer $ADMIN" -H "X-School-Id: $SCHOOL" -d "$body")
    echo "$out" | jq -c 'del(.parentInitialPassword)'
    CHILD=$(echo "$out" | jq -r .childId)
    if [ "$CHILD" = null ]; then # already admitted on an earlier run: find her by name
        CHILD=$(curl -s "$API/admin/children/search?q=$(printf %s "$1" | jq -sRr @uri)" -H "Authorization: Bearer $ADMIN" \
            -H "X-School-Id: $SCHOOL" | jq -r '.rows[0].id // .rows[0].childId')
    fi
    export CHILD; echo "CHILD=$CHILD"
}

# The FakeAuth bearer for an address: `fake-token-fake-<sha256(email)[0:16]>`, exactly what the app sends.
t3_parent_token() {
    local uid; uid=$(printf %s "$1" | tr 'A-Z' 'a-z' | tr -d '\n' | shasum -a 256 | cut -c1-16)
    PARENT="fake-token-fake-$uid"; export PARENT; echo "PARENT token set"
}
# A FAKE_AUTH parent who signed in through the app gets a parent row `fake-<uid>@fake.local`; the bearer is
# `fake-token-` + that local part. Reads it from the Admin's child search, so the app's typed address is not needed.
t3_parent_token_for_child() {
    local email; email=$(curl -s "$API/admin/children/search?q=$(printf %s "${1:-Hala}" | jq -sRr @uri)" \
        -H "Authorization: Bearer $ADMIN" -H "X-School-Id: $SCHOOL" | jq -r '.rows[0].parentEmail')
    case "$email" in fake-*@fake.local) PARENT="fake-token-${email%@fake.local}"; export PARENT; echo "PARENT token set";;
        *) echo "parent row is not a FAKE_AUTH one"; return 1;; esac
}

# t3_exam_create "Title" [minutesOpen=20] [classId] → exports EXAM. The window opens now.
t3_exam_create() {
    local opens closes class=${3:-$CLASS_1A}
    opens=$(( $(date +%s) * 1000 )); closes=$(( opens + ${2:-20} * 60000 ))
    local out
    out=$(_t3_json -X POST "$API/teacher/classes/$(printf %s "$class" | jq -sRr @uri)/exams" -H "Authorization: Bearer $TEACHER" \
        -d "{\"title\":\"$1\",\"opensAt\":$opens,\"closesAt\":$closes,\"level\":\"1\",\"source\":\"manual\",\"releaseMode\":\"manual\",\"practiceLength\":5}")
    echo "$out" | jq -c '{examId, state, open, level, opensAt, closesAt} // .'
    EXAM=$(echo "$out" | jq -r .examId); export EXAM
}

# t3_exam_fill EXAM [count=5] — hand-written single-answer stops into level 1 (the Add question sheet's route)
t3_exam_fill() {
    local play
    play=$(curl -s "$API/teacher/lessons/$1" -H "Authorization: Bearer $TEACHER" | jq -r '.plays[] | select(.level==1 and .variant==0) | .id')
    [ -z "$play" ] && { echo "no level-1 play on $1"; return 1; }
    local i stop
    for i in $(seq 1 "${2:-5}"); do
        stop=$(jq -nc --arg i "$i" '{type:"choice", id:("q"+$i), title:("Question "+$i), speak:("How many apples? Question "+$i),
            ingredient:{emoji:"🍎",name:"apple"}, parentTip:{en:"Count together.",ar:"عدّوا معاً."}, hint:"Count them one by one.",
            question:("Which number comes after "+$i+"?"), options:[{id:"a",label:(($i|tonumber)+1|tostring)},{id:"b",label:(($i|tonumber)+3|tostring)},{id:"c",label:(($i|tonumber)+5|tostring)}], correctOptionId:"a"}')
        _t3_json -X POST "$API/teacher/plays/$play/stops" -H "Authorization: Bearer $TEACHER" -d "$stop" | jq -c 'if type=="object" and .code then . else "stop \($i|tostring) added" end' --arg i "$i"
    done
    curl -s "$API/teacher/lessons/$1" -H "Authorization: Bearer $TEACHER" | jq -c '{status, stops: [.plays[] | select(.level==1) | .play.stops | length]}'
}

t3_exam_publish() { _t3_json -X POST "$API/teacher/exams/$1/publish" -H "Authorization: Bearer $TEACHER" | jq -c '.'; }
t3_exam_results() { curl -s "$API/teacher/exams/$1/results" -H "Authorization: Bearer $TEACHER" | jq '.'; }
t3_exam_release() { _t3_json -X POST "$API/teacher/exams/$1/release" -H "Authorization: Bearer $TEACHER" -d '{"released":true}' | jq -c '{released, releasedAt} // .'; }
t3_exam_children() { curl -s "$API/teacher/exams/$1/results" -H "Authorization: Bearer $TEACHER" | jq -c '{sat, submitted, children: [.children[] | {state, answered, total, score, maxScore, percent, band, reopened, comment}]}'; }
t3_exam_comment() { # EXAM "comment" — PUT /teacher/marks, lesson level (no stopId); 404 while openStopMarking is off
    _t3_json -X PUT "$API/teacher/marks" -H "Authorization: Bearer $TEACHER" \
        -d "$(jq -nc --arg c "$CHILD" --arg l "$1" --arg t "$2" '{marks:[{childId:$c, lessonId:$l, comment:$t}]}')" | jq -c '.'
}
t3_exam_reopen() { _t3_json -X POST "$API/teacher/exams/$1/reopen/$2" -H "Authorization: Bearer $TEACHER" | jq -c '.'; }
t3_exam_row() { curl -s "$API/teacher/exams/$1" -H "Authorization: Bearer $TEACHER" | jq -c '{state, open, sat, roster, needsMarking, releasedAt}'; }

_t3_upload() { curl -s -X POST "$API/media/attachments" -H "Authorization: Bearer $MANAGER" -F "file=@$1" | jq -r .id; }
t3_plan_image() { # weekStart grade file
    local att; att=$(_t3_upload "$3")
    _t3_json -X POST "$API/management/broadcasts" -H "Authorization: Bearer $MANAGER" \
        -d "{\"kind\":\"weekly_plan\",\"weekStart\":\"$1\",\"grade\":$2,\"attachmentId\":\"$att\"}" | jq -c '{id, kind, weekStart, grade, attachment, bodyEn} // .'
}
t3_plan_pdf() { t3_plan_image "$@"; }
t3_event() { # title body [expiresAt]
    local exp=${3:+,\"expiresAt\":$3}
    _t3_json -X POST "$API/management/broadcasts" -H "Authorization: Bearer $MANAGER" \
        -d "{\"kind\":\"event\",\"title\":\"$1\",\"bodyEn\":\"$2\",\"audience\":[\"parents\",\"teachers\"],\"grade\":1$exp}" | jq -c '{id, kind, title, expiresAt} // .'
}
t3_announcement() {
    _t3_json -X POST "$API/management/broadcasts" -H "Authorization: Bearer $MANAGER" \
        -d "{\"kind\":\"announcement\",\"title\":\"$1\",\"bodyEn\":\"$2\",\"audience\":[\"parents\"],\"grade\":1}" | jq -c '{id, kind, title} // .'
}

# What the parent's app reads, straight from the API (same bearer the app sends)
t3_parent_feed() { curl -s "$API/children/$CHILD/broadcasts" -H "Authorization: Bearer $PARENT" | jq -c '{unread, items: [.items[] | {id, kind, title, expiresAt, attachment}]}'; }
t3_parent_plans() { curl -s "$API/children/$CHILD/weekly-plans" -H "Authorization: Bearer $PARENT" | jq -c '{unread, weeks: [.weeks[] | {weekStart, items: [.items[] | .plan | {id, grade, attachment}]}]}'; }
t3_parent_map() { curl -s "$API/children/$CHILD/map?from=$(date -v-30d +%F)&to=$(date -v+14d +%F)" -H "Authorization: Bearer $PARENT" | jq -c '[.islands[] | {lessonId, title, kind, date, state, examWindow}]'; }
t3_parent_progress() { curl -s "$API/children/$CHILD/progress" -H "Authorization: Bearer $PARENT" | jq -c '[.results[] | {title, score, band, comment, releasedAt}]'; }
t3_parent_threads() { curl -s "$API/children/$CHILD/chat/threads" -H "Authorization: Bearer $PARENT" | jq -c '[.[] | {id, teacherId, teacherName, staffRole, topic, status, parentUnread}]'; }

# The three staff inboxes a complaint must reach
t3_teacher_threads() { curl -s "$API/teacher/chat/threads" -H "Authorization: Bearer $TEACHER" | jq -c '[.[] | {id, childId, topic, status, teacherUnread}]'; }
t3_coord_complaints() { curl -s "$API/coordinator/complaints?status=${1:-open}" -H "Authorization: Bearer $COORD" | jq -c '[.[] | {id, childId, topic, status}]'; }
t3_manager_complaints() { curl -s "$API/management/complaints?status=${1:-open}" -H "Authorization: Bearer $MANAGER" | jq -c '[.[] | {id, childId, topic, status}]'; }
t3_coord_resolve() { _t3_json -X PATCH "$API/coordinator/chat/threads/$1/status" -H "Authorization: Bearer $COORD" -d '{"status":"resolved"}' | jq -c '{id, status} // .'; }
t3_manager_resolve() { _t3_json -X PATCH "$API/management/chat/threads/$1/status" -H "Authorization: Bearer $MANAGER" -d '{"status":"resolved"}' | jq -c '{id, status} // .'; }
t3_teacher_message() { _t3_json -X POST "$API/teacher/chat/threads/$CHILD/messages" -H "Authorization: Bearer $TEACHER" -d "{\"body\":\"$1\"}" | jq -c '{id, body, createdAt} // .'; }

# t3_lesson_create "Title" [date=today] [classId] → exports LESSON (a hand-written homework, empty level 1)
t3_lesson_create() {
    local class=${3:-$CLASS_1A} out
    out=$(_t3_json -X POST "$API/teacher/lessons" -H "Authorization: Bearer $TEACHER" \
        -d "$(jq -nc --arg c "$class" --arg d "${2:-$(date +%F)}" --arg t "$1" '{classId:$c, subject:"math", date:$d, source:"manual", title:$t}')")
    echo "$out" | jq -c '{id, status, title, date} // .'
    LESSON=$(echo "$out" | jq -r .id); export LESSON
}
# t3_lesson_fill LESSON [count] — same stops as the exam, so t3_exam_fill does the work
t3_lesson_fill() { t3_exam_fill "$@"; }
t3_lesson_publish() { _t3_json -X POST "$API/teacher/lessons/$1/publish" -H "Authorization: Bearer $TEACHER" -d "{\"classIds\":[\"${2:-$CLASS_1A}\"]}" | jq -c 'if type=="array" then [.[] | {id, status, classId}] else . end'; }

# t3_week_all — the pass may run on a Friday or Saturday: make every day a teaching day on the LOCAL platform so
# `POST /teacher/lessons` and `POST /teacher/classes/{id}/exams` are not refused with `not_teaching_day`.
t3_week_all() {
    local cur; cur=$(curl -s "$API/admin/platform-settings" -H "Authorization: Bearer $ADMIN")
    _t3_json -X PUT "$API/admin/platform-settings" -H "Authorization: Bearer $ADMIN" \
        -d "$(echo "$cur" | jq -c '{name, shortName, logoUrl, supportEmail, defaultTheme, timezone: (.timezone // "Asia/Riyadh"), schoolWeek: ["SUN","MON","TUE","WED","THU","FRI","SAT"]}')" | jq -c '{schoolWeek, timezone} // .'
}
