#!/usr/bin/env bash
# Demo data for the feature-tour screenshots (docs/screenshots/feature-tour/). LOCAL ONLY — never QA, it holds the
# owner's real school and children.
#
# Run against a FAKE_AUTH H2 server started with the full seed (`SEED_SCHOOL=true SEED_PROFILE=full`), so the
# 30 classes, 40 teachers, coordinators, managers and 600 children already exist. Every password comes from the
# environment and nothing here prints one:
#   API                  base URL, default http://localhost:8090
#   ADMIN_PASSWORD       the platform admin's (ADMIN_EMAIL, default admin@quest.local)
#   SEED_STAFF_PASSWORD  the seed's shared staff password
#
#   e2e/local/feature-demo-data.sh            # idempotent: a second run finds every row and adds nothing
#
# What it writes, all in the default school and mostly on 1A British (Sara Al Harbi, maths):
#   flags on (exams, gradebook, openStopMarking, chat, complaints, announcements, teacherQuestions, rosterEdit),
#   school name + logo, staff phones, three workers, a demo parent (FakeAuth `fake-token-demo-mona-hassan`) with two children,
#   four published lessons (one with levels 1–3 and Again) and an assistant draft, an open exam with a released result and an
#   upcoming one, attendance for three school days, gradebook marks, weekly plans (image and PDF), an announcement and
#   an event, staff attendance, chat threads parent ↔ teacher/coordinator/manager/admin (text, a photo, a PDF), staff
#   threads, and complaints to a teacher, a coordinator and the manager (one open, one resolved and reopened history).
# The role tokens (ADMIN, TEACHER, …) are read by name through `call`'s indirection, which shellcheck cannot follow.
# shellcheck disable=SC2034
set -euo pipefail

API=${API:-http://localhost:8090}
ADMIN_EMAIL=${ADMIN_EMAIL:-admin@quest.local}
SCHOOL=default
CLASS_1A='default:british:1:1a british'
CLASS_2B='default:american:2:2b american'
SARA_EMAIL=sara.al-harbi@school.test
COORD_EMAIL=coordinator.math@school.test
MANAGER_EMAIL=manager.a@school.test
PARENT_BEARER=fake-token-demo-mona-hassan
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}" "${SEED_STAFF_PASSWORD:?set SEED_STAFF_PASSWORD}"

here=$(cd "$(dirname "$0")" && pwd)
ASSETS=${ASSETS:-$(mktemp -d)}
[ -f "$ASSETS/weekly-plan.pdf" ] || node "$here/feature-tour/make-assets.mjs" "$ASSETS" >/dev/null

# Lists come back either bare or wrapped ({complaints: […]}, {items: […]}): this makes both a bare array.
rows() { jq -c 'if type=="array" then . else (.items // .complaints // .exams // .messages // .rows // .children // []) end'; }
say() { printf '· %s\n' "$*"; }
uri() { printf %s "$1" | jq -sRr @uri; }
day() { python3 -c "import datetime,sys; print(datetime.date.today()+datetime.timedelta(days=int(sys.argv[1])))" "$1"; }
ms() { python3 -c "import time,sys; print(int(time.time()*1000)+int(float(sys.argv[1])*3600000))" "$1"; }
signin() { # email, password-variable-name; prints the token only into a variable, never to the terminal
    local pw; eval "pw=\${$2}"
    curl -sf -X POST "$API/auth/sign-in" -H 'Content-Type: application/json' \
        -d "$(jq -nc --arg e "$1" --arg p "$pw" '{email:$e,password:$p}')" | jq -r .token
}
# call ROLE METHOD PATH [json] — ROLE is a variable holding the bearer; fails loudly on a non-2xx
call() {
    local who=$1 method=$2 path=$3 body=${4:-} tok out code
    eval "tok=\${$who}"
    out=$(curl -s -w '\n%{http_code}' -X "$method" "$API$path" -H "Authorization: Bearer $tok" -H "X-School-Id: $SCHOOL" \
        ${body:+-H 'Content-Type: application/json' -d "$body"})
    code=${out##*$'\n'}; out=${out%$'\n'*}
    if [ "${code:0:1}" != 2 ]; then printf '  %s %s → %s %s\n' "$method" "$path" "$code" "$(printf %s "$out" | head -c 300)" >&2; return 1; fi
    printf '%s' "$out"
}
upload() { # ROLE PATH FILE TYPE [METHOD=POST] → attachment id
    local tok; eval "tok=\${$1}"
    curl -sf -X "${5:-POST}" -H "Authorization: Bearer $tok" -H "X-School-Id: $SCHOOL" -F "file=@$3;type=$4" "$API$2" | jq -r '.id // "ok"'
}

# ---------------------------------------------------------------- who
ADMIN=$(signin "$ADMIN_EMAIL" ADMIN_PASSWORD)
TEACHER=$(signin "$SARA_EMAIL" SEED_STAFF_PASSWORD)
COORD=$(signin "$COORD_EMAIL" SEED_STAFF_PASSWORD)
MANAGER=$(signin "$MANAGER_EMAIL" SEED_STAFF_PASSWORD)
PARENT=$PARENT_BEARER
for v in ADMIN TEACHER COORD MANAGER; do eval "t=\${$v}"; [ -n "$t" ] && [ "$t" != null ] || { echo "$v could not sign in"; exit 1; }; done
say "signed in: admin, teacher, coordinator, manager"

# ---------------------------------------------------------------- school, flags, profiles
for key in exams gradebook openStopMarking chat complaints announcements teacherQuestions teacher.rosterEdit; do
    call ADMIN PUT "/admin/schools/$SCHOOL/flags/$key" '{"enabled":true}' >/dev/null
done
call ADMIN PATCH "/admin/schools/$SCHOOL" '{"name":"Green Valley Academy"}' >/dev/null
upload ADMIN "/admin/schools/$SCHOOL/logo" "$ASSETS/logo.png" image/png PUT >/dev/null && say "flags on, school named, logo set"
workers=$(call ADMIN GET /admin/workers)
for w in "Ahmed Saleh|Bus driver|+966 55 300 1001" "Maryam Yousef|School nurse|+966 55 300 1002" "Khalil Omar|Caretaker|+966 55 300 1003"; do
    IFS='|' read -r wn wj wp <<<"$w"
    echo "$workers" | rows | jq -e --arg n "$wn" '[.[] | select(.fullName==$n or .name==$n)] | length > 0' >/dev/null \
        || call ADMIN POST /admin/workers "$(jq -nc --arg n "$wn" --arg j "$wj" --arg p "$wp" '{fullName:$n, job:$j, phone:$p}')" >/dev/null
done
call ADMIN PATCH /me '{"displayName":"Platform Admin"}' >/dev/null
# A phone number on every staff member who has none yet (the directories show it): each signs in and sets her own.
n=0
for email in $( { call ADMIN GET /admin/teachers; call ADMIN GET /admin/coordinators; call ADMIN GET /admin/managers; } \
        | rows | jq -r '.[] | select((.phone // "") == "") | .email'); do
    n=$((n + 1))
    STAFF=$(signin "$email" SEED_STAFF_PASSWORD) || continue
    call STAFF PATCH /me "$(printf '{"phone":"+966 55 %03d %04d"}' $((200 + n)) $((1000 + n * 37)))" >/dev/null || true
done

# The school week: on a Friday or Saturday nothing can be dated today, so open every day (as parent-flows.sh does).
dow=$(date +%u) # 1 = Monday … 7 = Sunday
if [ "$dow" = 5 ] || [ "$dow" = 6 ]; then
    cur=$(call ADMIN GET /admin/platform-settings)
    call ADMIN PUT /admin/platform-settings "$(echo "$cur" | jq -c '{name, shortName, logoUrl, supportEmail, defaultTheme, timezone: (.timezone // "Asia/Riyadh"), schoolWeek: ["SUN","MON","TUE","WED","THU","FRI","SAT"]}')" >/dev/null
    say "weekend run: every day made a school day"
fi
SUN_OFFSET=$(( -(dow % 7) ))              # this week's Sunday, relative to today
WEEK=$(day $SUN_OFFSET); LAST_WEEK=$(day $(( SUN_OFFSET - 7 )))

# ---------------------------------------------------------------- the demo family
code_1a=$(call ADMIN GET /admin/classes | jq -r --arg c "$CLASS_1A" '.[] | select(.id==$c) | .joinCode')
code_2b=$(call ADMIN GET /admin/classes | jq -r --arg c "$CLASS_2B" '.[] | select(.id==$c) | .joinCode')
child_id() { call PARENT GET /children | jq -r --arg n "$1" '.[] | select(.name==$n) | .id' | head -1; }
admit() { # name colour curriculum grade joinCode
    local id; id=$(child_id "$1")
    [ -n "$id" ] || id=$(call PARENT POST /children "$(jq -nc --arg n "$1" --arg a "$2" --arg c "$3" --argjson g "$4" --arg j "$5" \
        '{name:$n, avatarColor:$a, curriculum:$c, grade:$g, joinCode:$j}')" | jq -r .id)
    call ADMIN PATCH "/admin/children/$id" '{"parentName":"Mona Hassan","parentPhone":"+966 50 123 4567"}' >/dev/null
    printf '%s' "$id"
}
LAYLA=$(admit "Layla Hassan" sun british 1 "$code_1a")
OMAR=$(admit "Omar Hassan" sky american 2 "$code_2b")
say "parent Mona Hassan: Layla (1A British), Omar (2B American)"

# ---------------------------------------------------------------- lessons
lesson_by_title() { call TEACHER GET /teacher/lessons | jq -r --arg t "$1" '[.[] | select(.title==$t)][0].id // empty'; }
level1() { call TEACHER GET "/teacher/lessons/$1" | jq -r '.plays[] | select(.level==1 and .variant==0) | .id'; }
stop_count() { call TEACHER GET "/teacher/lessons/$1" | jq -r --argjson l "$2" --argjson v "${3:-0}" '[.plays[] | select(.level==$l and .variant==$v) | .play.stops | length][0] // 0'; }
# busy CALL… — the assistant takes one job per lesson at a time; a 409 `generating` means wait and ask again
busy() { local i out; for i in $(seq 1 60); do
    out=$(call "$@" 2>&1) && { printf '%s' "$out"; return 0; }
    case $out in *generating*) sleep 1;; *) printf '%s\n' "$out" >&2; return 1;; esac; done; return 1; }
# fill LESSON KIND — five hand-written single-answer stops (sums for an exam, skip counting for homework)
fill() {
    local play i stop; play=$(level1 "$1")
    [ "$(stop_count "$1" 1)" -gt 0 ] && return 0
    for i in 1 2 3 4 5; do
        stop=$(jq -nc --argjson i "$i" --arg k "$2" '
            (if $k=="exam" then {q:"\($i) + \($i) = ?", a:($i*2), b:($i*2+1), c:($i*2+3), t:"Question \($i)"}
             else {q:"What comes after \($i*2) when counting in 2s?", a:($i*2+2), b:($i*2+1), c:($i*2+5), t:"Count by 2s · \($i)"} end) as $s |
            {type:"choice", id:"q\($i)", title:$s.t, speak:$s.q, ingredient:{emoji:"🍎",name:"apple"},
             parentTip:{en:"Count together on your fingers.",ar:"عدّوا معاً على الأصابع."}, hint:"Count them one by one.",
             question:$s.q, options:[{id:"a",label:($s.a|tostring)},{id:"b",label:($s.b|tostring)},{id:"c",label:($s.c|tostring)}], correctOptionId:"a"}')
        call TEACHER POST "/teacher/plays/$play/stops" "$stop" >/dev/null
    done
}
lesson() { # title date [levels=1|all] [publish=yes|no] → id
    local id; id=$(lesson_by_title "$1")
    [ -n "$id" ] || id=$(call TEACHER POST /teacher/lessons "$(jq -nc --arg c "$CLASS_1A" --arg d "$2" --arg t "$1" \
        '{classId:$c, subject:"math", date:$d, source:"manual", title:$t, practiceLength:5}')" | jq -r .id)
    fill "$id" homework
    if [ "${3:-1}" = all ]; then
        local lv
        for lv in 2 3 again; do
            case $lv in again) [ "$(stop_count "$id" 1 1)" -gt 0 ] && continue;; *) [ "$(stop_count "$id" "$lv")" -gt 0 ] && continue;; esac
            busy TEACHER POST "/teacher/lessons/$id/plays/$lv/generate" >/dev/null
        done
    fi
    if [ "${4:-yes}" = yes ] && [ "$(call TEACHER GET "/teacher/lessons/$id" | jq -r .status)" != published ]; then
        busy TEACHER POST "/teacher/lessons/$id/publish" "$(jq -nc --arg c "$CLASS_1A" '{classIds:[$c]}')" >/dev/null
    fi
    printf '%s' "$id"
}
L_PAST1=$(lesson "Number bonds to 10" "$(day $(( SUN_OFFSET - 6 )))")
L_PAST2=$(lesson "Shapes around us" "$(day $(( SUN_OFFSET - 4 )))")
L_SUN=$(lesson "Counting in 2s" "$WEEK" all)
L_MON=$(lesson "Measuring length" "$(day $(( SUN_OFFSET + 1 )))")
# An assistant draft: an empty lesson the assistant writes from a sentence (all three levels and Again), left in review.
L_WED=$(lesson_by_title "Odd and even")
[ -n "$L_WED" ] || L_WED=$(call TEACHER POST /teacher/lessons "$(jq -nc --arg c "$CLASS_1A" --arg d "$(day $(( SUN_OFFSET + 3 )))" \
    '{classId:$c, subject:"math", date:$d, source:"manual", title:"Odd and even", practiceLength:5}')" | jq -r .id)
[ "$(stop_count "$L_WED" 1)" -gt 0 ] || busy TEACHER POST "/teacher/lessons/$L_WED/generate-from-text" \
    '{"text":"Odd and even numbers up to 20. Children sort numbers into odd and even and spot the pattern in the ones digit."}' >/dev/null
# Layla plays the four published homeworks (an unsealed paper: the option ids are the real ones). Attempt ids are fixed,
# so a second run re-sends the same rows rather than new ones.
for L in $L_PAST1 $L_PAST2 $L_SUN $L_MON; do
    call PARENT POST "/children/$LAYLA/attempts" "$(call TEACHER GET "/teacher/lessons/$L" | jq -c --arg l "$L" --argjson now "$(ms -20)" '
        [.plays[] | select(.level==1 and .variant==0) | .play.stops | to_entries[] |
         {id:"demo-\($l)-\(.key)", stopId:.value.id, lessonId:$l, level:1, answerJson:(if .key==2 then "b" else "a" end),
          correct:(.key!=2), attemptNumber:1, mistakes:(if .key==2 then 1 else 0 end), stars:(if .key==2 then 1 else 3 end),
          answeredAt:($now + .key*60000)}]')" >/dev/null
done
say "lessons: 4 published (Counting in 2s has levels 1–3 and Again), 1 assistant draft (Odd and even)"

# ---------------------------------------------------------------- exams
C1A=$(uri "$CLASS_1A")
exam_by_title() { call TEACHER GET "/teacher/classes/$C1A/exams" | rows | jq -r --arg t "$1" '[.[] | select(.title==$t)][0] | (.examId // .id) // empty'; }
exam() { # title opensInHours hoursOpen → id
    local id; id=$(exam_by_title "$1")
    [ -n "$id" ] || id=$(call TEACHER POST "/teacher/classes/$C1A/exams" "$(jq -nc --arg t "$1" --argjson o "$(ms "$2")" --argjson c "$(ms "$(( $2 + $3 ))")" \
        '{title:$t, opensAt:$o, closesAt:$c, level:"1", source:"manual", releaseMode:"manual", practiceLength:5, durationMinutes:20}')" | jq -r .examId)
    fill "$id" exam
    [ "$(call TEACHER GET "/teacher/lessons/$id" | jq -r .status)" = published ] || call TEACHER POST "/teacher/exams/$id/publish" >/dev/null
    printf '%s' "$id"
}
E_OPEN=$(exam "Autumn maths check" -1 72)
E_NEXT=$(exam "Week 6 number check" 48 24)
# Layla sits the open exam: the paper she gets is sealed (opaque option ids), so answer by the option's label. One
# wrong answer on purpose, so the result is a real band rather than 100%.
if [ "$(call TEACHER GET "/teacher/exams/$E_OPEN/results" | jq -r --arg c "$LAYLA" '.children[] | select(.childId==$c) | .state')" != submitted ]; then
    now=$(ms 0)
    attempts=$(call PARENT GET "/lessons/$E_OPEN" | jq -c --arg e "$E_OPEN" --argjson now "$now" '
        [.plays[] | select(.level==1 and .variant==0) | .stops | to_entries[] |
         (.value.question | capture("^(?<n>[0-9]+)") | .n | tonumber * 2) as $right |
         (if .key==3 then ([.value.options[] | select(.label != ($right|tostring))][0].id) else ([.value.options[] | select(.label == ($right|tostring))][0].id) end) as $pick |
         {id:"demo-\($e)-\(.key)", stopId:.value.id, lessonId:$e, level:1, answerJson:$pick, correct:(.key!=3), attemptNumber:1,
          mistakes:(if .key==3 then 1 else 0 end), stars:(if .key==3 then 0 else 3 end), answeredAt:($now - 900000 + .key*120000)}]')
    call PARENT POST "/children/$LAYLA/attempts" "$attempts" >/dev/null
fi
call TEACHER GET "/teacher/exams/$E_OPEN/results" | jq -e '.released' >/dev/null 2>&1 && [ "$(call TEACHER GET "/teacher/exams/$E_OPEN/results" | jq -r .released)" = true ] \
    || call TEACHER POST "/teacher/exams/$E_OPEN/release" '{"released":true}' >/dev/null
call TEACHER PUT /teacher/marks "$(jq -nc --arg c "$LAYLA" --arg l "$E_OPEN" '{marks:[{childId:$c, lessonId:$l, comment:"Well done, Layla — check question 4 again at home."}]}')" >/dev/null
say "exams: Autumn maths check (open, Layla submitted, released), Week 6 number check (opens in two days)"

# ---------------------------------------------------------------- attendance and gradebook
kids=$(call TEACHER GET "/teacher/classes/$C1A/children" | rows | jq -c '[.[] | (.id // .childId)]')
for off in $(( SUN_OFFSET - 4 )) $SUN_OFFSET $(( SUN_OFFSET + 1 )); do
    d=$(day "$off"); [ "$d" \> "$(day 0)" ] && continue
    call TEACHER POST "/teacher/classes/$C1A/attendance" "$(echo "$kids" | jq -c --arg d "$d" --argjson s "$off" '{date:$d, items:[to_entries[] |
        {childId:.value, status:(((.key + $s) % 9) as $r | if $r==2 then "ABSENT" elif $r==5 then "LATE" elif $r==7 then "EXCUSED" else "PRESENT" end)}
        + (if ((.key + $s) % 9)==7 then {notes:"Doctor appointment"} else {} end)]}')" >/dev/null
done
marks=$(echo "$kids" | jq -c --arg a "$L_PAST1" --arg b "$L_PAST2" --arg c "$L_SUN" --arg d "$L_MON" '{marks:[to_entries[] | .key as $k | .value as $id |
    ([$a,$b,$c,$d] | to_entries[] | {childId:$id, lessonId:.value, score:(45 + (($k*17 + .key*11) % 56))})]}')
call TEACHER PUT /teacher/marks "$marks" >/dev/null
say "attendance for three school days, gradebook marks on four lessons"

# ---------------------------------------------------------------- weekly plans, announcement, event
broadcasts=$(call MANAGER GET /management/broadcasts)
# One plan per week and grade (a second post replaces the first), so a week+grade that has one is done.
has_plan() { echo "$broadcasts" | rows | jq -e --arg w "$1" --argjson g "$2" '[.[] | select(.kind=="weekly_plan" and .weekStart==$w and .grade==$g)] | length > 0' >/dev/null; }
has_title() { echo "$broadcasts" | rows | jq -e --arg t "$1" '[.[] | select(.title==$t)] | length > 0' >/dev/null; }
plan() { # weekStart grade file type
    has_plan "$1" "$2" && return 0
    local att; att=$(upload MANAGER /media/attachments "$3" "$4")
    call MANAGER POST /management/broadcasts "$(jq -nc --arg w "$1" --argjson g "$2" --arg a "$att" '{kind:"weekly_plan", weekStart:$w, grade:$g, attachmentId:$a}')" >/dev/null
}
plan "$WEEK" 1 "$ASSETS/weekly-plan.png" image/png
plan "$WEEK" 2 "$ASSETS/weekly-plan.pdf" application/pdf
plan "$LAST_WEEK" 1 "$ASSETS/weekly-plan.pdf" application/pdf
plan "$LAST_WEEK" 2 "$ASSETS/weekly-plan.png" image/png
has_title "Parents' evening" || call MANAGER POST /management/broadcasts "$(jq -nc '{kind:"announcement", title:"Parents'"'"' evening",
    bodyEn:"Meet your child'"'"'s teachers on Wednesday from 4 pm to 7 pm in the main hall. Book a slot at reception.",
    bodyAr:"لقاء أولياء الأمور يوم الأربعاء من ٤ إلى ٧ مساءً في القاعة الرئيسية.", audience:["parents","teachers"], grade:1}')" >/dev/null
has_title "Sports day" || call MANAGER POST /management/broadcasts "$(jq -nc --argjson e "$(ms 240)" '{kind:"event", title:"Sports day",
    bodyEn:"Thursday 9:00 on the school field. Children wear their house colours and bring a water bottle.", audience:["parents","teachers"], grade:1, expiresAt:$e}')" >/dev/null
say "weekly plans (an image and a PDF each week, grades 1 and 2), Parents' evening, Sports day"

# ---------------------------------------------------------------- staff attendance
staff=$(call MANAGER GET "/management/staff-attendance?day=$(day 0)" | jq -c '[.people[] | .userId]')
for off in $(( SUN_OFFSET - 4 )) $(( SUN_OFFSET - 3 )) $SUN_OFFSET $(( SUN_OFFSET + 1 )); do
    d=$(day "$off"); [ "$d" \> "$(day 0)" ] && continue
    call MANAGER PUT "/management/staff-attendance?day=$d" "$(echo "$staff" | jq -c --argjson s "$off" '[to_entries[] |
        {userId:.value, status:(((.key + $s) % 11) as $r | if $r==3 then "absent" elif $r==6 then "late" elif $r==9 then "leave" else "present" end)}
        + (if ((.key + $s) % 11)==9 then {note:"Annual leave"} else {} end)]')" >/dev/null 2>&1 || true
done
say "staff attendance for this month's school days"

# ---------------------------------------------------------------- chat
SARA_ID=$(call PARENT GET "/children/$LAYLA/complaints/recipients" | jq -r '.[] | select(.peerRole=="TEACHER" and .subject=="math") | .staffId')
COORD_ID=$(call PARENT GET "/children/$LAYLA/complaints/recipients" | jq -r '.[] | select(.peerRole=="COORDINATOR" and .subject=="math") | .staffId')
MANAGER_ID=$(call PARENT GET "/children/$LAYLA/complaints/recipients" | jq -r '.[] | select(.peerRole=="MANAGERIAL") | .staffId' | head -1)
parent_thread_empty() { [ "$(call PARENT GET "/children/$LAYLA/chat/threads/$1/messages" | rows | jq length)" = 0 ]; }
psend() { call PARENT POST "/children/$LAYLA/chat/threads/$1/messages" "$(jq -nc --arg b "$2" --argjson a "${3:-[]}" '{body:$b, attachmentIds:$a, topic:"question"}')" >/dev/null; }
if parent_thread_empty "$SARA_ID"; then
    photo=$(upload PARENT "/children/$LAYLA/chat/attachments" "$ASSETS/class-garden.png" image/png)
    note=$(upload PARENT "/children/$LAYLA/chat/attachments" "$ASSETS/medical-note.pdf" application/pdf)
    psend "$SARA_ID" "Good morning Ms Sara! Layla finished her garden project at the weekend — photo attached."  "[\"$photo\"]"
    call TEACHER POST "/teacher/chat/threads/$LAYLA/messages" '{"body":"What a lovely garden! I will show it to the class tomorrow 🌱"}' >/dev/null
    psend "$SARA_ID" "She will be late on Sunday because of a dentist appointment. Here is the note." "[\"$note\"]"
    sheet=$(upload TEACHER /media/chat-attachments "$ASSETS/weekly-plan.pdf" application/pdf)
    call TEACHER POST "/teacher/chat/threads/$LAYLA/messages" "$(jq -nc --arg a "$sheet" '{body:"Thank you, noted. Here is this week'"'"'s plan so she can catch up.", attachmentIds:[$a]}')" >/dev/null
fi
if parent_thread_empty "$COORD_ID"; then
    psend "$COORD_ID" "Hello Ms Rasha, is there extra maths practice for Grade 1 this term?"
    call COORD POST "/coordinator/chat/threads/$(call COORD GET /coordinator/chat/threads | jq -r --arg c "$LAYLA" '[.[] | select(.childId==$c)][0].id')/messages" \
        '{"body":"Yes — the Again practice in each lesson is meant for that. I will ask Ms Sara to add one more this week."}' >/dev/null
fi
if parent_thread_empty "$MANAGER_ID"; then
    pass=$(upload PARENT "/children/$LAYLA/chat/attachments" "$ASSETS/medical-note.pdf" application/pdf)
    snap=$(upload PARENT "/children/$LAYLA/chat/attachments" "$ASSETS/class-garden.png" image/png)
    psend "$MANAGER_ID" "Dear Ms Huda, will the school bus route change after the holiday? The signed bus form and a photo of the stop are attached." "[\"$pass\",\"$snap\"]"
    call MANAGER POST "/management/chat/threads/$(call MANAGER GET /management/chat/threads | jq -r --arg c "$LAYLA" '[.[] | select(.childId==$c)][0].id')/messages" \
        '{"body":"Good afternoon Mrs Hassan, the route stays the same. The new timetable is coming home on Thursday."}' >/dev/null
fi
admin_thread=$(call ADMIN POST /admin/chat/threads "$(jq -nc --arg c "$LAYLA" '{childId:$c}')" | jq -r .id)
if [ "$(call ADMIN GET "/admin/chat/threads/$admin_thread/messages" | rows | jq length)" = 0 ]; then
    logo=$(upload ADMIN /media/chat-attachments "$ASSETS/class-garden.png" image/png)
    form=$(upload ADMIN /media/chat-attachments "$ASSETS/medical-note.pdf" application/pdf)
    call ADMIN POST "/admin/chat/threads/$admin_thread/messages" "$(jq -nc --arg a "$logo" --arg b "$form" '{body:"Welcome to Green Valley Academy! Here are the class garden photo and the consent form for the trip.", attachmentIds:[$a,$b]}')" >/dev/null
    psend "$(call PARENT GET "/children/$LAYLA/chat/threads" | jq -r '.[] | select(.teacherName=="School administration") | .teacherId' | head -1)" "Thank you! I will sign and send it back tomorrow."
fi
# staff ↔ staff
mgr_user=$(call TEACHER GET /teacher/managers | jq -r '.[0].userId')
t_coord=$(call TEACHER POST /teacher/chat/staff-threads "$(jq -nc --arg c "$COORD_ID" '{coordinatorUserId:$c}')" | jq -r .id)
[ "$(call TEACHER GET "/teacher/chat/staff-threads/$t_coord/messages" | rows | jq length)" != 0 ] || {
    call TEACHER POST "/teacher/chat/staff-threads/$t_coord/messages" '{"body":"Rasha, the Week 6 number check is ready for review."}' >/dev/null
    call COORD POST "/coordinator/chat/threads/$t_coord/messages" '{"body":"Thanks Sara, I will look at it this afternoon."}' >/dev/null; }
t_mgr=$(call TEACHER POST /teacher/chat/staff-threads "$(jq -nc --arg m "$mgr_user" '{managerUserId:$m}')" | jq -r .id)
[ "$(call TEACHER GET "/teacher/chat/staff-threads/$t_mgr/messages" | rows | jq length)" != 0 ] || {
    call TEACHER POST "/teacher/chat/staff-threads/$t_mgr/messages" '{"body":"Ms Huda, may I take 1A to the library on Thursday?"}' >/dev/null
    call MANAGER POST "/management/chat/threads/$t_mgr/messages" '{"body":"Of course — please book it with the librarian."}' >/dev/null; }
admin_user=$(call MANAGER GET /management/admins | jq -r '.[0].userId')
m_admin=$(call MANAGER POST /management/chat/threads "$(jq -nc --arg a "$admin_user" '{adminUserId:$a}')" | jq -r .id)
[ "$(call MANAGER GET "/management/chat/threads/$m_admin/messages" | rows | jq length)" != 0 ] || {
    call MANAGER POST "/management/chat/threads/$m_admin/messages" '{"body":"Could we get two more coordinator accounts for next term?"}' >/dev/null
    call ADMIN POST "/admin/chat/threads/$m_admin/messages" '{"body":"Yes, send me their names and emails and I will add them today."}' >/dev/null; }
say "chat: parent ↔ teacher (photo + PDFs), coordinator, manager, admin; teacher ↔ coordinator/manager; manager ↔ admin"

# ---------------------------------------------------------------- complaints
complaint() { # staffId title body → id (found by title)
    local id; id=$(call PARENT GET "/children/$LAYLA/complaints" | rows | jq -r --arg t "$2" '[.[] | select(.title==$t)][0].id // empty')
    [ -n "$id" ] || id=$(call PARENT POST "/children/$LAYLA/complaints" "$(jq -nc --arg s "$1" --arg t "$2" --arg b "$3" '{staffId:$s, title:$t, body:$b, attachmentIds:[]}')" | jq -r .complaint.id)
    printf '%s' "$id"
}
new_complaint() { [ "$(call PARENT GET "/children/$LAYLA/complaints/$1" | rows | jq length)" -le 1 ]; }
c1=$(complaint "$SARA_ID" "Homework too long" "Layla spent more than an hour on Sunday's homework. Could it be shorter for Grade 1?")
if new_complaint "$c1"; then
    call TEACHER POST "/teacher/complaints/$c1/messages" '{"body":"Thank you for telling me. I will split it into two shorter parts from this week."}' >/dev/null
    call TEACHER PATCH "/teacher/complaints/$c1/status" '{"status":"resolved"}' >/dev/null
fi
c2=$(complaint "$COORD_ID" "Missing workbook pages" "Pages 14 to 18 of the maths workbook were not sent home.")
if new_complaint "$c2"; then
    call COORD POST "/coordinator/complaints/$c2/messages" '{"body":"I have asked the class teacher to send copies home tomorrow."}' >/dev/null
fi
c3=$(complaint "$MANAGER_ID" "Bus arrived late" "The school bus has arrived 20 minutes late three times this week.")
if new_complaint "$c3"; then
    call MANAGER POST "/management/complaints/$c3/messages" '{"body":"I am sorry about this. I have spoken to the transport company."}' >/dev/null
    call MANAGER PATCH "/management/complaints/$c3/status" '{"status":"resolved"}' >/dev/null
    call PARENT POST "/children/$LAYLA/complaints/$c3/messages" '{"body":"It was late again this morning."}' >/dev/null
    call PARENT PATCH "/children/$LAYLA/complaints/$c3/status" '{"status":"open"}' >/dev/null 2>&1 || true
    call MANAGER POST "/management/complaints/$c3/messages" '{"body":"Thank you — the driver has been changed from tomorrow. I will check on Thursday."}' >/dev/null
fi
say "complaints: teacher (resolved), coordinator (open), manager (resolved, reopened, answered)"
say "done — the dashboard is at $API/dashboard/"
