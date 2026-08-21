# shellcheck shell=bash
#
# Shared helpers for the deploy scripts. Sourced, never executed directly.
#
# Defaults live here so that build, push and the k8s scripts cannot drift
# apart on image name, registry or namespace.

# ── paths ───────────────────────────────────────────────────────────────
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${LIB_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${DEPLOY_DIR}/.." && pwd)"

# ── image coordinates ───────────────────────────────────────────────────
# Local tag is the bare name; the remote reference is
# ${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${tag}.
IMAGE_NAME="${IMAGE_NAME:-hrafnagud}"
REGISTRY="${REGISTRY:-docker.io}"
NAMESPACE="${NAMESPACE:-mhus}"

# ── kubernetes ──────────────────────────────────────────────────────────
K8S_NAMESPACE="${K8S_NAMESPACE:-hrafnagud}"

# ── output ──────────────────────────────────────────────────────────────
_c_reset=$'\033[0m'; _c_red=$'\033[1;31m'; _c_green=$'\033[1;32m'
_c_yellow=$'\033[1;33m'; _c_blue=$'\033[1;34m'

# All of these go to stderr, including the non-error ones: render-secret.sh
# prints the path of the rendered file on stdout, and progress chatter mixed
# into that would be substituted into a kubectl command line.
log()  { echo "${_c_blue}▸${_c_reset} $*" >&2; }
ok()   { echo "${_c_green}✓${_c_reset} $*" >&2; }
warn() { echo "${_c_yellow}⚠${_c_reset} $*" >&2; }
die()  { echo "${_c_red}✗${_c_reset} $*" >&2; exit 1; }

separator() { echo "────────────────────────────────────────────────────────────" >&2; }

# ── deploy environment ──────────────────────────────────────────────────
#
# Everything secret (registry token, Mongo URI, API keys, SSH target) comes
# from one env file. It is looked up in this order, first hit wins:
#
#   1. $HRAFNAGUD_DEPLOY_ENV                    explicit override
#   2. <repo>/deploy/secrets.env                standalone checkout
#   3. <workbench>/confidential/hrafnagud/secrets.env
#                                               vance-wb layout, where
#                                               confidential/ is the place
#                                               local secrets already live
#   4. ~/.hrafnagud/deploy.env                  per-user fallback
#
# Passing `optional` makes a missing file a no-op instead of an error — the
# build script needs no secrets at all, only push and deploy do.
load_deploy_env() {
    local mode="${1:-required}"
    local candidates=(
        "${HRAFNAGUD_DEPLOY_ENV:-}"
        "${REPO_ROOT}/deploy/secrets.env"
        "${REPO_ROOT}/../../confidential/hrafnagud/secrets.env"
        "${HOME}/.hrafnagud/deploy.env"
    )
    local f
    for f in "${candidates[@]}"; do
        [ -n "$f" ] || continue
        [ -f "$f" ] || continue
        # shellcheck disable=SC1090
        set -a; . "$f"; set +a
        DEPLOY_ENV_FILE="$f"
        log "deploy env: ${f}"
        return 0
    done
    if [ "$mode" = "optional" ]; then
        return 0
    fi
    warn "no deploy env file found. Looked at:"
    for f in "${candidates[@]}"; do [ -n "$f" ] && echo "    $f" >&2; done
    die "copy deploy/secrets.env.example to one of them and fill it in"
}

# Fails with the variable's name rather than with whatever downstream error
# an empty value would eventually cause.
require_var() {
    local name="$1"
    [ -n "${!name:-}" ] || die "${name} is required (set it in ${DEPLOY_ENV_FILE:-the deploy env file})"
}

# ── remote cluster access ───────────────────────────────────────────────
#
# "Remote" here means: kubectl runs on the cluster host over SSH, rather
# than locally against a kubeconfig. That is how the Mini (k3s) is reached —
# and it keeps the rendered Secret off the local disk of anything but a temp
# file, since it is scp'd to /tmp on the host and deleted after the apply.
#
# Set SSH_HOST / SSH_USER / SSH_KEY in the deploy env file. A cluster you
# reach with a normal kubeconfig does not need any of this — leave --remote
# off and apply.sh talks to the current kubectl context.
remote_ssh() {
    require_var SSH_HOST
    ssh -i "${SSH_KEY}" \
        -o StrictHostKeyChecking=accept-new \
        -o ConnectTimeout=10 \
        -o ServerAliveInterval=15 \
        -o ControlMaster=auto \
        -o ControlPath="/tmp/.hrafnagud-ssh-%r@%h:%p" \
        -o ControlPersist=60 \
        "${SSH_USER:-root}@${SSH_HOST}" "$@"
}

remote_scp() {
    require_var SSH_HOST
    scp -i "${SSH_KEY}" \
        -o StrictHostKeyChecking=accept-new \
        -o ConnectTimeout=10 \
        -o ControlMaster=auto \
        -o ControlPath="/tmp/.hrafnagud-ssh-%r@%h:%p" \
        -o ControlPersist=60 \
        "$@"
}

remote_target() { echo "${SSH_USER:-root}@${SSH_HOST}"; }

check_ssh_key() {
    require_var SSH_HOST
    require_var SSH_KEY
    [ -f "${SSH_KEY}" ] || die "SSH key not found: ${SSH_KEY}"
    # ssh refuses group/world-readable keys, and the resulting error names
    # permissions rather than the deploy step that hit them.
    local mode
    mode="$(stat -f '%Lp' "${SSH_KEY}" 2>/dev/null || stat -c '%a' "${SSH_KEY}")"
    [ "${mode}" = "600" ] || warn "${SSH_KEY} has mode ${mode}; ssh wants 600"
}

# ── misc ────────────────────────────────────────────────────────────────
# Project version straight from the POM, without invoking Maven
# (which would cost seconds and needs a working settings.xml). Used for
# version-tagged pushes.
project_version() {
    sed -n 's|^[[:space:]]*<version>\(.*\)</version>.*|\1|p' \
        "${REPO_ROOT}/pom.xml" | sed -n 2p
}
