#!/usr/bin/env bash
#
# Write the hrafnagud block into the Caddyfile on the cluster host and
# reload Caddy.
#
# Opt-in: without HRAFNAGUD_PUBLIC_HOST in the deploy env file this is a
# no-op, because a hostname is the one thing that cannot be defaulted — and
# a route pointing at a host nobody resolves is worse than no route.
#
# Usage:
#   deploy/mini/install-caddy.sh            # install / update the block
#   deploy/mini/install-caddy.sh --show     # print the rendered block
#   deploy/mini/install-caddy.sh --remove   # drop the block again

set -euo pipefail

# shellcheck source=../lib/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/common.sh"

MODE="install"
case "${1:-}" in
    --show)   MODE="show" ;;
    --remove) MODE="remove" ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    "")       ;;
    *)        die "unknown argument: $1" ;;
esac

load_deploy_env

CADDYFILE="${CADDYFILE_PATH:-/mnt/data/caddy/Caddyfile}"
MARKER_BEGIN="# >>> hrafnagud >>>"
MARKER_END="# <<< hrafnagud <<<"
NODE_PORT="${HRAFNAGUD_NODE_PORT:-30980}"

render() {
    require_var HRAFNAGUD_PUBLIC_HOST
    local template="${DEPLOY_DIR}/mini/caddy/hrafnagud.caddy"
    local content
    content="$(cat "${template}")"
    # Bash parameter expansion rather than sed: a hostname is harmless, but
    # keeping every substitution in this file on one mechanism means none of
    # them can be broken by a delimiter or an ampersand later.
    content="${content//__PUBLIC_HOST__/${HRAFNAGUD_PUBLIC_HOST}}"
    content="${content//__NODE_PORT__/${NODE_PORT}}"
    printf '%s\n' "${content}"
}

if [[ "${MODE}" == "show" ]]; then
    render
    exit 0
fi

check_ssh_key

remote_snippet="/tmp/hrafnagud-caddy-$$.caddy"
if [[ "${MODE}" == "install" ]]; then
    render | remote_ssh "cat > ${remote_snippet}"
else
    remote_ssh ": > ${remote_snippet}"
fi

# The Caddyfile is bind-mounted into the caddy container as a single file.
# Anything that swaps the inode (sed -i, mv, cp -f) breaks that mount and the
# container keeps serving the old content until it is restarted — so the file
# is rewritten in place with `cat >`, which truncates and refills the same
# inode. The markers are compared with string equality, not as regexes,
# because they contain characters awk would otherwise interpret.
remote_ssh "set -e
    tmpfile=\$(mktemp)
    BEGIN_MARK='${MARKER_BEGIN}' END_MARK='${MARKER_END}' awk '
        \$0 == ENVIRON[\"BEGIN_MARK\"] { skip=1; next }
        \$0 == ENVIRON[\"END_MARK\"]   { skip=0; next }
        skip != 1                      { print }
    ' ${CADDYFILE} > \$tmpfile
    if [ -s ${remote_snippet} ]; then
        {
            echo ''
            echo '${MARKER_BEGIN}'
            cat ${remote_snippet}
            echo '${MARKER_END}'
        } >> \$tmpfile
    fi
    cat \$tmpfile > ${CADDYFILE}
    rm -f \$tmpfile ${remote_snippet}
    docker exec caddy caddy reload --config /etc/caddy/Caddyfile 2>/dev/null \
        || docker restart caddy"

if [[ "${MODE}" == "install" ]]; then
    ok "Caddy: ${HRAFNAGUD_PUBLIC_HOST} → 127.0.0.1:${NODE_PORT} (/ode/feed only)"
else
    ok "Caddy: hrafnagud block removed"
fi
