#!/usr/bin/env bash
#
# Build the hrafnagud Docker image.
#
# Runs the Maven build first (unless SKIP_MAVEN=1) and then `docker build`
# with the repository root as context, because the Dockerfile copies the JAR
# from target/.
#
# Usage:
#   deploy/docker/build-image.sh                     # :latest, host arch
#   IMAGE_TAG=0.1.0 deploy/docker/build-image.sh     # explicit tag
#   SKIP_MAVEN=1 deploy/docker/build-image.sh        # trust the existing JAR
#   deploy/docker/build-image.sh --amd64             # cross-build via buildx
#   deploy/docker/build-image.sh --arm64
#
# The per-arch flags tag :latest-amd64 / :latest-arm64 (or
# :<IMAGE_TAG>-<arch> when IMAGE_TAG is set), which is what push-image.sh
# combines into a multi-arch manifest.

set -euo pipefail

# shellcheck source=../lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/common.sh"

PLATFORM=""
ARCH_SUFFIX=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --amd64) PLATFORM="linux/amd64"; ARCH_SUFFIX="-amd64"; shift ;;
        --arm64) PLATFORM="linux/arm64"; ARCH_SUFFIX="-arm64"; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

IMAGE_TAG="${IMAGE_TAG:-latest}${ARCH_SUFFIX}"
SKIP_MAVEN="${SKIP_MAVEN:-0}"
JAR="${REPO_ROOT}/target/hrafnagud.jar"

cd "${REPO_ROOT}"

if [[ "${SKIP_MAVEN}" != "1" ]]; then
    log "mvn install (set SKIP_MAVEN=1 to skip)"
    # install, not package: a sibling checkout may want the artifact out of
    # the local repository.
    mvn install
fi

[ -f "${JAR}" ] || die "missing ${JAR#"${REPO_ROOT}/"} — run without SKIP_MAVEN=1"

log "building ${IMAGE_NAME}:${IMAGE_TAG}${PLATFORM:+ for ${PLATFORM}} ($(du -h "${JAR}" | cut -f1) jar)"

if [[ -n "${PLATFORM}" ]]; then
    # --load puts the cross-built image into the local image store so the
    # push script can tag it. Needs a buildx builder with qemu — see
    # deploy/README.md if this fails with "exec format error".
    docker buildx build \
        --platform "${PLATFORM}" \
        --file deploy/docker/Dockerfile \
        --build-arg APT_MIRROR="${APT_MIRROR:-}" \
        --build-arg APT_PORTS_MIRROR="${APT_PORTS_MIRROR:-}" \
        --tag "${IMAGE_NAME}:${IMAGE_TAG}" \
        --load \
        .
else
    docker build \
        --file deploy/docker/Dockerfile \
        --build-arg APT_MIRROR="${APT_MIRROR:-}" \
        --build-arg APT_PORTS_MIRROR="${APT_PORTS_MIRROR:-}" \
        --tag "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
fi

ok "${IMAGE_NAME}:${IMAGE_TAG}"
