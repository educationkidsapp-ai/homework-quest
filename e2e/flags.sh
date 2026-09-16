#!/usr/bin/env bash
# Feature flags (§4), school themes (§3) and platform settings (§A), asserted over the API with the tokens of the
# two-school fixture (P2.3, prompt §3/§4/§A and §10 acceptance 2).
#
#   e2e/seed/seed.mjs      creates the fixture and writes the ids to e2e/.seed.json
#   e2e/flags.sh           reads that file and flips flags, themes and the platform name, then puts them all back
#
# Everything this script changes it restores before it exits, including on Ctrl-C: the four flag flips end where they
# started, school A's theme is written back exactly as `GET /admin/schools/{A}/theme` answered at the start, and the
# platform name is set back to what `GET /platform-settings` answered. The one row it cannot take back is the
# `flag_audit` trail, which is append-only by design — that is the point of assertion (b).
#
# Since P2.5 the theme this script reads at the start is the one `seed/seed.mjs` applied (school A's deep green
# "Al Noor" set), so the restore leaves the seeded theme in place rather than a platform default — which is what
# `e2e/themes.sh` asserts afterwards.
#
# Environment: E2E_BASE_URL, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, E2E_STAFF_PASSWORD, E2E_PARENT_PASSWORD,
# E2E_PARENT_AUTH (fake|firebase), E2E_FIREBASE_API_KEY, E2E_SEED_OUT, E2E_NO_JQ. Nothing is ever printed but ids,
# colours and verdicts — no password reaches curl's argv or this script's output.
#
# Exit 0 only when every assertion holds; 1 when one fails; 2 when one could not be run (see BLOCKED/SKIPPED).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
QA_API='https://homework-quest-api-625882725080.me-central1.run.app'
BASE="${E2E_BASE_URL:-$QA_API}"; BASE="${BASE%/}"
SEED_FILE="${E2E_SEED_OUT:-$REPO/e2e/.seed.json}"
ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-admin@quest.local}"

# The flag this script flips. `certificates` is `default_on` and guards nothing yet, so a flip is observable and
# harmless; `complaints` / `announcements` / `teacherQuestions` are the ones P3.0 and P4.0 will hang routes off.
FLAG=certificates
PLATFORM_NAME_DEFAULT='Schools Dashboard'
PLATFORM_NAME_TEST='QA Dashboard'
# Deliberately *not* school A's seeded `appName` ("Al Noor", P2.5): the (h) assertion below is that `/me.platformName`
# follows the theme's name, and a test name equal to the seeded one would hold whether or not the save did anything.
THEME_APP_NAME='Al Noor QA Flip'
# §3 measures `primaryInk` on `primary` AND on `ground` (ThemeDto: "primary is a light brand surface rather than a
# saturated fill"), so white ink over a dark navy surface only validates when the ground goes dark with it — and then
# the default red accent is 2.7:1 on that ground and the default mascot blue is below the 3:1 non-text bar. The valid
# theme is therefore a coherent dark set, not `primary`/`primaryInk` alone.
THEME_PRIMARY='#1F2A44'
THEME_PRIMARY_INK='#FFFFFF'
THEME_GROUND='#1F2A44'
THEME_ACCENT='#FF8A65'
THEME_MASCOT='#7FB3D5'
# §3's worked example of a refusal: the brand red as text on the near-white surface is ~3.9:1, below 4.5:1.
BAD_PRIMARY='#F3F2F2'
BAD_PRIMARY_INK='#EC3013'
BAD_LOGO='javascript:alert(1)'

# The 14 keys of §4 with the `default_on` of `V5__flags_themes.sql`, which is also `DEFAULT_FLAGS` in
# shared-api (`quest/api/ContentApi.kt`) — 10 on for what ships today, 4 off for what is not built yet.
# `FlagDefaultsTest` already asserts the two lists agree on the server; this one asserts the deployed database does.
DEFAULT_FLAGS_ON='lessons.pdf lessons.slides lessons.images lessons.manual levels.three retell.recording openAnswer.drawing parentPanel.arabic stickers.treasureChest certificates'
DEFAULT_FLAGS_OFF='complaints announcements teacherQuestions progress.weeklyEmail'

pass=0; failed=0; blocked=0

# ---------------------------------------------------------------- json, with jq when it is there and node when it is not
#
# The same reader as isolation.sh, so the two scripts behave identically on a machine with and without jq.
# E2E_NO_JQ=1 forces the node reader.

if [ -z "${E2E_NO_JQ:-}" ] && command -v jq >/dev/null 2>&1; then
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

# A flag key contains a dot, which the path reader above would split on, so the flag map is read with an exact-key
# lookup instead of a path. Reads the JSON object on stdin and echoes true/false (or nothing for a missing key).
flag_value() {                                    # flag_value KEY < json-object
  node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let o; try { o = JSON.parse(raw); } catch { process.exit(0); }
      const v = o && typeof o === "object" ? o[process.argv[1]] : undefined;
      if (v === true || v === false) console.log(String(v));
    });
  ' "$1" 2>/dev/null
}

# One element of a JSON array, as an object on one line. Used for the audit rows: the fields of a row have to be read
# from the SAME row, and `json '[].schoolId'` cannot do that — see `field` below.
pick() {                                          # pick INDEX < json-array
  node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let a; try { a = JSON.parse(raw); } catch { process.exit(0); }
      const row = Array.isArray(a) ? a[Number(process.argv[1])] : undefined;
      if (row !== undefined) console.log(JSON.stringify(row));
    });
  ' "$1" 2>/dev/null
}

# One field of a JSON object, printing `null` for a null and nothing for a missing key.
#
# This exists because `json` above is jq's `.path // empty`, and jq's `//` is "alternative", not "default": it fires
# on `false` and on `null` just as it does on a missing key. Reading `enabled` that way turns a flag that was switched
# OFF into an empty string, and reading `schoolId` that way makes the column action's null row — the very thing §4
# says a null `school_id` means — indistinguishable from a row that is not there. Every boolean and every nullable
# field below is read with this instead.
field() {                                         # field KEY < json-object
  node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let o; try { o = JSON.parse(raw); } catch { process.exit(0); }
      if (!o || typeof o !== "object") process.exit(0);
      const key = process.argv[1];
      if (!(key in o)) process.exit(0);
      const v = o[key];
      console.log(v === null ? "null" : typeof v === "object" ? JSON.stringify(v) : String(v));
    });
  ' "$1" 2>/dev/null
}

# A flag map with its keys sorted, on one line: two responses can then be compared as strings without a difference in
# key order counting as a difference in content.
canonical() {
  node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let o; try { o = JSON.parse(raw); } catch { process.exit(0); }
      if (!o || typeof o !== "object") process.exit(0);
      const out = {};
      for (const k of Object.keys(o).sort()) out[k] = o[k];
      console.log(JSON.stringify(out));
    });
  ' 2>/dev/null
}

# The keys of a flag map, one per line, sorted — so the count and the set can both be asserted.
flag_keys() {
  node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let o; try { o = JSON.parse(raw); } catch { process.exit(0); }
      if (o && typeof o === "object") for (const k of Object.keys(o).sort()) console.log(k);
    });
  ' 2>/dev/null
}

# ---------------------------------------------------------------- http
#   req METHOD PATH [token] [schoolId] [body] [extra header] -> writes the body to $BODY, the headers to $HEAD,
#                                                               echoes the status
#
# Four attempts with a growing backoff over a 502/503/504 or a transport failure, exactly as isolation.sh: a Cloud Run
# cold start is not a failure. Request bodies go to curl over stdin (`--data-binary @-`), never in its argv, so a
# password is not visible in `ps` to other local users.

BODY="$(mktemp)"
HEAD="$(mktemp)"
RC="$(mktemp)"
# Replaced by `restore` once there is state to put back; until then an early exit still cleans up after itself.
trap 'rm -f "$BODY" "$HEAD" "$RC"' EXIT

req() {
  local method="$1" path="$2" token="${3:-}" school="${4:-}" body="${5:-}" header="${6:-}"
  local args=(-s -o "$BODY" -D "$HEAD" -w '%{http_code}' -X "$method" --max-time 60)
  [ -n "$token" ] && args+=(-H "authorization: Bearer $token")
  [ -n "$school" ] && args+=(-H "X-School-Id: $school")
  [ -n "$header" ] && args+=(-H "$header")
  [ -n "$body" ] && args+=(-H 'content-type: application/json' --data-binary @-)
  local url="$path"; case "$path" in http*) ;; *) url="$BASE$path";; esac

  local attempt=1 status rc
  while :; do
    if [ -n "$body" ]; then status=$(printf '%s' "$body" | curl "${args[@]}" "$url" 2>/dev/null); rc=$?
    else                   status=$(curl "${args[@]}" "$url" 2>/dev/null); rc=$?
    fi
    [ "$rc" -ne 0 ] && status=000
    printf '%s' "$rc" > "$RC"
    [ "$attempt" -ge 4 ] && break
    case "$status" in
      000|502|503|504) sleep "$((attempt * 2))"; attempt=$((attempt + 1));;
      *) break;;
    esac
  done
  printf '%s' "$status"
}

transport_rc() { cat "$RC"; }

# The value of one response header from the last req, without its name or CRLF. Header names are case-insensitive and
# a redirect would leave more than one block in $HEAD, so the last match wins.
header() { grep -i "^$1:" "$HEAD" | tail -1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'; }

if [ -z "${E2E_NO_JQ:-}" ] && command -v jq >/dev/null 2>&1; then
  credentials() {
    local extra="${3:-}"; [ -z "$extra" ] && extra='{}'
    printf '%s' "$2" | jq -Rs --arg e "$1" --argjson x "$extra" '{email:$e, password:.} + $x'
  }
else
  credentials() {
    local extra="${3:-}"; [ -z "$extra" ] && extra='{}'
    printf '%s' "$2" | node -e '
      let p = ""; process.stdin.on("data", c => p += c).on("end", () => {
        process.stdout.write(JSON.stringify({ email: process.argv[1], password: p, ...JSON.parse(process.argv[2]) }));
      });
    ' "$1" "$extra"
  }
fi

# ---------------------------------------------------------------- verdicts

ok()      { pass=$((pass + 1));       printf 'PASS    %s\n' "$1"; }
bad()     { failed=$((failed + 1));   printf 'FAIL    %s — %s\n' "$1" "$2"; }
skipped() { blocked=$((blocked + 1)); printf 'BLOCKED %s — %s\n' "$1" "$2"; }

assert_status() { if [ "$3" = "$2" ]; then ok "$1"; else bad "$1" "expected $2, got $3 ($(json message < "$BODY" | head -1))"; fi; }
assert_equals() { if [ "$3" = "$2" ]; then ok "$1"; else bad "$1" "expected [$2], got [$3]"; fi; }

# ---------------------------------------------------------------- set-up

[ -f "$SEED_FILE" ] || { echo "no fixture at $SEED_FILE — run: node e2e/seed/seed.mjs" >&2; exit 1; }
[ -n "${E2E_ADMIN_PASSWORD:-}" ] || { echo "E2E_ADMIN_PASSWORD is not set" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "node is required (the flag map is read with it)" >&2; exit 1; }

case "$BASE" in
  http://localhost*|http://127.0.0.1*|http://0.0.0.0*) LOCAL=1;;
  *) LOCAL=0;;
esac
PARENT_AUTH="${E2E_PARENT_AUTH:-$([ "$LOCAL" = 1 ] && echo fake || echo firebase)}"

SCHOOL_A=$(seed schools.A.id);      SCHOOL_B=$(seed schools.B.id)
TEACHER_A_ID=$(seed schools.A.teacher.id); MANAGER_A_ID=$(seed schools.A.managerial.id)
TEACHER_A_EMAIL=$(seed schools.A.teacher.email); MANAGER_A_EMAIL=$(seed schools.A.managerial.email)
PARENT_A_UID=$(seed schools.A.parent.uid); PARENT_A_EMAIL=$(seed schools.A.parent.email)

echo "flags, themes and platform settings against $BASE"
echo "  school A $SCHOOL_A"
echo "  school B $SCHOOL_B"
echo ""

# Refuse early and legibly against a target that predates P2.1: every route below arrived with `feat(server): feature
# flags, school themes and platform settings`, and twelve 404s are a worse message than one line saying so.
probe=$(req GET "/schools/$SCHOOL_A/flags"); probe_rc=$(transport_rc)
if [ "$probe_rc" -ne 0 ]; then
  echo "FAIL    $BASE did not answer after four attempts (curl exit $probe_rc). Is the server up, and is E2E_BASE_URL right?"
  exit 1
elif [ "$probe" != 200 ]; then
  req GET /health >/dev/null; version=$(json version < "$BODY")
  echo "FAIL    $BASE has no GET /schools/{id}/flags (it is $probe) — it is running ${version:-an unknown revision},"
  echo "        which is older than P2.1 backend/flags-themes-settings. Deploy develop to this environment first."
  exit 1
fi

SIGN_IN_STATUS=0
sign_in() {
  SIGN_IN_STATUS=$(req POST /auth/sign-in '' '' "$(credentials "$1" "$2")")
  [ "$SIGN_IN_STATUS" = 429 ] && printf 'rate limited (429) signing in as %s — wait for the window to clear\n' "$1" >&2
  [ "$SIGN_IN_STATUS" = 200 ] && json token < "$BODY"
}

view_as() {
  local status
  status=$(req POST "/admin/users/$1/impersonate" "$2")
  [ "$status" = 200 ] && json token < "$BODY"
}

parent_token() {
  local uid="$1" email="$2" status key
  if [ "$PARENT_AUTH" = fake ]; then printf 'fake-token-%s' "$uid"; return; fi
  key="${E2E_FIREBASE_API_KEY:-$(json client[].api_key[].current_key < "$REPO/androidApp/src/qa/google-services.json" | head -1)}"
  [ -z "$key" ] && return
  status=$(req POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$key" '' '' \
    "$(credentials "$email" "${E2E_PARENT_PASSWORD:-}" '{"returnSecureToken":true}')")
  [ "$status" = 200 ] && json idToken < "$BODY"
}

ADMIN_TOKEN=$(sign_in "$ADMIN_EMAIL" "${E2E_ADMIN_PASSWORD}")
if [ -z "$ADMIN_TOKEN" ]; then bad "admin signs in" "POST /auth/sign-in refused $ADMIN_EMAIL"; echo ""; exit 1; fi
ok "admin signs in"

# A staff token is a real sign-in when the account has a password, and otherwise the ADMIN-only read-only "View as…"
# token. That token is enough for every GET here, but `ReadOnlyGuard` refuses *every* non-GET with 403 — so it would
# make assertion (f) pass for the wrong reason, and (f) is reported BLOCKED rather than asserted in that mode.
staff_token() {                                   # staff_token EMAIL USER_ID LABEL -> sets TOKEN and MODE
  TOKEN=$(sign_in "$1" "${E2E_STAFF_PASSWORD:-}")
  if [ -n "$TOKEN" ]; then MODE=session; ok "$3 signs in"; return; fi
  TOKEN=$(view_as "$2" "$ADMIN_TOKEN")
  if [ -n "$TOKEN" ]; then MODE=viewas; skipped "$3 signs in" "no password for $1; using the ADMIN View-as token (read-only)"; return; fi
  MODE=none
  bad "$3 signs in" "neither a password nor a View-as token for $1"
}

staff_token "$TEACHER_A_EMAIL" "$TEACHER_A_ID" "teacher A";    TEACHER_A=$TOKEN; TEACHER_A_MODE=$MODE
staff_token "$MANAGER_A_EMAIL" "$MANAGER_A_ID" "managerial A"; MANAGER_A=$TOKEN

PARENT_A=$(parent_token "$PARENT_A_UID" "$PARENT_A_EMAIL")
if [ -n "$PARENT_A" ]; then ok "parent A signs in ($PARENT_AUTH auth)"
else skipped "parent A signs in" "no parent token ($PARENT_AUTH auth)"; fi

# ---------------------------------------------------------------- restore
#
# Registered before the first write and run on EXIT, so an interrupted run still puts the environment back. Each
# step is a no-op until the matching "remember" line has run.

RESTORE_FLAG_A=''            # the value school A's flag had before (b)
RESTORE_FLAG_B=''
RESTORE_THEME=''             # school A's theme JSON exactly as GET answered it
RESTORE_PLATFORM_NAME=''

# put_back WHAT PATH PAYLOAD VERIFY_PATH WANTED READER… — one restore write.
#
# A restore that leaves the environment dirty must say so and make the run fail: the operator's last line of output
# must never claim a clean environment the server did not confirm. `req` has already retried a 5xx and a transport
# error four times, so a non-200 here means it stayed broken.
#
# What counts as dirty is decided by READING VERIFY_PATH back and comparing it to WANTED, not by the PUT's status —
# assuming the earlier write had landed would be the same sin in a smaller font, and it cuts both ways: a failed
# restore whose value is already right (the write that would have changed it failed too) is not a dirty environment,
# and a 500 there should not send an operator hunting for damage that was never done.
put_back() {
  local what="$1" path="$2" payload="$3" verify_path="$4" wanted="$5"; shift 5
  local status verify actual
  status=$(req PUT "$path" "$ADMIN_TOKEN" '' "$payload")
  if [ "$status" = 200 ]; then RESTORED=$((RESTORED + 1)); return 0; fi

  verify=$(req GET "$verify_path" "$ADMIN_TOKEN")
  if [ "$verify" != 200 ]; then
    printf 'FAIL    restore %s — PUT %s answered %s, and GET %s answered %s: cannot tell what state it is in\n' \
      "$what" "$path" "$status" "$verify_path" "$verify" >&2
    RESTORE_FAILED=$((RESTORE_FAILED + 1))
    return 1
  fi

  actual="$("$@" < "$BODY")"
  if [ "$actual" = "$wanted" ]; then
    printf 'note    restore %s — PUT %s answered %s, but %s already reads the wanted [%s]; nothing left dirty\n' \
      "$what" "$path" "$status" "$verify_path" "$wanted" >&2
    RESTORED=$((RESTORED + 1))
    return 0
  fi

  printf 'FAIL    restore %s — PUT %s answered %s; %s reads [%s], wanted [%s]\n' \
    "$what" "$path" "$status" "$verify_path" "${actual:-<empty>}" "$wanted" >&2
  RESTORE_FAILED=$((RESTORE_FAILED + 1))
  return 1
}

RESTORED=0
RESTORE_FAILED=0

restore() {
  local code=$?
  [ -n "$RESTORE_FLAG_A" ] && put_back "$FLAG for school A ($SCHOOL_A)" \
    "/admin/schools/$SCHOOL_A/flags/$FLAG" "{\"enabled\":$RESTORE_FLAG_A}" \
    "/schools/$SCHOOL_A/flags" "$RESTORE_FLAG_A" flag_value "$FLAG"
  [ -n "$RESTORE_FLAG_B" ] && put_back "$FLAG for school B ($SCHOOL_B)" \
    "/admin/schools/$SCHOOL_B/flags/$FLAG" "{\"enabled\":$RESTORE_FLAG_B}" \
    "/schools/$SCHOOL_B/flags" "$RESTORE_FLAG_B" flag_value "$FLAG"
  # The whole theme, not just its appName: a colour left dark is as dirty as a name left set.
  [ -n "$RESTORE_THEME" ] && put_back "school A's theme" \
    "/admin/schools/$SCHOOL_A/theme" "$RESTORE_THEME" \
    "/schools/$SCHOOL_A/theme" "$(canonical <<<"$RESTORE_THEME")" canonical
  [ -n "$RESTORE_PLATFORM_NAME" ] && put_back "the platform name" \
    /admin/platform-settings "$(printf '%s' "$RESTORE_PLATFORM_NAME" | node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>process.stdout.write(JSON.stringify({name:s})))')" \
    /platform-settings "$RESTORE_PLATFORM_NAME" field name

  if [ "$RESTORE_FAILED" -gt 0 ]; then
    printf '\n%s thing(s) left changed — %s IS NOT BACK AS IT WAS. Put the FAIL lines above right by hand.\n' \
      "$RESTORE_FAILED" "$BASE" >&2
    code=1
  elif [ "$RESTORED" -gt 0 ]; then
    printf '\nrestored: %s for both schools, school A'"'"'s theme and the platform name are back as they were\n' "$FLAG" >&2
  fi
  rm -f "$BODY" "$HEAD" "$RC"
  exit "$code"
}
trap restore EXIT INT TERM

# ================================================================ (a) the seeded defaults

status=$(req GET "/schools/$SCHOOL_A/flags")
keys=$(flag_keys < "$BODY")
count=$(printf '%s\n' "$keys" | grep -c . )
if [ "$status" != 200 ]; then bad "(a) GET /schools/A/flags" "expected 200, got $status"
elif [ "$count" != 14 ]; then bad "(a) GET /schools/A/flags has 14 keys" "got $count: $(tr '\n' ' ' <<<"$keys")"
else ok "(a) GET /schools/A/flags has 14 keys"; fi

wrong=''
for key in $DEFAULT_FLAGS_ON;  do [ "$(flag_value "$key" < "$BODY")" = true  ] || wrong="$wrong $key(want on)"; done
for key in $DEFAULT_FLAGS_OFF; do [ "$(flag_value "$key" < "$BODY")" = false ] || wrong="$wrong $key(want off)"; done
if [ -n "$wrong" ]; then
  bad "(a) the 14 keys are the seeded defaults (10 on, 4 off)" "wrong or missing:$wrong"
else
  ok "(a) the 14 keys are the seeded defaults (10 on, 4 off)"
fi

# The public route is the one the app syncs, so it must be cacheable (§4).
etag=$(header etag)
if [ -n "$etag" ]; then ok "(a) GET /schools/A/flags carries an ETag"
else bad "(a) GET /schools/A/flags carries an ETag" "no ETag header"; fi

# The route is `permitAll`, but a parent's app always calls it with a token attached, and a parent is the only reader
# of these flags the rest of the suite never exercises: everything else here is an ADMIN, teacher or managerial
# session. Captured before the parent request overwrites $BODY.
public_set=$(canonical < "$BODY")
if [ -z "$PARENT_A" ]; then
  skipped "(a) parent A: GET /schools/A/flags is the same 14 keys" "no parent token ($PARENT_AUTH auth)"
else
  status=$(req GET "/schools/$SCHOOL_A/flags" "$PARENT_A")
  parent_count=$(flag_keys < "$BODY" | grep -c .)
  parent_set=$(canonical < "$BODY")
  if [ "$status" != 200 ]; then bad "(a) parent A: GET /schools/A/flags is 200" "expected 200, got $status"
  elif [ "$parent_count" != 14 ]; then bad "(a) parent A: GET /schools/A/flags has 14 keys" "got $parent_count"
  elif [ "$parent_set" != "$public_set" ]; then
    bad "(a) parent A sees the same set as an anonymous caller" "parent: $parent_set"
  else ok "(a) parent A: GET /schools/A/flags is 200 with the same 14 keys as the anonymous read"; fi
fi

# ================================================================ (b) flip `certificates` off for A

RESTORE_FLAG_A=true                               # the default; corrected below if the fixture had an override
before_a=$(req GET "/schools/$SCHOOL_A/flags" >/dev/null; flag_value "$FLAG" < "$BODY")
before_b=$(req GET "/schools/$SCHOOL_B/flags" >/dev/null; flag_value "$FLAG" < "$BODY")
[ -n "$before_a" ] && RESTORE_FLAG_A=$before_a
[ -n "$before_b" ] && RESTORE_FLAG_B=$before_b

audit_before=$(req GET "/admin/flags/audit?limit=1" "$ADMIN_TOKEN" >/dev/null; pick 0 < "$BODY" | field id)

status=$(req PUT "/admin/schools/$SCHOOL_A/flags/$FLAG" "$ADMIN_TOKEN" '' '{"enabled":false}')
returned=$(flag_value "$FLAG" < "$BODY")
if [ "$status" != 200 ]; then bad "(b) ADMIN PUT /admin/schools/A/flags/$FLAG {false}" "expected 200, got $status ($(json message < "$BODY" | head -1))"
elif [ "$returned" != false ]; then bad "(b) the PUT answers A's whole flag set with $FLAG false" "$FLAG came back [$returned]"
else ok "(b) ADMIN PUT /admin/schools/A/flags/$FLAG {false} is 200 and answers the new set"; fi

# "within one fetch": the very next public GET must already say false. A per-instance 60s cache (`FeatureFlags`) can
# hold a stale value on a target with more than one Cloud Run instance, so a first fetch that disagrees is retried —
# and reported as BLOCKED with the delay rather than as a pass, because §4 asks for the next sync, not the next TTL.
req GET "/schools/$SCHOOL_A/flags" >/dev/null
after_a=$(flag_value "$FLAG" < "$BODY")
if [ "$after_a" = false ]; then
  ok "(b) GET /schools/A/flags.$FLAG is false on the next fetch"
else
  waited=0
  while [ "$waited" -lt 75 ] && [ "$after_a" != false ]; do
    sleep 5; waited=$((waited + 5))
    req GET "/schools/$SCHOOL_A/flags" >/dev/null; after_a=$(flag_value "$FLAG" < "$BODY")
  done
  if [ "$after_a" = false ]; then
    skipped "(b) GET /schools/A/flags.$FLAG is false on the next fetch" \
      "the next fetch still said [true]; it turned false after ${waited}s — another instance's FeatureFlags cache (60s TTL). Owner: backend"
  else
    bad "(b) GET /schools/A/flags.$FLAG is false on the next fetch" "still [$after_a] after ${waited}s"
  fi
fi

req GET "/schools/$SCHOOL_B/flags" >/dev/null
after_b=$(flag_value "$FLAG" < "$BODY")
assert_equals "(b) GET /schools/B/flags.$FLAG is unchanged" "$before_b" "$after_b"

status=$(req GET "/admin/flags/audit?limit=5" "$ADMIN_TOKEN")
newest=$(pick 0 < "$BODY")
audit_id=$(field id <<<"$newest")
audit_key=$(field flagKey <<<"$newest")
audit_school=$(field schoolId <<<"$newest")
audit_actor=$(field actorEmail <<<"$newest")
audit_enabled=$(field enabled <<<"$newest")
if [ "$status" != 200 ]; then bad "(b) GET /admin/flags/audit" "expected 200, got $status"
elif [ "$audit_id" = "$audit_before" ]; then bad "(b) the audit has a new row" "the newest row is still $audit_before"
elif [ "$audit_key" != "$FLAG" ]; then bad "(b) the new audit row names $FLAG" "flagKey is [$audit_key]"
elif [ "$audit_school" != "$SCHOOL_A" ]; then bad "(b) the new audit row has schoolId == A" "schoolId is [$audit_school]"
elif [ "$audit_enabled" != false ]; then bad "(b) the new audit row records the new value" "enabled is [$audit_enabled]"
elif [ "$audit_actor" != "$ADMIN_EMAIL" ]; then bad "(b) the new audit row has the ADMIN actor" "actorEmail is [$audit_actor]"
else ok "(b) GET /admin/flags/audit has a new row: $FLAG=false, school A, by $ADMIN_EMAIL"; fi

# ================================================================ (c) a route the flag guards

# §4's interceptor turns a flagged route into a 404 while its flag is off. Nothing in the deployed API is annotated
# yet: `FeatureFlagCoverageTest` exempts the eleven phase-1 controllers and lists FlagController, ThemeController and
# PlatformSettingsController as infrastructure, and the only `@FeatureFlag` handlers in the tree are the two in
# `FeatureFlagInterceptorTest`'s own test controller, which is not part of the application. This asserts that, from
# the OpenAPI document rather than from the source, so the moment P4.0 adds a flagged route the SKIP turns into a FAIL
# telling whoever reads it to assert the 404 here.
flagged_route=''
status=$(req GET /v3/api-docs)
if [ "$status" = 200 ]; then
  flagged_route=$(node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      let doc; try { doc = JSON.parse(raw); } catch { process.exit(0); }
      // A test-only interceptor endpoint, if the server ever exposes one, and any route whose description says a
      // flag guards it — the two shapes this assertion could use.
      for (const [path, ops] of Object.entries(doc.paths || {}))
        for (const [method, op] of Object.entries(ops))
          if (/__flagged|\/test\/flag/.test(path) || /feature flag .*guards|@FeatureFlag/i.test(op.description || ""))
            console.log(method.toUpperCase() + " " + path);
    });
  ' < "$BODY" | head -1)
fi
if [ -n "$flagged_route" ]; then
  skipped "(c) a route guarded by $FLAG is 404 for A and 200 for B" \
    "the OpenAPI document now has a flagged route ($flagged_route) — assert the 404 here instead of skipping. Owner: test"
else
  skipped "(c) a route guarded by $FLAG is 404 for A and 200 for B" \
    "SKIPPED (no flagged route yet — P4.0 adds teacherQuestions/announcements); no path in /v3/api-docs is behind @FeatureFlag"
fi

# ================================================================ (d) flip back on — no rebuild

status=$(req PUT "/admin/schools/$SCHOOL_A/flags/$FLAG" "$ADMIN_TOKEN" '' '{"enabled":true}')
returned=$(flag_value "$FLAG" < "$BODY")
if [ "$status" != 200 ]; then bad "(d) ADMIN PUT /admin/schools/A/flags/$FLAG {true}" "expected 200, got $status"
elif [ "$returned" != true ]; then bad "(d) the PUT answers $FLAG true" "$FLAG came back [$returned]"
else ok "(d) ADMIN PUT /admin/schools/A/flags/$FLAG {true} is 200"; fi

req GET "/schools/$SCHOOL_A/flags" >/dev/null
assert_equals "(d) GET /schools/A/flags.$FLAG is true again, with no restart" true "$(flag_value "$FLAG" < "$BODY")"

# ================================================================ (e) the column action

status=$(req PUT "/admin/flags/$FLAG/all" "$ADMIN_TOKEN" '' '{"enabled":false}')
if [ "$status" != 200 ]; then bad "(e) ADMIN PUT /admin/flags/$FLAG/all {false}" "expected 200, got $status ($(json message < "$BODY" | head -1))"
else
  ok "(e) ADMIN PUT /admin/flags/$FLAG/all {false} is 200"
  RESTORE_FLAG_B=${RESTORE_FLAG_B:-true}          # the column action writes an explicit row for B as well
fi

req GET "/schools/$SCHOOL_A/flags" >/dev/null; all_a=$(flag_value "$FLAG" < "$BODY")
req GET "/schools/$SCHOOL_B/flags" >/dev/null; all_b=$(flag_value "$FLAG" < "$BODY")
if [ "$all_a" = false ] && [ "$all_b" = false ]; then ok "(e) $FLAG is false for both schools"
else bad "(e) $FLAG is false for both schools" "A is [$all_a], B is [$all_b]"; fi

req GET "/admin/flags/audit?limit=5" "$ADMIN_TOKEN" >/dev/null
newest=$(pick 0 < "$BODY")
second=$(pick 1 < "$BODY")
all_id=$(field id <<<"$newest")
all_key=$(field flagKey <<<"$newest")
all_school=$(field schoolId <<<"$newest")     # the literal string "null" is the column action (§4)
all_actor=$(field actorEmail <<<"$newest")
all_enabled=$(field enabled <<<"$newest")
# "one audit row": the row under the newest must be the (d) flip, not a second row from this one action.
second_id=$(field id <<<"$second")
if [ "$all_id" = "$audit_id" ]; then bad "(e) the column action leaves one audit row with schoolId null" "no new row"
elif [ "$all_key" != "$FLAG" ]; then bad "(e) the new audit row names $FLAG" "flagKey is [$all_key]"
elif [ "$all_school" != null ]; then bad "(e) the new audit row has schoolId null" "schoolId is [$all_school]"
elif [ "$all_enabled" != false ]; then bad "(e) the new audit row records the new value" "enabled is [$all_enabled]"
elif [ "$(field schoolId <<<"$second")" = null ]; then
  bad "(e) the column action leaves exactly one audit row" "the row under it ($second_id) is a second schoolId-null row"
elif [ "$all_actor" != "$ADMIN_EMAIL" ]; then bad "(e) the new audit row has the ADMIN actor" "actorEmail is [$all_actor]"
else ok "(e) the column action leaves one audit row with schoolId null, by $ADMIN_EMAIL"; fi

status=$(req PUT "/admin/flags/$FLAG/all" "$ADMIN_TOKEN" '' '{"enabled":true}')
req GET "/schools/$SCHOOL_A/flags" >/dev/null; back_a=$(flag_value "$FLAG" < "$BODY")
req GET "/schools/$SCHOOL_B/flags" >/dev/null; back_b=$(flag_value "$FLAG" < "$BODY")
if [ "$status" = 200 ] && [ "$back_a" = true ] && [ "$back_b" = true ]; then ok "(e) PUT /admin/flags/$FLAG/all {true} switches both schools back on"
else bad "(e) PUT /admin/flags/$FLAG/all {true} switches both schools back on" "status $status, A is [$back_a], B is [$back_b]"; fi

# ================================================================ (f) who may flip, and what a scoped caller sees

if [ "$TEACHER_A_MODE" = session ]; then
  assert_status "(f) teacher A: PUT /admin/schools/A/flags/$FLAG is 403" 403 \
    "$(req PUT "/admin/schools/$SCHOOL_A/flags/$FLAG" "$TEACHER_A" '' '{"enabled":false}')"
else
  skipped "(f) teacher A: PUT /admin/schools/A/flags/$FLAG is 403" \
    "a View-as token refuses every write as read-only (ReadOnlyGuard), so a 403 here would not be the permission rule"
fi

if [ -n "$MANAGER_A" ]; then
  status=$(req GET /admin/flags "$MANAGER_A")
  matrix_schools=$(json 'schools[].schoolId' < "$BODY")
  definitions=$(json 'definitions[].key' < "$BODY" | grep -c .)
  if [ "$status" != 200 ]; then bad "(f) managerial A: GET /admin/flags" "expected 200, got $status"
  elif [ "$matrix_schools" != "$SCHOOL_A" ]; then bad "(f) managerial A: the matrix has only school A" "rows: [$(tr '\n' ' ' <<<"$matrix_schools")]"
  elif [ "$definitions" != 14 ]; then bad "(f) managerial A: the matrix defines all 14 flags" "got $definitions definitions"
  else ok "(f) managerial A: GET /admin/flags is 14 definitions and only school A's row"; fi
fi

status=$(req GET /admin/flags "$ADMIN_TOKEN")
admin_rows=$(json 'schools[].schoolId' < "$BODY")
if [ "$status" != 200 ]; then bad "(f) ADMIN: GET /admin/flags" "expected 200, got $status"
elif grep -Fqx -- "$SCHOOL_A" <<<"$admin_rows" && grep -Fqx -- "$SCHOOL_B" <<<"$admin_rows"; then ok "(f) ADMIN: the matrix has both schools"
else bad "(f) ADMIN: the matrix has both schools" "rows: [$(tr '\n' ' ' <<<"$admin_rows")]"; fi

# ================================================================ (g) the theme

status=$(req GET "/admin/schools/$SCHOOL_A/theme" "$ADMIN_TOKEN")
if [ "$status" != 200 ]; then
  bad "(g) ADMIN GET /admin/schools/A/theme" "expected 200, got $status"
else
  ok "(g) ADMIN GET /admin/schools/A/theme is 200"
  RESTORE_THEME="$(cat "$BODY")"                  # written back verbatim by the EXIT trap

  # A refusal first, so nothing is stored if the contrast rule is broken: #EC3013 text on #F3F2F2 is ~3.9:1.
  bad_theme=$(node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      const t = JSON.parse(raw);
      process.stdout.write(JSON.stringify({ ...t, primary: process.argv[1], primaryInk: process.argv[2] }));
    });
  ' "$BAD_PRIMARY" "$BAD_PRIMARY_INK" <<<"$RESTORE_THEME")
  status=$(req PUT "/admin/schools/$SCHOOL_A/theme" "$ADMIN_TOKEN" '' "$bad_theme")
  message=$(json message < "$BODY")
  if [ "$status" != 400 ]; then bad "(g) a pair below 4.5:1 is a 400" "expected 400, got $status"
  elif ! grep -q 'primaryInk' <<<"$message" || ! grep -q 'primary' <<<"$message"; then
    bad "(g) the 400 names the pair" "message was: $message"
  elif ! grep -Eq '[0-9]+(\.[0-9]+)?:1' <<<"$message"; then
    bad "(g) the 400 names the ratio" "message was: $message"
  else ok "(g) primaryInk $BAD_PRIMARY_INK on primary $BAD_PRIMARY is 400: $message"; fi

  # A javascript: logo is stored XSS on a public, cached route (SafeText) — also a 400, also before anything is written.
  js_theme=$(node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      process.stdout.write(JSON.stringify({ ...JSON.parse(raw), logoUrl: process.argv[1] }));
    });
  ' "$BAD_LOGO" <<<"$RESTORE_THEME")
  status=$(req PUT "/admin/schools/$SCHOOL_A/theme" "$ADMIN_TOKEN" '' "$js_theme")
  message=$(json message < "$BODY")
  if [ "$status" != 400 ]; then bad "(g) logoUrl \"$BAD_LOGO\" is a 400" "expected 400, got $status"
  elif ! grep -qi 'logourl' <<<"$message"; then bad "(g) the 400 names logoUrl" "message was: $message"
  else ok "(g) logoUrl \"$BAD_LOGO\" is 400: $message"; fi

  # Now a theme that holds: the school's current one turned into a dark navy set with school A's name on it. The
  # `worldPalettes` are left as they came back — their ink is already dark on a light `soft`, which is what §3 checks.
  good_theme=$(node -e '
    let raw = ""; process.stdin.on("data", c => raw += c).on("end", () => {
      const [primary, primaryInk, ground, accent, mascotColor, appName] = process.argv.slice(1);
      process.stdout.write(JSON.stringify({ ...JSON.parse(raw), primary, primaryInk, ground, accent, mascotColor, appName }));
    });
  ' "$THEME_PRIMARY" "$THEME_PRIMARY_INK" "$THEME_GROUND" "$THEME_ACCENT" "$THEME_MASCOT" "$THEME_APP_NAME" <<<"$RESTORE_THEME")
  status=$(req PUT "/admin/schools/$SCHOOL_A/theme" "$ADMIN_TOKEN" '' "$good_theme")
  saved_primary=$(json primary < "$BODY"); saved_name=$(json appName < "$BODY")
  if [ "$status" != 200 ]; then bad "(g) a valid theme is 200" "expected 200, got $status ($(json message < "$BODY" | head -1))"
  elif [ "$saved_primary" != "$THEME_PRIMARY" ] || [ "$saved_name" != "$THEME_APP_NAME" ]; then
    bad "(g) the 200 answers the theme it stored" "primary [$saved_primary], appName [$saved_name]"
  else ok "(g) a valid theme (primary $THEME_PRIMARY, primaryInk $THEME_PRIMARY_INK, appName \"$THEME_APP_NAME\") is 200"; fi

  # The public route the app reads: the new theme, an ETag, and a 304 for a client that already holds it.
  status=$(req GET "/schools/$SCHOOL_A/theme")
  public_primary=$(json primary < "$BODY"); theme_etag=$(header etag)
  if [ "$status" != 200 ]; then bad "(g) GET /schools/A/theme (public) is 200" "got $status"
  elif [ "$public_primary" != "$THEME_PRIMARY" ]; then bad "(g) the public theme is the one just saved" "primary is [$public_primary]"
  elif [ -z "$theme_etag" ]; then bad "(g) GET /schools/A/theme carries an ETag" "no ETag header"
  else ok "(g) GET /schools/A/theme (public) is the saved theme with an ETag"; fi

  if [ -n "$theme_etag" ]; then
    assert_status "(g) GET /schools/A/theme with If-None-Match is 304" 304 \
      "$(req GET "/schools/$SCHOOL_A/theme" '' '' '' "If-None-Match: $theme_etag")"
  else
    skipped "(g) GET /schools/A/theme with If-None-Match is 304" "the 200 carried no ETag to send back"
  fi

  # ============================================================== (h, first half) appName wins inside the school

  if [ -n "$TEACHER_A" ]; then
    status=$(req GET /me "$TEACHER_A")
    platform_name=$(json platformName < "$BODY")
    if [ "$status" != 200 ]; then bad "(h) teacher A: GET /me" "expected 200, got $status"
    else assert_equals "(h) teacher A: GET /me.platformName is the theme's appName" "$THEME_APP_NAME" "$platform_name"; fi
  else
    skipped "(h) teacher A: GET /me.platformName is the theme's appName" "no teacher A token"
  fi

  # Put school A's theme back, and check the name falls through to the platform's again.
  status=$(req PUT "/admin/schools/$SCHOOL_A/theme" "$ADMIN_TOKEN" '' "$RESTORE_THEME")
  restored_name=$(json appName < "$BODY")
  if [ "$status" != 200 ]; then bad "(g) the previous theme is restored" "PUT answered $status ($(json message < "$BODY" | head -1))"
  else ok "(g) school A's previous theme is restored"; RESTORE_THEME=''; fi

  req GET /platform-settings >/dev/null; platform_now=$(json name < "$BODY")
  expected_after_restore="${restored_name:-$platform_now}"
  if [ -n "$TEACHER_A" ]; then
    req GET /me "$TEACHER_A" >/dev/null
    assert_equals "(h) teacher A: GET /me.platformName falls back after the restore" "$expected_after_restore" "$(json platformName < "$BODY")"
  else
    skipped "(h) teacher A: GET /me.platformName falls back after the restore" "no teacher A token"
  fi
fi

# ================================================================ (h) platform settings

status=$(req GET /platform-settings)
name_before=$(json name < "$BODY")
if [ "$status" != 200 ]; then bad "(h) GET /platform-settings" "expected 200, got $status"
else assert_equals "(h) GET /platform-settings.name is \"$PLATFORM_NAME_DEFAULT\"" "$PLATFORM_NAME_DEFAULT" "$name_before"; fi
[ -n "$name_before" ] && RESTORE_PLATFORM_NAME="$name_before"

status=$(req PUT /admin/platform-settings "$ADMIN_TOKEN" '' "{\"name\":\"$PLATFORM_NAME_TEST\"}")
if [ "$status" != 200 ]; then bad "(h) ADMIN PUT /admin/platform-settings {name}" "expected 200, got $status ($(json message < "$BODY" | head -1))"
else ok "(h) ADMIN PUT /admin/platform-settings {name: \"$PLATFORM_NAME_TEST\"} is 200"; fi

req GET /platform-settings >/dev/null
assert_equals "(h) GET /platform-settings.name is \"$PLATFORM_NAME_TEST\"" "$PLATFORM_NAME_TEST" "$(json name < "$BODY")"

status=$(req PUT /admin/platform-settings "$ADMIN_TOKEN" '' "{\"name\":\"${name_before:-$PLATFORM_NAME_DEFAULT}\"}")
req GET /platform-settings >/dev/null
restored=$(json name < "$BODY")
if [ "$status" = 200 ] && [ "$restored" = "${name_before:-$PLATFORM_NAME_DEFAULT}" ]; then
  ok "(h) the platform name is restored to \"$restored\""; RESTORE_PLATFORM_NAME=''
else
  bad "(h) the platform name is restored" "PUT answered $status, GET says [$restored]"
fi

# ---------------------------------------------------------------- verdict

echo ""
echo "$pass passed, $failed failed, $blocked blocked/skipped"
[ "$failed" -gt 0 ] && exit 1
[ "$blocked" -gt 0 ] && exit 2
exit 0
