#!/usr/bin/env bash
set -euo pipefail
umask 077

# 代码按提交隔离；共享凭据始终留在发布目录外，不进入构建上下文和发布包。
[[ "${RELEASE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid release SHA' >&2; exit 1; }
root=/opt/mediary
release="$root/releases/$RELEASE_SHA"
archive="/tmp/mediary-$RELEASE_SHA.tar.gz"
test -d "$root"
test -s "$root/shared/.env" || { echo 'Configure /opt/mediary/shared/.env before deployment' >&2; exit 1; }
test -f "$archive"
if [[ -e "$root/current" && ! -L "$root/current" ]]; then
    echo 'Expected current to be a release symlink' >&2
    exit 1
fi
previous=$(readlink -f "$root/current" 2>/dev/null || true)
mkdir -p "$release"
tar --no-same-owner -xzf "$archive" -C "$release"
ln -sfn "$root/shared/.env" "$release/.env"
cd "$release"
docker compose -p mediary config --quiet

ready() {
    local address
    address=$(docker compose -p mediary port mediary-api 8787 2>/dev/null) || return 1
    [[ "$address" =~ ^127\.0\.0\.1:[0-9]+$ ]] || return 1
    curl --max-time 5 --fail --silent "http://$address/health" >/dev/null
}

activate() {
    docker compose -p mediary up -d --build || return 1
    for attempt in $(seq 1 60); do
        if ready; then return 0; fi
        sleep 2
    done
    return 1
}

if ! activate; then
    docker compose -p mediary logs --tail=30 mediary-api || true
    # 只恢复当前应用的上一个源码版本；不操作其他项目，也不执行全局镜像清理。
    if [[ "$previous" == "$root/releases/"* && -d "$previous" ]]; then
        cd "$previous"
        docker compose -p mediary up -d --build
    fi
    echo 'Release did not become healthy; current release pointer was preserved' >&2
    exit 1
fi

ln -sfn "$release" "$root/.current-$RELEASE_SHA"
mv -Tf "$root/.current-$RELEASE_SHA" "$root/current"
printf '%s\n' "$RELEASE_SHA" > "$root/DEPLOYED_SHA"
rm -f "$archive"
echo "Mediary release $RELEASE_SHA is healthy"
