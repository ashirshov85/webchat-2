#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Keep in sync with the contract job in .github/workflows/ci.yml
VACUUM_VERSION="v0.30.3"
OASDIFF_VERSION="v1.31.0"
BASE_REF="origin/main"
NO_INSTALL=false

usage() {
  cat <<'EOF'
Usage: scripts/validate-contracts.sh [options]

Local mirror of the CI contract job (.github/workflows/ci.yml, "contract"):
  1. vacuum lint -e contracts/openapi.yaml
  2. Regenerate TS types: pnpm --dir frontend generate:api
  3. Drift check: git diff --exit-code -- frontend/src/api/schema.d.ts
  4. oasdiff breaking --fail-on ERR <base from origin/main> contracts/openapi.yaml
     - contracts/oasdiff-err-ignore.txt is passed only when the file exists
     - breaking check is skipped when contracts/BREAKING.md exists (intentional
       breaking changes, FR-010)
     - skipped when contracts/openapi.yaml is absent on the base ref
       (first introduction)

Options:
  --base-ref REF   Base ref for breaking check (default: origin/main)
  --no-install     Skip `pnpm --dir frontend install --frozen-lockfile`
                   (default: install only when frontend/node_modules is missing)
  -h, --help       Show this help

Prerequisites:
  - vacuum / oasdiff on PATH; if missing, downloaded automatically
    (pinned versions, see above) into <repo>/tmp/bin (git-ignored)
  - pnpm, node (see frontend/package.json / frontend/.nvmrc)

Examples:
  scripts/validate-contracts.sh
  scripts/validate-contracts.sh --base-ref origin/main --no-install
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base-ref) BASE_REF="${2:?}"; shift 2 ;;
    --no-install) NO_INSTALL=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

cd "$ROOT"

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$OS:$ARCH" in
  linux:x86_64|darwin:x86_64) VAC_ARCH=x86_64; OAS_ARCH=amd64 ;;
  linux:aarch64|linux:arm64|darwin:arm64) VAC_ARCH=arm64; OAS_ARCH=arm64 ;;
  *) echo "Unsupported platform for tool download: $OS $ARCH (install vacuum/oasdiff on PATH manually)" >&2; exit 1 ;;
esac

ensure_tool() {
  local name="$1" version="$2" asset_arch="$3"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi
  local bin_dir="$ROOT/tmp/bin"
  local bin="$bin_dir/$name"
  if [ ! -x "$bin" ]; then
    local ver="${version#v}" tarball url
    case "$name" in
      vacuum) tarball="vacuum_${ver}_${OS}_${asset_arch}.tar.gz"
              url="https://github.com/daveshanley/vacuum/releases/download/${version}/${tarball}" ;;
      oasdiff) tarball="oasdiff_${ver}_${OS}_${asset_arch}.tar.gz"
               url="https://github.com/oasdiff/oasdiff/releases/download/${version}/${tarball}" ;;
    esac
    mkdir -p "$bin_dir"
    echo "Downloading $name $version -> $bin (one-time, cached in tmp/bin)" >&2
    curl -sSL --fail -o "$bin_dir/$tarball" "$url"
    tar -xzf "$bin_dir/$tarball" -C "$bin_dir" "$name"
    rm -f "$bin_dir/$tarball"
    chmod +x "$bin"
  fi
  echo "$bin"
}

step() { printf '\n=== %s ===\n' "$*" >&2; }

step "Lint OpenAPI contract (vacuum lint -e contracts/openapi.yaml)"
VACUUM="$(ensure_tool vacuum "$VACUUM_VERSION" "$VAC_ARCH")"
"$VACUUM" lint -e contracts/openapi.yaml

step "Regenerate TypeScript types (pnpm --dir frontend generate:api)"
if [ "$NO_INSTALL" != true ] && [ ! -d frontend/node_modules ]; then
  pnpm --dir frontend install --frozen-lockfile
fi
pnpm --dir frontend generate:api

step "Drift check (committed schema.d.ts must match contract)"
if ! git diff --exit-code -- frontend/src/api/schema.d.ts; then
  echo >&2
  echo "Drift detected: frontend/src/api/schema.d.ts does not match contracts/openapi.yaml." >&2
  echo "Regenerated file is left in the working tree: commit it (and only it)." >&2
  exit 1
fi
echo "No drift: frontend/src/api/schema.d.ts matches the contract." >&2

if [ -f contracts/BREAKING.md ]; then
  step "Breaking change detection (oasdiff)"
  echo "contracts/BREAKING.md present — breaking check skipped (intentional, FR-010)" >&2
  exit 0
fi

if ! git cat-file -e "$BASE_REF:contracts/openapi.yaml" 2>/dev/null; then
  step "Breaking change detection (oasdiff)"
  echo "contracts/openapi.yaml not on $BASE_REF yet — first introduction, nothing to compare" >&2
  exit 0
fi

step "Breaking change detection (oasdiff breaking --fail-on ERR vs $BASE_REF)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git show "$BASE_REF:contracts/openapi.yaml" >"$TMP/openapi-base.yaml"
OASDIFF="$(ensure_tool oasdiff "$OASDIFF_VERSION" "$OAS_ARCH")"
ARGS=()
if [ -f contracts/oasdiff-err-ignore.txt ]; then
  ARGS+=(--err-ignore contracts/oasdiff-err-ignore.txt)
fi
"$OASDIFF" breaking --fail-on ERR "${ARGS[@]}" "$TMP/openapi-base.yaml" contracts/openapi.yaml

step "OK: lint + drift + breaking all green"
