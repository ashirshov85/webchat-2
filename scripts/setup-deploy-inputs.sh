#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NAMESPACE="${NAMESPACE:-dev}"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-github-actions-deployer}"
TOKEN_DURATION="${TOKEN_DURATION:-8760h}"
KUBE_CONTEXT="${KUBE_CONTEXT:-}"
DOCKER_SERVER="${DOCKER_SERVER:-}"
REGISTRY="${REGISTRY:-}"
IMAGE_PREFIX="${IMAGE_PREFIX:-}"
REGISTRY_USERNAME="${REGISTRY_USERNAME:-}"
REGISTRY_PASSWORD="${REGISTRY_PASSWORD:-}"
SKIP_CLUSTER=false
SKIP_GITHUB=false
DRY_RUN=false
REPO_ARG=""

usage() {
  cat <<'EOF'
Usage: scripts/setup-deploy-inputs.sh [owner/repo] [options]

Configures deploy inputs for the automated dev pipeline (task T018, US3, FR-014):

GitHub repository configuration (skipped with --skip-github):
  variables:  REGISTRY, IMAGE_PREFIX
  secrets:    REGISTRY_USERNAME, REGISTRY_PASSWORD, KUBE_CONFIG (base64)

Cluster configuration, one-time and outside git (skipped with --skip-cluster):
  - namespace <dev> (created if missing)
  - ServiceAccount <github-actions-deployer> + Role + RoleBinding
    (RBAC scoped to the dev namespace ONLY: deployments, services, ingresses)
  - restricted kubeconfig for that ServiceAccount -> base64 -> KUBE_CONFIG
  - pull secret `regcred` in the dev namespace (idempotent upsert)

Prerequisites:
  - kubectl installed, current context pointing at the dev cluster
    (or KUBE_CONTEXT=<name> / --kube-context <name> override)
  - gh CLI installed and authenticated with admin access to the repository
  - registry credentials with push permission (for regcred: pull is enough)

Options:
  owner/repo             target repository, overrides origin remote detection
  --registry R           container registry host, e.g. ghcr.io (or env REGISTRY)
  --image-prefix P       image path prefix, e.g. webchat (or env IMAGE_PREFIX)
  --registry-username U  registry login (or env REGISTRY_USERNAME)
  --docker-server S      registry API server for regcred (default: --registry)
  --namespace N          target namespace (default: dev)
  --service-account SA   deployer ServiceAccount name (default: github-actions-deployer)
  --kube-context C       kubectl context for cluster setup
  --token-duration D     ServiceAccount token lifetime (default: 8760h = 1 year)
  --skip-cluster         only configure GitHub (KUBE_CONFIG left untouched)
  --skip-github          only configure the cluster (RBAC + regcred)
  --dry-run              print planned actions without applying (secrets masked)

Security notes:
  - REGISTRY_PASSWORD is read from env or prompted; never passed as a flag.
  - Nothing sensitive is written to git: RBAC/regcred live in the cluster,
    credentials live in GitHub variables/secrets (FR-014, research.md §13).
  - The KUBE_CONFIG token expires after --token-duration; re-run this script
    (or just the KUBE_CONFIG part) to rotate it.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --registry)
    [[ $# -ge 2 ]] || die "--registry requires a value"
    REGISTRY="$2"
    shift 2
    ;;
  --image-prefix)
    [[ $# -ge 2 ]] || die "--image-prefix requires a value"
    IMAGE_PREFIX="$2"
    shift 2
    ;;
  --registry-username)
    [[ $# -ge 2 ]] || die "--registry-username requires a value"
    REGISTRY_USERNAME="$2"
    shift 2
    ;;
  --docker-server)
    [[ $# -ge 2 ]] || die "--docker-server requires a value"
    DOCKER_SERVER="$2"
    shift 2
    ;;
  --namespace)
    [[ $# -ge 2 ]] || die "--namespace requires a value"
    NAMESPACE="$2"
    shift 2
    ;;
  --service-account)
    [[ $# -ge 2 ]] || die "--service-account requires a value"
    SERVICE_ACCOUNT="$2"
    shift 2
    ;;
  --kube-context)
    [[ $# -ge 2 ]] || die "--kube-context requires a value"
    KUBE_CONTEXT="$2"
    shift 2
    ;;
  --token-duration)
    [[ $# -ge 2 ]] || die "--token-duration requires a value"
    TOKEN_DURATION="$2"
    shift 2
    ;;
  --skip-cluster) SKIP_CLUSTER=true && shift ;;
  --skip-github) SKIP_GITHUB=true && shift ;;
  --dry-run) DRY_RUN=true && shift ;;
  -h | --help)
    usage
    exit 0
    ;;
  --*)
    die "unknown option: $1"
    ;;
  *) [[ -z "$REPO_ARG" ]] || die "unexpected extra argument: $1"
    REPO_ARG="$1"
    shift
    ;;
  esac
done

[[ -n "$REGISTRY" ]] || die "registry is required: --registry (or env REGISTRY)"
[[ -n "$IMAGE_PREFIX" ]] || die "image prefix is required: --image-prefix (or env IMAGE_PREFIX)"
[[ -n "$REGISTRY_USERNAME" ]] || die "registry username is required: --registry-username (or env REGISTRY_USERNAME)"
[[ "$SKIP_GITHUB" == true || "$SKIP_CLUSTER" == true ]] || [[ -n "$REGISTRY_PASSWORD" ]] || {
  [[ -t 0 ]] || die "registry password is required: set REGISTRY_PASSWORD env (non-interactive stdin)"
  read -rs -p "Registry password for '${REGISTRY_USERNAME}@${REGISTRY}': " REGISTRY_PASSWORD
  echo >&2
}
[[ -n "$REGISTRY_PASSWORD" ]] || die "registry password must not be empty"
DOCKER_SERVER="${DOCKER_SERVER:-$REGISTRY}"

KCTL=(kubectl)
[[ -z "$KUBE_CONTEXT" ]] || KCTL+=(--context "$KUBE_CONTEXT")

if [[ "$SKIP_CLUSTER" != true && "$DRY_RUN" != true ]]; then
  command -v kubectl >/dev/null 2>&1 || die "kubectl is not installed — see https://kubernetes.io/docs/tasks/tools/"
  "${KCTL[@]}" auth can-i get ns >/dev/null 2>&1 ||
    die "kubectl cannot talk to the cluster (context: '${KUBE_CONTEXT:-current}') — check access or pass --kube-context"
fi

if [[ "$SKIP_GITHUB" != true ]]; then
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

  if [[ "$DRY_RUN" != true ]]; then
    command -v gh >/dev/null 2>&1 || die "gh CLI is not installed — see https://cli.github.com/"
    gh auth status >/dev/null 2>&1 || die "gh is not authenticated — run: gh auth login"
    gh api "repos/${REPO}" --silent >/dev/null || die "cannot access ${REPO} with current gh credentials"
  fi
fi

mask() {
  local value="$1"
  local len=${#value}
  if ((len == 0)); then
    echo "<empty>"
  elif ((len <= 8)); then
    echo "****"
  else
    printf '%s****%s (len=%d)' "${value:0:4}" "${value: -2}" "$len"
  fi
}

echo "Deploy inputs for T018:"
echo "  repository:        ${REPO:-<skipped (--skip-github)>}"
echo "  registry:          ${REGISTRY}"
echo "  image prefix:      ${IMAGE_PREFIX}"
echo "  registry user:     ${REGISTRY_USERNAME}"
echo "  registry password: $(mask "$REGISTRY_PASSWORD")"
echo "  cluster:           $(if [[ "$SKIP_CLUSTER" == true ]]; then echo '<skipped (--skip-cluster)>'; else echo "context '${KUBE_CONTEXT:-current}'"; fi)"
echo "  namespace:         ${NAMESPACE}"
echo

set_variable() {
  local name="$1" value="$2"
  if gh api "repos/${REPO}/actions/variables/${name}" --jq .name >/dev/null 2>&1; then
    gh api -X PATCH "repos/${REPO}/actions/variables/${name}" -f name="$name" -f value="$value" >/dev/null
    echo "  variable ${name}: updated"
  else
    gh api -X POST "repos/${REPO}/actions/variables" -f name="$name" -f value="$value" >/dev/null
    echo "  variable ${name}: created"
  fi
}

KUBE_CONFIG_B64=""

configure_cluster() {
  echo "[cluster] namespace, RBAC and pull secret (one-time, outside git)"

  if ! "${KCTL[@]}" get namespace "$NAMESPACE" >/dev/null 2>&1; then
    echo "  namespace ${NAMESPACE}: creating"
    [[ "$DRY_RUN" == true ]] || "${KCTL[@]}" create namespace "$NAMESPACE"
  else
    echo "  namespace ${NAMESPACE}: exists"
  fi

  echo "  ServiceAccount/${SERVICE_ACCOUNT} + Role + RoleBinding: applying"
  if [[ "$DRY_RUN" != true ]]; then
    "${KCTL[@]}" apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${NAMESPACE}
rules:
  - apiGroups: [apps]
    resources: [deployments]
    verbs: [get, list, watch, create, update, patch]
  - apiGroups: [""]
    resources: [services]
    verbs: [get, list, watch, create, update, patch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses]
    verbs: [get, list, watch, create, update, patch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${NAMESPACE}
subjects:
  - kind: ServiceAccount
    name: ${SERVICE_ACCOUNT}
    namespace: ${NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: ${SERVICE_ACCOUNT}
EOF
  fi

  echo "  pull secret regcred: upserting (docker-server=${DOCKER_SERVER})"
  if [[ "$DRY_RUN" != true ]]; then
    printf '%s' "$REGISTRY_PASSWORD" |
      "${KCTL[@]}" -n "$NAMESPACE" create secret docker-registry regcred \
        --docker-server="$DOCKER_SERVER" \
        --docker-username="$REGISTRY_USERNAME" \
        --docker-password-stdin \
        --dry-run=client -o yaml |
      "${KCTL[@]}" apply -f - >/dev/null
  fi

  echo "  kubeconfig for ServiceAccount: generating (token duration ${TOKEN_DURATION})"
  if [[ "$DRY_RUN" == true ]]; then
    echo "  KUBE_CONFIG: <generated at run time from cluster endpoint + SA token>"
    return
  fi

  local server ca token
  server="$("${KCTL[@]}" config view --minify --raw -o jsonpath='{.clusters[0].cluster.server}')"
  ca="$("${KCTL[@]}" config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"
  [[ -n "$server" ]] || die "cannot read cluster endpoint from kubeconfig"
  [[ -n "$ca" ]] || die "cannot read cluster CA from kubeconfig"
  token="$("${KCTL[@]}" create token "$SERVICE_ACCOUNT" -n "$NAMESPACE" --duration="$TOKEN_DURATION")"
  [[ -n "$token" ]] || die "failed to mint ServiceAccount token (requires kubectl 1.24+ and a live cluster)"

  KUBE_CONFIG_B64="$((
    cat <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: ${NAMESPACE}
    cluster:
      server: ${server}
      certificate-authority-data: ${ca}
users:
  - name: ${SERVICE_ACCOUNT}
    user:
      token: ${token}
contexts:
  - name: ${SERVICE_ACCOUNT}@${NAMESPACE}
    context:
      cluster: ${NAMESPACE}
      user: ${SERVICE_ACCOUNT}
      namespace: ${NAMESPACE}
current-context: ${SERVICE_ACCOUNT}@${NAMESPACE}
EOF
  ) | base64 -w0)"

  KUBECONFIG=/dev/null kubectl --kubeconfig <(printf '%s' "$KUBE_CONFIG_B64" | base64 -d) \
    auth can-i list deployments -n "$NAMESPACE" >/dev/null ||
    die "generated kubeconfig cannot list deployments in ${NAMESPACE} — RBAC setup incomplete"
  KUBECONFIG=/dev/null kubectl --kubeconfig <(printf '%s' "$KUBE_CONFIG_B64" | base64 -d) \
    auth can-i list deployments -n default 2>/dev/null &&
    die "generated kubeconfig must NOT have access outside ${NAMESPACE} — RBAC too broad"
  echo "  kubeconfig: verified (dev-namespace scoped only)"
  echo "  KUBE_CONFIG: $(mask "$KUBE_CONFIG_B64")"
}

configure_github() {
  echo "[github] variables and secrets for ${REPO}"
  if [[ "$DRY_RUN" == true ]]; then
    echo "  variable REGISTRY: ${REGISTRY} (create-or-update)"
    echo "  variable IMAGE_PREFIX: ${IMAGE_PREFIX} (create-or-update)"
    echo "  secret REGISTRY_USERNAME: $(mask "$REGISTRY_USERNAME")"
    echo "  secret REGISTRY_PASSWORD: $(mask "$REGISTRY_PASSWORD")"
    echo "  secret KUBE_CONFIG: $(if [[ "$SKIP_CLUSTER" == true ]]; then echo 'left untouched (--skip-cluster)'; elif [[ -n "$KUBE_CONFIG_B64" ]]; then mask "$KUBE_CONFIG_B64"; else echo "<generated at run time from cluster endpoint + SA token>"; fi)"
    return
  fi

  set_variable REGISTRY "$REGISTRY"
  set_variable IMAGE_PREFIX "$IMAGE_PREFIX"
  gh secret set REGISTRY_USERNAME --body "$REGISTRY_USERNAME" >/dev/null && echo "  secret REGISTRY_USERNAME: set"
  gh secret set REGISTRY_PASSWORD --body "$REGISTRY_PASSWORD" >/dev/null && echo "  secret REGISTRY_PASSWORD: set"

  if [[ "$SKIP_CLUSTER" != true ]]; then
    [[ -n "$KUBE_CONFIG_B64" ]] || die "KUBE_CONFIG is empty — cluster step must run before GitHub step"
    gh secret set KUBE_CONFIG --body "$KUBE_CONFIG_B64" >/dev/null && echo "  secret KUBE_CONFIG: set"
  else
    if ! gh secret list --repo "$REPO" 2>/dev/null | grep -q '^KUBE_CONFIG'; then
      echo "  WARNING: KUBE_CONFIG secret is not set and --skip-cluster was passed — deploy.yml will fail until it exists"
    else
      echo "  secret KUBE_CONFIG: left untouched (--skip-cluster)"
    fi
  fi
}

[[ "$SKIP_CLUSTER" == true ]] || configure_cluster
echo
[[ "$SKIP_GITHUB" == true ]] || configure_github

echo
echo "Done. Zero secrets were written to the repository or manifests (FR-014)."
echo "Next: verify per T019 — merge a green PR into main and watch the deploy run."
