#!/usr/bin/env bash
#
# Files a GitHub issue AND puts it on the project board, as one operation.
#
# Usage:  tools/github/file-issue.sh --title TITLE (--body TEXT | --body-file PATH) [options]
#
#   --status NAME     board column, matched case-insensitively against the board's own
#                     options; a miss lists what is available. Default: Backlog
#   --label NAME      repeatable. Passed through to `gh issue create` unchanged.
#   --project N       project number. Default: $ISSUE_PROJECT_NUMBER, else 6
#   --repo OWNER/NAME default: whatever `gh repo view` resolves in the working directory
#   --dry-run         resolve and validate everything, create nothing
#
# EXIT CODE: 0 only when the issue exists, is on the board, AND reads back carrying the
# Status that was asked for. 2 for a usage or validation error, before anything is created.
# **3 means the issue was created but did not reach the board** -- the number is printed on
# a line of its own, because that combination is the entire failure this script exists to
# prevent and it must never be quiet.
#
# WHY THIS EXISTS
#
# `gh issue create` does not touch the project board. The issue is created, carries its
# labels, and is invisible in the Kanban -- which looks exactly like a ticket nobody filed.
# Measured 2026-08-24: eight issues filed as a scripted batch all reached the board; one
# filed as a one-off a few minutes later did not, and was caught only because someone went
# looking. A batch carries the board step inside its loop. One-offs are where it slips, so
# one-offs are what this is for.
#
# Adding an item and setting a field value are GraphQL-only. REST can list project items
# and field definitions, but the `fields` array it returns on an item carries Title and
# nothing else -- a REST-only check reports every item's Status as unset, which is why the
# read-back at the end is a GraphQL query rather than the cheaper REST one.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
# It does not cache the project, field or option ids. Resolving them by name costs one
# GraphQL query per run, and it means a renamed or reordered column cannot make this write
# a stale id. The ids are the fragile part; the names are what people actually use.
#
# It does not apply triage labels for you. `above-cut` and `backlog` are labels from one
# specific 2026-08-22 triage pass -- they mean "worked autonomously overnight" and "held for
# manual review", not "this is in the Backlog column". Status carries board state. Pass
# --label only for things that are true about the issue itself.
#
# It does not create the project, the Status field, or a missing option. Anything absent is
# an error to report, not to invent.

set -euo pipefail

readonly EXIT_USAGE=2
readonly EXIT_ORPHANED=3

die() {
    printf 'file-issue: %s\n' "$1" >&2
    exit "${2:-$EXIT_USAGE}"
}

title=""
body=""
body_file=""
status="Backlog"
project="${ISSUE_PROJECT_NUMBER:-6}"
repo=""
dry_run=0
labels=()

while [ $# -gt 0 ]; do
    case "$1" in
        --title)     [ $# -ge 2 ] || die "--title needs a value"; title="$2"; shift 2 ;;
        --body)      [ $# -ge 2 ] || die "--body needs a value"; body="$2"; shift 2 ;;
        --body-file) [ $# -ge 2 ] || die "--body-file needs a path"; body_file="$2"; shift 2 ;;
        --status)    [ $# -ge 2 ] || die "--status needs a value"; status="$2"; shift 2 ;;
        --label)     [ $# -ge 2 ] || die "--label needs a value"; labels+=("$2"); shift 2 ;;
        --project)   [ $# -ge 2 ] || die "--project needs a number"; project="$2"; shift 2 ;;
        --repo)      [ $# -ge 2 ] || die "--repo needs OWNER/NAME"; repo="$2"; shift 2 ;;
        --dry-run)   dry_run=1; shift ;;
        -h|--help)   awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
                     exit 0 ;;
        *)           die "unknown argument: $1" ;;
    esac
done

[ -n "$title" ] || die "--title is required"
if [ -n "$body" ] && [ -n "$body_file" ]; then
    die "pass --body or --body-file, not both"
fi
[ -n "$body" ] || [ -n "$body_file" ] || die "one of --body or --body-file is required"
if [ -n "$body_file" ] && [ ! -r "$body_file" ]; then
    die "--body-file is not readable: $body_file"
fi
case "$project" in
    ''|*[!0-9]*) die "--project must be a number, got: $project" ;;
esac

command -v gh >/dev/null 2>&1 || die "gh is not on PATH"

if [ -z "$repo" ]; then
    repo=$(gh repo view --json nameWithOwner --jq '.nameWithOwner') \
        || die "could not resolve the repository; pass --repo OWNER/NAME"
fi
owner="${repo%%/*}"
[ -n "$owner" ] || die "could not read an owner out of: $repo"

# ---------------------------------------------------------------------------
# Resolve the board by NAME. Every id below is read fresh; none is hardcoded.
# ---------------------------------------------------------------------------

# The $names in the query are GraphQL variables, declared by the query and bound by the
# -f flags. Expanding them in the shell would send this shell's idea of $owner to the
# API instead of declaring a parameter -- which is why every query here is single-quoted.
# shellcheck disable=SC2016
board=$(gh api graphql \
    -f query='
        query($owner: String!, $number: Int!) {
          user(login: $owner) {
            projectV2(number: $number) {
              id
              title
              field(name: "Status") {
                ... on ProjectV2SingleSelectField { id options { id name } }
              }
            }
          }
        }' \
    -f owner="$owner" -F number="$project" 2>&1) \
    || die "could not read project $project for $owner. A 403 naming scopes means gh is
missing 'project'; a 403 naming a rate limit is the GraphQL budget, not permissions. The
API said: $board"

project_id=$(printf '%s' "$board" | jq -r '.data.user.projectV2.id // empty')
field_id=$(printf '%s' "$board" | jq -r '.data.user.projectV2.field.id // empty')
project_title=$(printf '%s' "$board" | jq -r '.data.user.projectV2.title // empty')

[ -n "$project_id" ] || die "no project number $project under user $owner"
[ -n "$field_id" ] || die "project $project has no single-select field named 'Status'"

# Case-insensitive match, so "backlog" and "Backlog" both work. The canonical name is
# what gets reported back, so a sloppy argument still produces an exact log line.
option=$(printf '%s' "$board" | jq -r --arg want "$status" '
    .data.user.projectV2.field.options[]
    | select((.name | ascii_downcase) == ($want | ascii_downcase))
    | "\(.id)\t\(.name)"' | head -n 1)

if [ -z "$option" ]; then
    printf 'file-issue: no Status option named %s. Available:\n' "$status" >&2
    printf '%s' "$board" | jq -r '.data.user.projectV2.field.options[] | "  " + .name' >&2
    exit "$EXIT_USAGE"
fi
option_id="${option%%$'\t'*}"
status_canonical="${option#*$'\t'}"

printf 'repo     %s\n' "$repo"
printf 'board    %s (project %s)\n' "$project_title" "$project"
printf 'status   %s\n' "$status_canonical"
printf 'labels   %s\n' "${labels[*]:-(none)}"
printf 'title    %s\n' "$title"

if [ "$dry_run" -eq 1 ]; then
    printf '\ndry run: everything above resolved; nothing was created.\n'
    exit 0
fi

# ---------------------------------------------------------------------------
# Create. Past this line a failure can leave an issue off the board, so every
# error path prints the number.
# ---------------------------------------------------------------------------

create_args=(--repo "$repo" --title "$title")
if [ -n "$body_file" ]; then
    create_args+=(--body-file "$body_file")
else
    create_args+=(--body "$body")
fi
for label in ${labels[@]+"${labels[@]}"}; do
    create_args+=(--label "$label")
done

issue_url=$(gh issue create "${create_args[@]}") || die "gh issue create failed; nothing was filed"
issue_number="${issue_url##*/}"
case "$issue_number" in
    ''|*[!0-9]*) die "could not read an issue number out of: $issue_url" ;;
esac

orphaned() {
    printf 'file-issue: %s\n' "$1" >&2
    printf 'file-issue: THE ISSUE EXISTS BUT IS NOT ON THE BOARD. Fix it by hand:\n' >&2
    printf '%s\n' "$issue_url" >&2
    exit "$EXIT_ORPHANED"
}

content_id=$(gh api "/repos/$repo/issues/$issue_number" --jq '.node_id') \
    || orphaned "could not read the node id for #$issue_number"

# shellcheck disable=SC2016  # GraphQL variables, as above
item_id=$(gh api graphql \
    -f query='
        mutation($project: ID!, $content: ID!) {
          addProjectV2ItemById(input: {projectId: $project, contentId: $content}) {
            item { id }
          }
        }' \
    -f project="$project_id" -f content="$content_id" \
    --jq '.data.addProjectV2ItemById.item.id') \
    || orphaned "could not add #$issue_number to the board"
[ -n "$item_id" ] || orphaned "the board add returned no item id for #$issue_number"

# shellcheck disable=SC2016  # GraphQL variables, as above
gh api graphql \
    -f query='
        mutation($project: ID!, $item: ID!, $field: ID!, $option: String!) {
          updateProjectV2ItemFieldValue(input: {
            projectId: $project, itemId: $item, fieldId: $field,
            value: {singleSelectOptionId: $option}
          }) { projectV2Item { id } }
        }' \
    -f project="$project_id" -f item="$item_id" -f field="$field_id" -f option="$option_id" \
    >/dev/null \
    || orphaned "#$issue_number is on the board but its Status could not be set"

# ---------------------------------------------------------------------------
# Read back. A mutation returning 200 is not evidence the board shows what was
# asked for -- this is the only check that is.
# ---------------------------------------------------------------------------

# shellcheck disable=SC2016  # GraphQL variables, as above
readback=$(gh api graphql \
    -f query='
        query($item: ID!) {
          node(id: $item) {
            ... on ProjectV2Item {
              content { ... on Issue { number } }
              fieldValueByName(name: "Status") {
                ... on ProjectV2ItemFieldSingleSelectValue { name }
              }
            }
          }
        }' \
    -f item="$item_id") \
    || orphaned "#$issue_number was written but could not be read back"

seen_number=$(printf '%s' "$readback" | jq -r '.data.node.content.number // empty')
seen_status=$(printf '%s' "$readback" | jq -r '.data.node.fieldValueByName.name // empty')

if [ "$seen_number" != "$issue_number" ]; then
    orphaned "read-back names issue #${seen_number:-<none>}, expected #$issue_number"
fi
if [ "$seen_status" != "$status_canonical" ]; then
    orphaned "read-back Status is ${seen_status:-<unset>}, expected $status_canonical"
fi

printf '\n#%s on %s as %s -- verified by read-back\n' \
    "$issue_number" "$project_title" "$seen_status"
printf '%s\n' "$issue_url"
