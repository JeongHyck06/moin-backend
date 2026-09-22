#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
# The Docker service owns the application port after the first deployment.
systemctl disable spring-app.service
config=/etc/nginx/sites-available/home-spring
cp -a "$config" "$config.before-moin"
sed -i 's/client_max_body_size 20m;/client_max_body_size 32m;/' "$config"
nginx -t
systemctl reload nginx
systemctl enable docker nginx
echo 'MOIN_HOST_READY: Docker autostart; video uploads up to application limit; legacy Java service disabled.'
