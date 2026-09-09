#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"

REQUIRED_CHECKS=(
  "backend (lint + build + test)"
  "frontend (lint + typecheck + test + build)"
  "gitleaks (secret scanning)"
)

ENFORCE_ADMINS="${ENFORCE_ADMINS:-true}"
REQUIRE_UP_TO_DATE="${REQUIRE_UP_TO_DATE:-false}"

usage() {
  cat <<'EOF'
Usage: scripts/setup-branch-protection.sh [owner/repo] [--dry-run]

Configures branch protection on `main` (task T009, US2, FR-003):
  - direct pushes to main forbidden, changes go through pull requests only
  - required status checks: backend, frontend, gitleaks
  - force pushes and branch deletion forbidden
  - admins included by default (ENFORCE_ADMINS)

Prerequisites:
  - repository pushed to GitHub, `origin` remote configured (or pass owner/repo)
  - gh CLI installed and authenticated with admin access to the repository
  - branch `main` exists on the remote

Options:
  owner/repo    target repository, overrides origin remote detection
  --dry-run     print the API call and payload without applying

Environment overrides:
  ENFORCE_ADMINS=true|false        include administrators (default: true)
  REQUIRE_UP_TO_DATE=true|false    PR branch must be up to date with main (default: false)

Note: required check names must exactly match the job `name:` values in
.github/workflows/ci.yml. When T023 adds the `contract` job, append its
name to REQUIRED_CHECKS above and re-run this script.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

DRY_RUN=false
REPO_ARG=""
for arg in "$@"; do
  case "$arg" in
  --dry-run) DRY_RUN=true ;;
  -h | --help)
    usage
    exit 0
    ;;
  *) REPO_ARG="$arg" ;;
  esac
done

case "$ENFORCE_ADMINS" in true | false) ;; *) die "ENFORCE_ADMINS must be true or false" ;; esac
case "$REQUIRE_UP_TO_DATE" in true | false) ;; *) die "REQUIRE_UP_TO_DATE must be true or false" ;; esac

[[ -f "$CI_WORKFLOW" ]] || die "not found: $CI_WORKFLOW"

for check in "${REQUIRED_CHECKS[@]}"; do
  grep -qF "name: ${check}" "$CI_WORKFLOW" ||
    die "check '${check}' is not a job name in .github/workflows/ci.yml — update REQUIRED_CHECKS to match the workflow"
done

if [[ -n "$REPO_ARG" ]]; then
  REPO="$REPO_ARG"
else
  url="$(git -C "$ROOT" remote get-url origin 2>/dev/null)" || url=""
  if [[ -z "$url" ]]; then
    if [[ "$DRY_RUN" == true ]]; then
      REPO="OWNER/REPO"
    else
      die "no origin remote configured — push the repository to GitHub or pass owner/repo as argument"
    fi
  else
    url="${url%.git}"
    tail="${url##*[:/]}"
    rest="${url%"$tail"}"
    rest="${rest%[:/]}"
    owner="${rest##*[:/]}"
    [[ -n "$owner" && -n "$tail" && "$owner" != *"/"* ]] || die "cannot parse owner/repo from remote: $url"
    REPO="$owner/$tail"
  fi
fi

nl=$'\n'
contexts=""
for check in "${REQUIRED_CHECKS[@]}"; do
  contexts+="    \"${check}\","$'\n'
done
contexts="${contexts%,${nl}}"

PAYLOAD="$(
  cat <<EOF
{
  "required_status_checks": {
    "strict": ${REQUIRE_UP_TO_DATE},
    "contexts": [
${contexts}
    ]
  },
  "enforce_admins": ${ENFORCE_ADMINS},
  "required_pull_request_reviews": {
    "require_code_owner_reviews": false,
    "required_approving_review_count": 0
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_linear_history": false
}
EOF
)"

if [[ "$DRY_RUN" == true ]]; then
  echo "DRY RUN — target: repos/${REPO}/branches/main/protection (PUT)"
  echo
  echo "$PAYLOAD"
  exit 0
fi

command -v gh >/dev/null 2>&1 || die "gh CLI is not installed — see https://cli.github.com/"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated — run: gh auth login"

default_branch="$(gh api "repos/${REPO}" --jq .default_branch)"
[[ "$default_branch" == "main" ]] || echo "WARNING: default branch of ${REPO} is '${default_branch}', expected 'main'"

gh api "repos/${REPO}/branches/main" --silent || die "branch 'main' does not exist on ${REPO}"

gh api -X PUT "repos/${REPO}/branches/main/protection" --input - <<<"$PAYLOAD" >/dev/null

echo "Branch protection applied to ${REPO}:main"
gh api "repos/${REPO}/branches/main/protection" --jq '.required_status_checks.contexts[]' |
  sed 's/^/  required check: /'
echo
echo "Next: verify per T010 — open a PR with a failing unit test, confirm merge is blocked."
