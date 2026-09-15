#!/usr/bin/env bash
# Cross-school isolation, asserted over the API with the tokens of the two-school fixture (P1.6, prompt §2 and §10).
#
#   e2e/seed/seed.mjs      creates the fixture and writes the ids to e2e/.seed.json
#   e2e/isolation.sh       reads that file and asserts them
#
# Environment: E2E_BASE_URL, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, E2E_STAFF_PASSWORD, E2E_PARENT_PASSWORD,
# E2E_PARENT_AUTH (fake|firebase), E2E_FIREBASE_API_KEY, E2E_SEED_OUT. Nothing is ever printed but ids and verdicts.
#
# Exit 0 only when every assertion holds; 1 when one fails; 2 when one could not be run (see BLOCKED).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
QA_API='https://homework-quest-api-625882725080.me-central1.run.app'
BASE="${E2E_BASE_URL:-$QA_API}"; BASE="${BASE%/}"
SEED_FILE="${E2E_SEED_OUT:-$REPO/e2e/.seed.json}"
ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-admin@quest.local}"
MAP_FROM=2027-03-01
MAP_TO=2027-03-31

pass=0; failed=0; blocked=0

# ---------------------------------------------------------------- json, with jq when it is there and node when it is not

# E2E_NO_JQ=1 forces the node reader, so the fallback is exercised on a machine that has jq.
if [ -z "${E2E_NO_JQ:-}" ] && command -v jq >/dev/null 2>&1; then
  # path syntax: `a.b.c`, `[].id`, `islands[].lessonId` — the same string both back-ends understand.
  json() { jq -r ".$1 // empty" 2>/dev/null; }
else
  json() {
    node -e '
      const path = process.argv[1];
      let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
        let node; try { node = JSON.parse(raw); } catch { process.exit(0); }
        let values = [node];
        for (const seg of path.split(".").filter(Boolean)) {
          const each = seg.endsWith("[]");
          const key = each ? seg.slice(0, -2) : seg;
          const next = [];
          for (const v of values) {
            const picked = key === "" ? v : (v == null ? undefined : v[key]);
            if (picked === undefined || picked === null) continue;
            if (each) { for (const item of (Array.isArray(picked) ? picked : [])) next.push(item); }
            else next.push(picked);
          }
          values = next;
        }
        for (const v of values) console.log(typeof v === "object" ? JSON.stringify(v) : String(v));
      });
    ' "$1" 2>/dev/null
  }
fi

seed() { json "$1" < "$SEED_FILE"; }

# ---------------------------------------------------------------- http
#   req METHOD PATH [token] [schoolId] [body]  ->  writes the body to $BODY, echoes the status

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

req() {
  local method="$1" path="$2" token="${3:-}" school="${4:-}" body="${5:-}"
  local args=(-s -o "$BODY" -w '%{http_code}' -X "$method" --max-time 60)
  [ -n "$token" ] && args+=(-H "authorization: Bearer $token")
  [ -n "$school" ] && args+=(-H "X-School-Id: $school")
  [ -n "$body" ] && args+=(-H 'content-type: application/json' --data-binary "$body")
  local url="$path"; case "$path" in http*) ;; *) url="$BASE$path";; esac
  curl "${args[@]}" "$url" 2>/dev/null || echo 000
}

# ---------------------------------------------------------------- verdicts

ok()      { pass=$((pass + 1));       printf 'PASS    %s\n' "$1"; }
bad()     { failed=$((failed + 1));   printf 'FAIL    %s — %s\n' "$1" "$2"; }
skipped() { blocked=$((blocked + 1)); printf 'BLOCKED %s — %s\n' "$1" "$2"; }

# assert_status NAME EXPECTED ACTUAL
assert_status() { if [ "$3" = "$2" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi; }

# ---------------------------------------------------------------- tokens

sign_in() {                                  # sign_in EMAIL PASSWORD -> token on stdout, empty when refused
  local status
  status=$(req POST /auth/sign-in '' '' "$(printf '{"email":"%s","password":"%s"}' "$1" "$2")")
  [ "$status" = 200 ] && json token < "$BODY"
}

view_as() {                                  # view_as ADMIN_TOKEN USER_ID -> read-only token
  local status
  status=$(req POST "/admin/users/$1/impersonate" "$2")
  [ "$status" = 200 ] && json token < "$BODY"
}

parent_token() {                             # parent_token UID EMAIL -> bearer token
  local uid="$1" email="$2" status key
  if [ "${PARENT_AUTH}" = fake ]; then printf 'fake-token-%s' "$uid"; return; fi
  key="${E2E_FIREBASE_API_KEY:-$(json client[].api_key[].current_key < "$REPO/androidApp/src/qa/google-services.json" | head -1)}"
  [ -z "$key" ] && return
  status=$(req POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$key" '' '' \
    "$(printf '{"email":"%s","password":"%s","returnSecureToken":true}' "$email" "${E2E_PARENT_PASSWORD:-}")")
  [ "$status" = 200 ] && json idToken < "$BODY"
}

# ---------------------------------------------------------------- set-up

[ -f "$SEED_FILE" ] || { echo "no fixture at $SEED_FILE — run: node e2e/seed/seed.mjs" >&2; exit 1; }
[ -n "${E2E_ADMIN_PASSWORD:-}" ] || { echo "E2E_ADMIN_PASSWORD is not set" >&2; exit 1; }

case "$BASE" in
  http://localhost*|http://127.0.0.1*|http://0.0.0.0*) LOCAL=1;;
  *) LOCAL=0;;
esac
PARENT_AUTH="${E2E_PARENT_AUTH:-$([ "$LOCAL" = 1 ] && echo fake || echo firebase)}"

SCHOOL_A=$(seed schools.A.id);      SCHOOL_B=$(seed schools.B.id)
LESSON_A=$(seed schools.A.lesson.id); LESSON_B=$(seed schools.B.lesson.id)
CODE_B=$(seed schools.B.code)
TEACHER_A_ID=$(seed schools.A.teacher.id); TEACHER_B_ID=$(seed schools.B.teacher.id)
MANAGER_A_ID=$(seed schools.A.managerial.id)
TEACHER_A_EMAIL=$(seed schools.A.teacher.email); TEACHER_B_EMAIL=$(seed schools.B.teacher.email)
MANAGER_A_EMAIL=$(seed schools.A.managerial.email)
CHILD_A=$(seed schools.A.child.id)
PARENT_A_UID=$(seed schools.A.parent.uid); PARENT_A_EMAIL=$(seed schools.A.parent.email)

echo "isolation against $BASE"
echo "  school A $SCHOOL_A   lesson A $LESSON_A"
echo "  school B $SCHOOL_B   lesson B $LESSON_B"
echo ""

# `GET /auth/sign-in` is 405 wherever P1.3 mapped the route and 401/403/404 where it is not: a target that predates
# backend/dashboard-auth has none of the endpoints below, and saying so beats twelve confusing FAILs.
probe=$(req GET /auth/sign-in)
if [ "$probe" != 405 ]; then
  echo "FAIL    $BASE has no POST /auth/sign-in (GET is $probe, not 405) — it is running $(req GET /health >/dev/null; json version < "$BODY"),"
  echo "        which is older than P1.3 backend/dashboard-auth. Deploy develop to this environment first."
  exit 1
fi

ADMIN_TOKEN=$(sign_in "$ADMIN_EMAIL" "${E2E_ADMIN_PASSWORD}")
if [ -z "$ADMIN_TOKEN" ]; then bad "admin signs in" "POST /auth/sign-in refused $ADMIN_EMAIL"; echo ""; exit 1; fi
ok "admin signs in"

# A staff token is a real sign-in when the account has a password, and otherwise the ADMIN-only read-only "View as…"
# token, which is enough for every read assertion. Writes need a real session, so they are reported BLOCKED.
# Sets TOKEN and MODE (session | viewas | none) rather than echoing, so the verdict lines are not captured.
staff_token() {                              # staff_token EMAIL USER_ID LABEL
  TOKEN=$(sign_in "$1" "${E2E_STAFF_PASSWORD:-}")
  if [ -n "$TOKEN" ]; then MODE=session; ok "$3 signs in"; return; fi
  TOKEN=$(view_as "$2" "$ADMIN_TOKEN")
  if [ -n "$TOKEN" ]; then MODE=viewas; skipped "$3 signs in" "no password for $1; using the ADMIN View-as token (read-only)"; return; fi
  MODE=none
  bad "$3 signs in" "neither a password nor a View-as token for $1"
}

staff_token "$TEACHER_A_EMAIL" "$TEACHER_A_ID" "teacher A";   TEACHER_A=$TOKEN; TEACHER_A_MODE=$MODE
staff_token "$TEACHER_B_EMAIL" "$TEACHER_B_ID" "teacher B";   TEACHER_B=$TOKEN; TEACHER_B_MODE=$MODE
staff_token "$MANAGER_A_EMAIL" "$MANAGER_A_ID" "managerial A"; MANAGER_A=$TOKEN; MANAGER_A_MODE=$MODE

# ---------------------------------------------------------------- teacher A

contains() { grep -Fqx -- "$2" <<<"$1"; }

if [ -n "$TEACHER_A" ]; then
  status=$(req GET /admin/lessons "$TEACHER_A")
  ids=$(json '[].id' < "$BODY")
  if [ "$status" != 200 ]; then bad "teacher A: GET /admin/lessons" "expected 200, got $status"
  elif ! contains "$ids" "$LESSON_A"; then bad "teacher A: GET /admin/lessons lists her own lesson" "A's lesson is missing"
  elif contains "$ids" "$LESSON_B"; then bad "teacher A: GET /admin/lessons hides school B" "B's lesson is in the list"
  else ok "teacher A: GET /admin/lessons has A's lesson and not B's ($(wc -l <<<"$ids" | tr -d ' ') row(s))"; fi

  assert_status "teacher A: GET /admin/lessons/<B lesson> is 404" 404 "$(req GET "/admin/lessons/$LESSON_B" "$TEACHER_A")"

  status=$(req GET /admin/schools "$TEACHER_A")
  school_ids=$(json '[].id' < "$BODY")
  if [ "$status" != 200 ]; then bad "teacher A: GET /admin/schools" "expected 200, got $status"
  elif [ "$school_ids" != "$SCHOOL_A" ]; then bad "teacher A: GET /admin/schools is only her school" "got [$(tr '\n' ' ' <<<"$school_ids")]"
  elif grep -Fq "$CODE_B" "$BODY"; then bad "teacher A: GET /admin/schools leaks no other join code" "school B's code is in the payload"
  else ok "teacher A: GET /admin/schools is only school A, with no other school's code"; fi

  assert_status "teacher A: X-School-Id school B is 403" 403 "$(req GET /admin/lessons "$TEACHER_A" "$SCHOOL_B")"

  wrong='{"curriculum":"british","grade":1,"subject":"english","date":"2027-03-04","source":"manual","title":"e2e isolation — wrong subject"}'
  if [ "$TEACHER_A_MODE" = session ]; then
    status=$(req POST /admin/lessons "$TEACHER_A" '' "$wrong")
    message=$(json message < "$BODY")
    if [ "$status" != 403 ]; then bad "teacher A: POST /admin/lessons (english) is 403" "expected 403, got $status"
    elif ! grep -qi 'subject' <<<"$message"; then bad "teacher A: the 403 names 'subject'" "message was: $message"
    else ok "teacher A: POST /admin/lessons (english) is 403 naming 'subject'"; fi
  else
    skipped "teacher A: POST /admin/lessons (english) is 403 naming 'subject'" \
      "a View-as token refuses every write as read-only, so the subject rule cannot be reached"
  fi
fi

# ---------------------------------------------------------------- teacher B (the mirror image)

if [ -n "$TEACHER_B" ]; then
  status=$(req GET /admin/lessons "$TEACHER_B")
  ids=$(json '[].id' < "$BODY")
  if [ "$status" != 200 ]; then bad "teacher B: GET /admin/lessons" "expected 200, got $status"
  elif ! contains "$ids" "$LESSON_B"; then bad "teacher B: GET /admin/lessons lists her own lesson" "B's lesson is missing"
  elif contains "$ids" "$LESSON_A"; then bad "teacher B: GET /admin/lessons hides school A" "A's lesson is in the list"
  else ok "teacher B: GET /admin/lessons has B's lesson and not A's"; fi
  assert_status "teacher B: GET /admin/lessons/<A lesson> is 404" 404 "$(req GET "/admin/lessons/$LESSON_A" "$TEACHER_B")"
fi

# ---------------------------------------------------------------- managerial A

if [ -n "$MANAGER_A" ]; then
  new='{"curriculum":"british","grade":1,"subject":"math","date":"2027-03-05","source":"manual","title":"e2e isolation — managerial"}'
  if [ "$MANAGER_A_MODE" = session ]; then
    status=$(req POST /admin/lessons "$MANAGER_A" '' "$new")
    assert_status "managerial A: POST /admin/lessons is 403" 403 "$status"
  else
    skipped "managerial A: POST /admin/lessons is 403" \
      "a View-as token refuses every write as read-only, so the managerial rule cannot be reached"
  fi

  status=$(req GET /admin/lessons "$MANAGER_A")
  ids=$(json '[].id' < "$BODY")
  if [ "$status" != 200 ]; then bad "managerial A: GET /admin/lessons" "expected 200, got $status"
  elif contains "$ids" "$LESSON_B"; then bad "managerial A: GET /admin/lessons hides school B" "B's lesson is in the list"
  else ok "managerial A: GET /admin/lessons reads her school and not B's"; fi
fi

# ---------------------------------------------------------------- admin

status=$(req GET /admin/schools "$ADMIN_TOKEN")
school_ids=$(json '[].id' < "$BODY")
if [ "$status" != 200 ]; then bad "admin: GET /admin/schools" "expected 200, got $status"
elif contains "$school_ids" "$SCHOOL_A" && contains "$school_ids" "$SCHOOL_B"; then ok "admin: GET /admin/schools has both schools"
else bad "admin: GET /admin/schools has both schools" "got [$(tr '\n' ' ' <<<"$school_ids")]"; fi

status=$(req GET /admin/lessons "$ADMIN_TOKEN" "$SCHOOL_B")
ids=$(json '[].id' < "$BODY")
if [ "$status" != 200 ]; then bad "admin: X-School-Id school B" "expected 200, got $status"
elif ! contains "$ids" "$LESSON_B"; then bad "admin: X-School-Id school B lists B's lesson" "B's lesson is missing"
elif contains "$ids" "$LESSON_A"; then bad "admin: X-School-Id school B hides A" "A's lesson is in the list"
else ok "admin: X-School-Id school B lists only school B"; fi

# ---------------------------------------------------------------- parent A

PARENT_A=$(parent_token "$PARENT_A_UID" "$PARENT_A_EMAIL")
if [ -z "$PARENT_A" ]; then
  skipped "parent A: GET /children/<child>/map has A's lesson and not B's" "no parent token ($PARENT_AUTH auth)"
else
  status=$(req GET "/children/$CHILD_A/map?from=$MAP_FROM&to=$MAP_TO" "$PARENT_A")
  lessons=$(json 'islands[].lessonId' < "$BODY")
  if [ "$status" != 200 ]; then bad "parent A: GET /children/<child>/map" "expected 200, got $status"
  elif ! contains "$lessons" "$LESSON_A"; then bad "parent A: the map has A's lesson" "A's lesson is not on the map"
  elif contains "$lessons" "$LESSON_B"; then bad "parent A: the map hides B's lesson" "B's lesson is on the map"
  else ok "parent A: the map has A's lesson and not B's"; fi
fi

# ---------------------------------------------------------------- verdict

echo ""
echo "$pass passed, $failed failed, $blocked blocked"
[ "$failed" -gt 0 ] && exit 1
[ "$blocked" -gt 0 ] && exit 2
exit 0
