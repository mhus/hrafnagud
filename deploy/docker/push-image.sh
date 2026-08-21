#!/usr/bin/env bash
#
# Push the hrafnagud image to a registry.
#
# Default target is docker.io/mhus/hrafnagud (override with REGISTRY /
# NAMESPACE / IMAGE_NAME, e.g. REGISTRY=ghcr.io NAMESPACE=mhus).
#
# Usage:
#   deploy/docker/push-image.sh                  # host-arch image → :latest
#   deploy/docker/push-image.sh --multi-arch     # combine the two per-arch
#                                                # builds into one manifest
#   deploy/docker/push-image.sh --version        # additionally tag with the
#                                                # POM version
#   TAG=0.1.0 deploy/docker/push-image.sh        # explicit remote tag
#
# --multi-arch expects both local per-arch tags to exist:
#   deploy/docker/build-image.sh --amd64
#   deploy/docker/build-image.sh --arm64
#
# Credentials come from the deploy env file (REGISTRY_USER /
# REGISTRY_TOKEN). If they are empty the ambient `docker login` session is
# used instead, which is the normal case on a workstation.

set -euo pipefail

# shellcheck source=../lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/common.sh"

MULTI_ARCH=0
WITH_VERSION=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --multi-arch) MULTI_ARCH=1; shift ;;
        --version)    WITH_VERSION=1; shift ;;
        -h|--help)    sed -n '2,22p' "$0"; exit 0 ;;
        *)            die "unknown argument: $1" ;;
    esac
done

load_deploy_env optional

TAG="${TAG:-latest}"
REMOTE_BASE="${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}"

# Remote tags this run publishes. The version tag is opt-in because pushing
# a moving version tag (0.1.0-SNAPSHOT) on every build makes it useless as a
# record of what ran.
TAGS=("${TAG}")
if [[ "${WITH_VERSION}" == "1" ]]; then
    version="$(project_version)"
    [ -n "${version}" ] || die "could not read the version from pom.xml"
    TAGS+=("${version}")
fi

separator
log "push → ${REMOTE_BASE} [$(IFS=,; echo "${TAGS[*]}")]$( [[ ${MULTI_ARCH} == 1 ]] && echo ' (multi-arch)')"
separator

# Verify locally before touching the network: a half-pushed manifest is
# tedious to clean up, and a missing per-arch tag otherwise surfaces only
# after the first arch is already uploaded.
if [[ "${MULTI_ARCH}" == "1" ]]; then
    for arch in amd64 arm64; do
        docker image inspect "${IMAGE_NAME}:latest-${arch}" >/dev/null 2>&1 \
            || die "${IMAGE_NAME}:latest-${arch} is missing — build it with 'deploy/docker/build-image.sh --${arch}'"
    done
else
    docker image inspect "${IMAGE_NAME}:latest" >/dev/null 2>&1 \
        || die "${IMAGE_NAME}:latest is missing — build it with 'deploy/docker/build-image.sh'"
fi

if [[ -n "${REGISTRY_USER:-}" && -n "${REGISTRY_TOKEN:-}" ]]; then
    echo "${REGISTRY_TOKEN}" | docker login "${REGISTRY}" \
        --username "${REGISTRY_USER}" --password-stdin
else
    warn "REGISTRY_USER/REGISTRY_TOKEN empty — relying on the existing 'docker login' session"
fi

for tag in "${TAGS[@]}"; do
    if [[ "${MULTI_ARCH}" == "1" ]]; then
        amd64_remote="${REMOTE_BASE}:${tag}-amd64"
        arm64_remote="${REMOTE_BASE}:${tag}-arm64"

        log "${IMAGE_NAME}:latest-amd64 → ${amd64_remote}"
        docker tag "${IMAGE_NAME}:latest-amd64" "${amd64_remote}"
        docker push "${amd64_remote}"

        log "${IMAGE_NAME}:latest-arm64 → ${arm64_remote}"
        docker tag "${IMAGE_NAME}:latest-arm64" "${arm64_remote}"
        docker push "${arm64_remote}"

        # imagetools, not `docker manifest create`: buildx --load images
        # carry provenance attestations, which already makes each per-arch
        # push a manifest list, and `docker manifest create` refuses to
        # nest one ("is a manifest list"). imagetools merges them.
        log "manifest ${REMOTE_BASE}:${tag}"
        docker buildx imagetools create -t "${REMOTE_BASE}:${tag}" \
            "${amd64_remote}" "${arm64_remote}"
    else
        log "${IMAGE_NAME}:latest → ${REMOTE_BASE}:${tag}"
        docker tag "${IMAGE_NAME}:latest" "${REMOTE_BASE}:${tag}"
        docker push "${REMOTE_BASE}:${tag}"
    fi
done

ok "pushed — docker pull ${REMOTE_BASE}:${TAGS[0]}"
