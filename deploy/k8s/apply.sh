#!/usr/bin/env bash
#
# Apply the hrafnagud manifests to a cluster.
#
# Usage:
#   deploy/k8s/apply.sh                          # base, current kubectl context
#   deploy/k8s/apply.sh --overlay mini           # mini overlay
#   deploy/k8s/apply.sh --overlay mini --remote  # kubectl runs on the cluster
#                                                # host over SSH (k3s / Mini)
#   deploy/k8s/apply.sh --no-secret              # leave the Secret alone
#                                                # (GitOps owns it)
#   deploy/k8s/apply.sh --restart                # rollout restart afterwards
#
# --restart exists because the images are published under a floating tag:
# re-applying an unchanged manifest creates no new pod, so nothing pulls the
# new `latest`. A rollout restart does.
#
# Apply order is namespace → Secret → everything else. The other way round
# starts a pod whose env references a Secret that does not exist yet, which
# shows up as CreateContainerConfigError and resolves itself only after a
# retry backoff.

set -euo pipefail

# shellcheck source=../lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/common.sh"

OVERLAY="base"
REMOTE=0
WITH_SECRET=1
RESTART=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --overlay)   OVERLAY="${2:?--overlay needs a name}"; shift 2 ;;
        --remote)    REMOTE=1; shift ;;
        --no-secret) WITH_SECRET=0; shift ;;
        --restart)   RESTART=1; shift ;;
        -h|--help)   sed -n '2,21p' "$0"; exit 0 ;;
        *)           die "unknown argument: $1" ;;
    esac
done

# Loaded before anything else reads SSH_HOST / K8S_NAMESPACE: values from the
# env file must win over the defaults in common.sh, and the remote path has
# no usable fallback for a missing file.
if [[ "${REMOTE}" == "1" ]]; then
    load_deploy_env
    check_ssh_key
else
    load_deploy_env optional
fi

K8S_DIR="${DEPLOY_DIR}/k8s"
if [[ "${OVERLAY}" == "base" ]]; then
    KUSTOMIZE_REL="base"
else
    KUSTOMIZE_REL="overlays/${OVERLAY}"
fi
[ -f "${K8S_DIR}/${KUSTOMIZE_REL}/kustomization.yaml" ] \
    || die "no kustomization at deploy/k8s/${KUSTOMIZE_REL}"

separator
log "apply: overlay=${OVERLAY} namespace=${K8S_NAMESPACE} target=$( [[ ${REMOTE} == 1 ]] && echo "ssh ${SSH_HOST:-?}" || echo "$(kubectl config current-context 2>/dev/null || echo 'no context')")"
separator

SECRET_FILE=""
if [[ "${WITH_SECRET}" == "1" ]]; then
    SECRET_FILE="$("${K8S_DIR}/render-secret.sh")"
fi
# `[ … ] && rm` would return 1 when there is no secret file, and under
# `set -e` a non-zero status from an EXIT trap replaces the script's own.
cleanup() { if [ -n "${SECRET_FILE}" ]; then rm -f "${SECRET_FILE}"; fi; }
trap cleanup EXIT

if [[ "${REMOTE}" == "1" ]]; then
    remote_dir="/tmp/hrafnagud-k8s-$$"
    remote_ssh "mkdir -p ${remote_dir}"
    remote_scp -q -r "${K8S_DIR}/." "$(remote_target):${remote_dir}/"
    if [[ -n "${SECRET_FILE}" ]]; then
        remote_scp -q "${SECRET_FILE}" "$(remote_target):${remote_dir}/secret.yaml"
    fi
    restart_cmd=""
    if [[ "${RESTART}" == "1" ]]; then
        restart_cmd="kubectl rollout restart deployment/hrafnagud -n ${K8S_NAMESPACE}"
    fi
    remote_ssh "set -e
        kubectl apply -f ${remote_dir}/base/namespace.yaml
        if [ -f ${remote_dir}/secret.yaml ]; then kubectl apply -f ${remote_dir}/secret.yaml; fi
        kubectl apply -k ${remote_dir}/${KUSTOMIZE_REL}
        ${restart_cmd}
        rm -rf ${remote_dir}"
else
    kubectl apply -f "${K8S_DIR}/base/namespace.yaml"
    if [[ -n "${SECRET_FILE}" ]]; then kubectl apply -f "${SECRET_FILE}"; fi
    kubectl apply -k "${K8S_DIR}/${KUSTOMIZE_REL}"
    if [[ "${RESTART}" == "1" ]]; then
        kubectl rollout restart "deployment/hrafnagud" -n "${K8S_NAMESPACE}"
    fi
fi

ok "applied"
