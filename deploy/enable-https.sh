#!/usr/bin/env bash
set -euo pipefail

# sudo 비밀번호와 인증기관 약관 응답은 운영자가 터미널에 직접 입력
[[ "$EUID" -eq 0 ]] || { echo 'sudo bash enable-https.sh 로 실행하세요' >&2; exit 1; }
[[ "$(hostname)" == jackhome ]] || { echo 'jackhome 서버에서만 실행하세요' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
domain=moin-api.duckdns.org
site=/etc/nginx/sites-available/moin-api
enabled=/etc/nginx/sites-enabled/moin-api
test -f "$script_dir/nginx-moin-api.conf"

if ! dpkg-query -W -f='${Status}' python3-certbot-nginx 2>/dev/null | grep -q 'install ok installed'; then
    apt-get update
    apt-get install -y certbot python3-certbot-nginx
fi

# Certbot 이 추가한 TLS 설정을 재실행 시 덮어쓰지 않음
if [[ ! -e "$site" && ! -L "$enabled" ]]; then
    install -m 644 "$script_dir/nginx-moin-api.conf" "$site"
    ln -s "$site" "$enabled"
    if ! nginx -t; then
        rm -f "$enabled" "$site"
        exit 1
    fi
    systemctl reload nginx
fi

if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
    ufw allow 443/tcp
fi

echo '공유기 TCP 443이 192.168.45.210:443으로 전달되어 있어야 외부 HTTPS 접속 가능'
echo 'Certbot 이메일과 약관 동의는 아래 프롬프트에서 직접 입력하세요'
certbot --nginx --redirect -d "$domain"
nginx -t
systemctl reload nginx
systemctl enable --now certbot.timer
certbot renew --dry-run
echo "HTTPS 설정 완료: https://$domain/app/version"
