#!/usr/bin/env python3
# coding: utf-8
"""운영자 계정의 기존 세션으로 신고 열람·처리, 토큰은 파일이나 인자로 저장하지 않음"""
import argparse, getpass, json, urllib.request, urllib.error
p = argparse.ArgumentParser()
p.add_argument('--url', default='https://moin-api.duckdns.org')
p.add_argument('--report', type=int)
p.add_argument('--action', choices=['DISMISS', 'REMOVE', 'SUSPEND'])
a = p.parse_args()
if not a.url.startswith('https://'):
    p.error('운영자 세션은 HTTPS에서만 사용하세요')
if bool(a.report) != bool(a.action):
    p.error('--report와 --action은 함께 지정하세요')
token = getpass.getpass('운영자 계정의 모인 세션 토큰: ')
path = '/moderation/reports'
data = None
if a.action:
    if input(f'신고 {a.report}에 {a.action} 처리 (yes 입력): ') != 'yes':
        raise SystemExit('취소됨')
    path += '/' + str(a.report)
    data = json.dumps({'action': a.action}).encode()
req = urllib.request.Request(a.url.rstrip('/') + path, data=data,
    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(req, timeout=20) as response:
        content = response.read()
        print(json.dumps(json.loads(content), ensure_ascii=False, indent=2) if content else '처리 완료')
except urllib.error.HTTPError as e:
    print('요청 실패:', e.code, e.read().decode())
    raise SystemExit(1)
