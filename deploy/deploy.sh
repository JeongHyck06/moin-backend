#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "$0")"
release=${1:-}
checksum=${2:-}
[[ "$release" =~ ^[a-f0-9]{40}$ && "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo 'Usage: deploy.sh RELEASE_ID SHA256' >&2; exit 2; }
exec 9>.deploy.lock
flock -w 600 9
archive="incoming/$release.tar.gz"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check --status
docker load -i "$archive" > /dev/null
docker image inspect "moin-backend:$release" > /dev/null
previous=$(cat release.env 2>/dev/null || true)
had_demo=0
if systemctl is-active --quiet spring-app.service; then had_demo=1; fi
printf 'APP_IMAGE=moin-backend:%s\n' "$release" > release.next.env
dc() { docker compose --env-file .env --env-file "${1}" -f compose.yaml "${@:2}"; }
dc release.next.env config --quiet
dc release.next.env up -d --wait --wait-timeout 180 db
if ((had_demo)); then sudo -n /usr/bin/systemctl stop spring-app.service; fi
changed=0
rollback() {
  trap - EXIT HUP INT TERM
  if ((changed)); then
    echo 'Deployment failed; rolling back the backend image.' >&2
    if [[ -n "$previous" ]]; then
      printf '%s\n' "$previous" > release.env
      dc release.env up -d --no-deps --wait --wait-timeout 180 backend || echo 'ROLLBACK FAILED: inspect docker compose logs.' >&2
    else
      dc release.next.env stop backend || true
      if ((had_demo)); then sudo -n /usr/bin/systemctl restart spring-app.service || true; fi
    fi
  fi
}
trap rollback EXIT
trap 'exit 130' HUP INT TERM
changed=1
if dc release.next.env up -d --no-deps --wait --wait-timeout 180 backend; then
  mv -f release.next.env release.env
  changed=0
  if [[ -n "$previous" ]]; then printf '%s\n' "$previous" > previous-release.env; fi
  printf '%s\n' "$release" > deployed-release
  echo "DEPLOYED_HEALTHY: $release"
  # Only remove the verified incoming archive. Preserve images and all data volumes.
  rm -f "$archive"
else
  echo 'Container health check failed.' >&2
  exit 1
fi
