#!/usr/bin/env bash
# The two seeded school themes, seen the way the app sees them (P2.5, prompt §3 and §10 acceptance 1: "two schools in
# QA with different logos and colours").
#
#   e2e/seed/seed.mjs      applies a distinct theme to each school and writes it to e2e/.seed.json
#   e2e/themes.sh          asserts the public routes serve exactly those two themes, and that they differ
#
# Public routes only: no token, no password, nothing to restore. It changes nothing, so it is safe to run at any
# point — but run it *before* `flags.sh` rather than during it, since flags.sh deliberately writes a throwaway theme
# over school A's for the length of assertion (g).
#
# Environment: E2E_BASE_URL, E2E_SEED_OUT, E2E_NO_JQ. Nothing but ids, colours and verdicts is ever printed.
#
# Exit 0 only when every assertion holds; 1 when one fails; 2 when one could not be run.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
QA_API='https://homework-quest-api-625882725080.me-central1.run.app'
BASE="${E2E_BASE_URL:-$QA_API}"; BASE="${BASE%/}"
SEED_FILE="${E2E_SEED_OUT:-$REPO/e2e/.seed.json}"

pass=0; failed=0; blocked=0

# ---------------------------------------------------------------- json, with jq when it is there and node when it is not
#
# The same reader as isolation.sh and flags.sh, so all three behave identically with and without jq.
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

# ---------------------------------------------------------------- http
#   req PATH [extra header] -> writes the body to $BODY and the headers to $HEAD, echoes the status
#
# GET only, and the same four attempts with a growing backoff as the other two scripts, so a Cloud Run cold start is
# not a failure.

BODY="$(mktemp)"
HEAD="$(mktemp)"
trap 'rm -f "$BODY" "$HEAD"' EXIT

req() {
  local path="$1" extra="${2:-}"
  local args=(-s -o "$BODY" -D "$HEAD" -w '%{http_code}' -X GET --max-time 60)
  [ -n "$extra" ] && args+=(-H "$extra")

  local attempt=1 status rc
  while :; do
    status=$(curl "${args[@]}" "$BASE$path" 2>/dev/null); rc=$?
    [ "$rc" -ne 0 ] && status=000
    [ "$attempt" -ge 4 ] && break
    case "$status" in
      000|502|503|504) sleep "$((attempt * 2))"; attempt=$((attempt + 1));;
      *) break;;
    esac
  done
  printf '%s' "$status"
}

# The value of one response header from the last req, without its name or CRLF; the last match wins.
header() { grep -i "^$1:" "$HEAD" | tail -1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'; }

# ---------------------------------------------------------------- verdicts

ok()      { pass=$((pass + 1));       printf 'PASS    %s\n' "$1"; }
bad()     { failed=$((failed + 1));   printf 'FAIL    %s — %s\n' "$1" "$2"; }
skipped() { blocked=$((blocked + 1)); printf 'BLOCKED %s — %s\n' "$1" "$2"; }

# assert_equals NAME EXPECTED ACTUAL
assert_equals() { if [ "$3" = "$2" ]; then ok "$1"; else bad "$1" "expected [$2], got [$3]"; fi; }

# ---------------------------------------------------------------- set-up

[ -f "$SEED_FILE" ] || { echo "no fixture at $SEED_FILE — run: node e2e/seed/seed.mjs" >&2; exit 1; }

SCHOOL_A=$(seed schools.A.id);            SCHOOL_B=$(seed schools.B.id)
CODE_A=$(seed schools.A.code);            CODE_B=$(seed schools.B.code)
NAME_A=$(seed schools.A.theme.appName);   NAME_B=$(seed schools.B.theme.appName)
PRIMARY_A=$(seed schools.A.theme.primary); PRIMARY_B=$(seed schools.B.theme.primary)
LOGO_A=$(seed schools.A.theme.logoUrl);   LOGO_B=$(seed schools.B.theme.logoUrl)

if [ -z "$NAME_A" ] || [ -z "$NAME_B" ]; then
  echo "$SEED_FILE has no theme for both schools — it predates P2.5. Run: node e2e/seed/seed.mjs" >&2
  exit 1
fi

echo "school themes against $BASE"
echo "  school A $SCHOOL_A  $CODE_A  \"$NAME_A\"  primary $PRIMARY_A"
echo "  school B $SCHOOL_B  $CODE_B  \"$NAME_B\"  primary $PRIMARY_B"
echo ""

# ================================================================ (a) the join-by-code lookup carries the theme

# `JoinSchoolInfo.theme` travels with the by-code answer so the app's confirm step can run its colour transition
# without a second request (§3). This is the route a parent hits before she has any token at all.

# by_code KEY CODE APPNAME PRIMARY LOGOURL
by_code() {
  local key="$1" code="$2" name="$3" primary="$4" logo="$5" status got_name got_primary got_logo
  status=$(req "/schools/by-code/$code")
  if [ "$status" != 200 ]; then bad "(a) GET /schools/by-code/$code is 200" "got $status"; return; fi
  got_name=$(json theme.appName < "$BODY")
  got_primary=$(json theme.primary < "$BODY")
  got_logo=$(json theme.logoUrl < "$BODY")
  if [ "$got_name" != "$name" ] || [ "$got_primary" != "$primary" ]; then
    bad "(a) GET /schools/by-code/$code carries school $key's theme" "appName [$got_name] primary [$got_primary]"
  elif [ "$got_logo" != "$logo" ]; then
    bad "(a) GET /schools/by-code/$code carries school $key's logo" "logoUrl [$got_logo]"
  else
    ok "(a) GET /schools/by-code/$code is \"$got_name\", primary $got_primary, logo $got_logo"
  fi
}

by_code A "$CODE_A" "$NAME_A" "$PRIMARY_A" "$LOGO_A"
by_code B "$CODE_B" "$NAME_B" "$PRIMARY_B" "$LOGO_B"

# ================================================================ (b) the two schools are visibly different

if [ "$NAME_A" = "$NAME_B" ] || [ "$PRIMARY_A" = "$PRIMARY_B" ] || [ "$LOGO_A" = "$LOGO_B" ]; then
  bad "(b) the two schools have different names, colours and logos" \
      "A \"$NAME_A\"/$PRIMARY_A, B \"$NAME_B\"/$PRIMARY_B"
else
  ok "(b) the two schools differ: \"$NAME_A\" $PRIMARY_A vs \"$NAME_B\" $PRIMARY_B, and their logos differ"
fi

# ================================================================ (c) both public theme routes are cacheable

# §3: the theme route is "public, cached, ETag" — the app re-fetches it on every start, so a 304 is the common case.

# public_theme KEY SCHOOL_ID APPNAME PRIMARY
public_theme() {
  local key="$1" id="$2" name="$3" primary="$4" status etag got_name got_primary
  status=$(req "/schools/$id/theme")
  etag=$(header etag)
  got_name=$(json appName < "$BODY")
  got_primary=$(json primary < "$BODY")
  if [ "$status" != 200 ]; then bad "(c) GET /schools/$key/theme (public) is 200" "got $status"; return; fi
  if [ "$got_name" != "$name" ] || [ "$got_primary" != "$primary" ]; then
    bad "(c) GET /schools/$key/theme is the seeded theme" "appName [$got_name] primary [$got_primary]"
  elif [ -z "$etag" ]; then
    bad "(c) GET /schools/$key/theme carries an ETag" "no ETag header"
  else
    ok "(c) GET /schools/$key/theme is \"$got_name\" with an ETag"
  fi

  if [ -z "$etag" ]; then
    skipped "(c) GET /schools/$key/theme with If-None-Match is 304" "the 200 carried no ETag to send back"
  else
    assert_equals "(c) GET /schools/$key/theme with If-None-Match is 304" 304 "$(req "/schools/$id/theme" "If-None-Match: $etag")"
  fi
}

public_theme A "$SCHOOL_A" "$NAME_A" "$PRIMARY_A"
public_theme B "$SCHOOL_B" "$NAME_B" "$PRIMARY_B"

# ---------------------------------------------------------------- verdict

echo ""
echo "$pass passed, $failed failed, $blocked blocked/skipped"
[ "$failed" -gt 0 ] && exit 1
[ "$blocked" -gt 0 ] && exit 2
exit 0
