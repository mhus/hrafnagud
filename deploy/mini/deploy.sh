#!/usr/bin/env bash
#
# Full deploy to the Mac-Mini k3s node, and the day-to-day commands around
# it. Everything here composes the scripts one level up; nothing is
# implemented twice.
#
# Usage:
#   deploy/mini/deploy.sh              # = all
#   deploy/mini/deploy.sh all          # build both arches → push → apply → restart → caddy
#   deploy/mini/deploy.sh build        # both arches only
#   deploy/mini/deploy.sh push         # multi-arch manifest only
#   deploy/mini/deploy.sh apply        # manifests + secret, then rollout restart
#   deploy/mini/deploy.sh restart      # rollout restart only
#   deploy/mini/deploy.sh status       # pods, services, deployment
#   deploy/mini/deploy.sh logs [n]     # last n log lines (default 100)
#   deploy/mini/deploy.sh forward [p]  # tunnel the API to localhost:p (default 9800)
#   deploy/mini/deploy.sh caddy        # (re-)install the public Caddy route
#
# The restart in `apply` is not redundant: images are published under the
# floating :latest tag, and re-applying an unchanged manifest gives the
# kubelet no reason to pull anything.

set -euo pipefail

# shellcheck source=../lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/common.sh"

CMD="${1:-all}"; shift || true

cmd_build() {
    # The first build runs Maven; the second reuses that JAR. Building it
    # twice would be pure waste, and worse, a source change landing between
    # the two would put different code in the two architectures of one
    # manifest.
    "${DEPLOY_DIR}/docker/build-image.sh" --amd64
    SKIP_MAVEN=1 "${DEPLOY_DIR}/docker/build-image.sh" --arm64
}

cmd_push() {
    "${DEPLOY_DIR}/docker/push-image.sh" --multi-arch
}

cmd_apply() {
    "${DEPLOY_DIR}/k8s/apply.sh" --overlay mini --remote --restart
}

cmd_restart() {
    load_deploy_env; check_ssh_key
    remote_ssh "kubectl rollout restart deployment/hrafnagud -n ${K8S_NAMESPACE}"
    ok "rollout restarted"
}

cmd_status() {
    load_deploy_env; check_ssh_key
    remote_ssh "kubectl get pods,svc,deploy -n ${K8S_NAMESPACE} -o wide"
}

cmd_logs() {
    load_deploy_env; check_ssh_key
    local tail="${1:-100}"
    remote_ssh "kubectl logs -n ${K8S_NAMESPACE} -l app=hrafnagud --tail=${tail}"
}

cmd_forward() {
    load_deploy_env; check_ssh_key
    local local_port="${1:-9800}"
    local node_port="${HRAFNAGUD_NODE_PORT:-30980}"
    log "http://localhost:${local_port} → ${SSH_HOST}:${node_port} (Ctrl-C to stop)"
    # An SSH tunnel rather than `kubectl port-forward`: kubectl would have to
    # run on the host anyway, and this way the unauthenticated API is reachable
    # from exactly one machine for exactly as long as this stays open.
    ssh -i "${SSH_KEY}" -N \
        -L "${local_port}:127.0.0.1:${node_port}" \
        "$(remote_target)"
}

cmd_caddy() {
    load_deploy_env
    if [ -z "${HRAFNAGUD_PUBLIC_HOST:-}" ]; then
        log "HRAFNAGUD_PUBLIC_HOST is empty — skipping the public route"
        return 0
    fi
    "${DEPLOY_DIR}/mini/install-caddy.sh"
}

case "${CMD}" in
    all)
        separator; log "deploy: build → push → apply → caddy"; separator
        cmd_build
        cmd_push
        cmd_apply
        cmd_caddy
        cmd_status
        ok "deployed"
        ;;
    build)   cmd_build ;;
    push)    cmd_push ;;
    apply)   cmd_apply ;;
    restart) cmd_restart ;;
    status)  cmd_status ;;
    logs)    cmd_logs "$@" ;;
    forward) cmd_forward "$@" ;;
    caddy)   cmd_caddy ;;
    -h|--help) sed -n '2,21p' "$0" ;;
    *)       die "unknown command: ${CMD} (try --help)" ;;
esac
