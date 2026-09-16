#!/usr/bin/env bash
# Minutes used per workflow over the last N days, as a job summary — and the projected monthly total.
#
# Why it does not use /actions/runs/<id>/timing: on this account that endpoint answers `total_ms: 0` for every job,
# so the minutes are computed the way GitHub bills them instead — each job rounded UP to the whole minute and
# multiplied by its runner's rate (Linux ×1, Windows ×2, macOS ×10).
#
#   DAYS=7 BUDGET=1500 .github/scripts/actions-cost.sh
#
# Runs on a Mac as well as on a runner: every date is handled by `jq` (`fromdateiso8601`, `gmtime`), never by
# `date -d`, which is GNU-only. Needs `gh` (authenticated, `actions: read`), `jq` and `awk`. Writes the table to
# $GITHUB_STEP_SUMMARY when set, always to stdout, and leaves `projected` and `over_budget` in $GITHUB_OUTPUT.
set -euo pipefail

REPO=${GITHUB_REPOSITORY:?set GITHUB_REPOSITORY (owner/name)}
DAYS=${DAYS:-7}
BUDGET=${BUDGET:-1500}
MAX_RUNS=${MAX_RUNS:-300}

since=$(jq -rn --argjson d "$DAYS" '(now - ($d * 86400)) | gmtime | strftime("%Y-%m-%d")')
rows=$(mktemp)
trap 'rm -f "$rows"' EXIT

echo "Collecting runs created since $since in $REPO…"
# Grouped by `.path` (the workflow file), not `.name`: a `run-name:` overrides the display name per run and would
# scatter one workflow across a dozen rows.
runs=$( { gh api --paginate "repos/$REPO/actions/runs?created=%3E%3D$since&per_page=100" \
  --jq '.workflow_runs[] | [.id, (.path | sub("^.github/workflows/"; ""))] | @tsv' || true; } | head -n "$MAX_RUNS")

while IFS=$'\t' read -r id workflow; do
  [ -n "${id:-}" ] || continue
  # jq turns the two ISO timestamps into a duration in seconds, so no `date` is involved at all.
  gh api "repos/$REPO/actions/runs/$id/jobs?per_page=100" \
    --jq '.jobs[] | select(.started_at != null and .completed_at != null and .conclusion != "skipped")
          | [(.labels[0] // "ubuntu-latest"),
             ((.completed_at | fromdateiso8601) - (.started_at | fromdateiso8601))] | @tsv' |
    while IFS=$'\t' read -r label secs; do
      [ "${secs:-0}" -ge 0 ] 2>/dev/null || secs=0
      case "$label" in
        macos*) rate=10 ;;
        windows*) rate=2 ;;
        *) rate=1 ;;
      esac
      printf '%s\t%s\n' "$workflow" "$(((secs + 59) / 60 * rate))" >>"$rows"
    done
done <<<"$runs"

report=$(
  awk -F'\t' -v days="$DAYS" '
    { minutes[$1] += $2; total += $2 }
    END {
      printf "| Workflow | Billed minutes (last %d days) |\n|---|--:|\n", days
      for (w in minutes) printf "| %s | %d |\n", w, minutes[w]
      printf "| **Total** | **%d** |\n", total
      printf "PROJECTED %d\n", total * 30 / days
    }' "$rows"
)

projected=$(echo "$report" | awk '/^PROJECTED/ { print $2 }')
table=$(echo "$report" | grep -v '^PROJECTED')

{
  echo "## Actions minutes — last $DAYS days"
  echo
  echo "$table"
  echo
  echo "Projected for a 30-day month: **${projected:-0}** of the ${BUDGET}-minute budget."
  echo "Rounded up per job and multiplied by the runner rate (Linux ×1, Windows ×2, macOS ×10)."
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'projected=%s\n' "${projected:-0}" >>"$GITHUB_OUTPUT"
  printf 'over_budget=%s\n' "$([ "${projected:-0}" -gt "$BUDGET" ] && echo true || echo false)" >>"$GITHUB_OUTPUT"
fi

if [ "${projected:-0}" -gt "$BUDGET" ]; then
  # Not a failure: the workflow opens an issue on the `over_budget` output. A red weekly run would only be noise.
  echo "::warning::projected ${projected} minutes/month is over the ${BUDGET}-minute budget"
fi
