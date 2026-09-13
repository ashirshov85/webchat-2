#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Auto-load tokens from repo-local .env.local (git-ignored), if present
if [ -z "${SONAR_TOKEN:-}" ] && [ -f "$ROOT/.env.local" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env.local"
  set +a
fi
SONAR_TOKEN="${SONAR_TOKEN:-${SONAR_ISSUES_TOKEN:-}}"

SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
PROJECT="all"
SEVERITIES=""
BRANCH=""
OUT=""
FAIL_IF_FOUND=false

usage() {
  cat <<'EOF'
Usage: scripts/sonar-issues.sh [options]

Fetches unresolved SonarQube issues via Web API and renders them as a markdown
task list, ready to be handed to an agent (or a human) for fixing.

Options:
  --project backend|frontend|all   Project to fetch (default: all)
  --severity LIST                  Comma-separated severities, e.g. BLOCKER,CRITICAL
  --branch NAME                    Branch to fetch (default: main branch of project)
  --out FILE                       Write markdown to FILE (default: <repo>/tmp/sonar-issues.md)
  --stdout                         Print markdown to stdout instead of a file
  --fail-if-found                  Exit 1 if any issue found (CI gating)
  -h, --help                       Show this help

Environment:
  SONAR_HOST_URL  SonarQube base URL (default: http://localhost:9000)
  SONAR_TOKEN     Token; if unset, auto-loaded from <repo>/.env.local
                  (SONAR_TOKEN, falls back to SONAR_ISSUES_TOKEN)

Prerequisites:
  - SonarQube is running (docker compose -f deploy/local/docker-compose.yml up -d sonarqube)
  - Projects webchat-backend / webchat-frontend have been scanned at least once
  - curl and python3 available

Examples:
  scripts/sonar-issues.sh
  scripts/sonar-issues.sh --project backend --severity BLOCKER,CRITICAL
  scripts/sonar-issues.sh --fail-if-found

Hand the report to the agent:
  scripts/sonar-issues.sh
  opencode "Fix the SonarQube issues listed in tmp/sonar-issues.md. Do not change public API or test expectations."
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="${2:?}"; shift 2 ;;
    --severity) SEVERITIES="${2:?}"; shift 2 ;;
    --branch) BRANCH="${2:?}"; shift 2 ;;
    --out) OUT="${2:?}"; shift 2 ;;
    --stdout) OUT="-"; shift ;;
    --fail-if-found) FAIL_IF_FOUND=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$PROJECT" in
  backend) PROJECT_KEYS="webchat-backend" ;;
  frontend) PROJECT_KEYS="webchat-frontend" ;;
  all) PROJECT_KEYS="webchat-backend,webchat-frontend" ;;
  *) echo "Invalid --project: $PROJECT (expected backend|frontend|all)" >&2; exit 2 ;;
esac

AUTH=()
if [ -n "$SONAR_TOKEN" ]; then
  AUTH=(-u "$SONAR_TOKEN:")
fi

status="$(curl -sf "${AUTH[@]}" "$SONAR_HOST_URL/api/system/status" 2>/dev/null || true)"
if ! grep -q '"status":"UP"' <<<"$status"; then
  echo "SonarQube at $SONAR_HOST_URL is not reachable or not UP." >&2
  echo "Start it: docker compose -f $ROOT/deploy/local/docker-compose.yml up -d sonarqube" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

page=1
while :; do
  url="$SONAR_HOST_URL/api/issues/search?projectKeys=$PROJECT_KEYS&resolved=false&ps=500&p=$page"
  [ -n "$SEVERITIES" ] && url="$url&severities=$SEVERITIES"
  [ -n "$BRANCH" ] && url="$url&branch=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$BRANCH")"
  if ! curl -sf "${AUTH[@]}" -o "$TMP/page-$page.json" "$url"; then
    echo "Failed to fetch issues (page $page). Check SONAR_TOKEN / project keys [$PROJECT_KEYS]." >&2
    exit 1
  fi
  total="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["total"])' "$TMP/page-$page.json")"
  pages=$(( (total + 499) / 500 ))
  [ "$page" -ge "$pages" ] && break
  page=$((page + 1))
done

render() {
  python3 - "$TMP" "$PROJECT" <<'PYEOF'
import glob
import json
import sys

tmp, scope = sys.argv[1], sys.argv[2]
sev_rank = {"BLOCKER": 0, "CRITICAL": 1, "MAJOR": 2, "MINOR": 3, "INFO": 4}

issues = []
for f in sorted(glob.glob(f"{tmp}/page-*.json")):
    issues.extend(json.load(open(f))["issues"])

if not issues:
    print("No unresolved SonarQube issues" + (f" ({scope})" if scope != "all" else "") + ".")
    sys.exit(0)

by_project = {}
for i in issues:
    project, _, path = i["component"].partition(":")
    line = i.get("textRange", {}).get("startLine", "?")
    by_project.setdefault(project, {}).setdefault(path, []).append((i, line))

for project in sorted(by_project):
    items = [x for v in by_project[project].values() for x in v]
    print(f"## {project} — {len(items)} issue(s)\n")
    fileless = by_project[project].pop("", [])
    for i, line in sorted(fileless, key=lambda x: x[0]["rule"]):
        print(f"- `[{i['severity']}]` {i['message']} (rule: `{i['rule']}`, project-level)")
    if fileless:
        print()
    for path in sorted(by_project[project]):
        entries = by_project[project][path]
        entries.sort(key=lambda x: (sev_rank.get(x[0]["severity"], 9), x[1]))
        print(f"### {path}\n")
        for i, line in entries:
            msg = i["message"].replace("\n", " ")
            print(f"- `[{i['severity']}]` line {line}: {msg} (rule: `{i['rule']}`)")
        print()

total_by_type = {}
for i in issues:
    total_by_type[i["type"]] = total_by_type.get(i["type"], 0) + 1
summary = ", ".join(f"{v} {k}" for k, v in sorted(total_by_type.items()))
print(f"---\nTotal: {len(issues)} ({summary})")
PYEOF
}

if [ "$OUT" = "-" ]; then
  render
else
  OUT="${OUT:-$ROOT/tmp/sonar-issues.md}"
  mkdir -p "$(dirname "$OUT")"
  render >"$OUT"
  echo "Report written to $OUT ($(grep -c '^- ' "$OUT" | awk '{print $1}') issues)." >&2
fi

if [ "$FAIL_IF_FOUND" = true ] && [ "$total" -gt 0 ]; then
  echo "Quality gate: $total unresolved issue(s) found." >&2
  exit 1
fi
