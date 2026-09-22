# 홈 서버 운영

`main` push → Java 17 테스트 → Docker 이미지 생성 → SSH 배포 → MySQL을 포함한 상태 확인.
GitHub에서 이미지를 만들어 전달하며, 홈 서버는 공개 저장소의 Actions runner로 사용하지 않습니다.

## 구성

- Nginx: 호스트 80번 → `127.0.0.1:18080`
- Spring: Docker `backend`, prod 프로필, 외부 개발 로그인 비활성화
- MySQL 8.4: Docker `db`, 내부 네트워크 전용, 외부 공개 포트 없음
- 영구 볼륨: `moin-mysql-data`, `moin-uploads`; Compose의 external 볼륨으로 선언해 일반적인 `down -v`에도 유지
- 재시작 정책: `unless-stopped`; Docker 부팅 시 자동 시작
- 로그 회전: 컨테이너별 10MB × 3개
- 앱 상태: `/actuator/health` 내부 점검. Nginx는 외부 Actuator 접근 차단

서버 파일은 `/home/jack/apps/moin`에 있습니다. `.env`는 서버에서 생성한 비밀번호를 담으므로 Git에 넣지 않습니다. Spring/DB 모두 같은 설정을 참조합니다. 이미 초기화한 MySQL의 비밀번호는 `.env` 수정만으로 바뀌지 않으므로 DB 계정도 함께 변경해야 합니다.

```bash
cd /home/jack/apps/moin
docker compose --env-file .env --env-file release.env ps
docker compose --env-file .env --env-file release.env logs --tail=100 backend
docker compose --env-file .env --env-file release.env logs -f backend
docker compose --env-file .env --env-file release.env restart backend
```

`release.env`는 현재 앱 이미지, `previous-release.env`는 이전 앱 이미지입니다. 배포 스크립트는 새 컨테이너가 건강하지 않으면 이전 이미지로 복구합니다. DB 스키마 변경은 이미지 복구와 별개입니다. `ddl-auto=update`를 사용하므로 호환되지 않는 스키마 변경은 별도 마이그레이션이 필요합니다. 현재 배포는 짧은 재시작 시간이 있습니다.

## GitHub

Repository Variables: `DEPLOY_HOST`, `DEPLOY_PORT` (기본 2222).
Repository Secrets: `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`.

배포 키는 서버의 jack 사용자 권한을 가지므로 main 브랜치와 저장소 쓰기 권한을 신뢰하는 사람에게만 부여하세요. IP가 변경되면 `DEPLOY_HOST`와 `DEPLOY_KNOWN_HOSTS`의 호스트 이름을 함께 변경해야 합니다.

## 선택 설정

- FCM: `secrets/firebase-service-account.json`을 컨테이너 사용자 10001이 읽을 수 있게 저장하고 `.env`의 `FCM_CREDENTIALS=/run/secrets/firebase-service-account.json`을 설정한 뒤 backend를 재생성합니다. 키가 없으면 실제 푸시는 발송되지 않습니다.
- 앱 버전·스토어 주소: `.env`의 `APP_MIN_VERSION`, `APP_LATEST_VERSION`, `IOS_STORE_URL`, `ANDROID_STORE_URL`.
- 도메인을 연결한 뒤 HTTPS를 설정하세요. 현재 공인 IP HTTP 접속은 가능하지만 로그인 토큰과 영상 전송 보호를 위해 실제 사용자 서비스에는 HTTPS가 필요합니다.
- DB 및 영상 백업은 운영 데이터가 쌓이기 전에 외부 저장소까지 구성하세요. Docker 볼륨은 영구 저장 공간이며 서버 디스크 장애에 대한 백업은 아닙니다.

`compose.yaml`, `deploy.sh`, `finalize-host.sh` 변경은 서버의 운영 파일에도 반영해야 합니다. 자동 배포 워크플로는 앱 이미지 교체만 수행해 DB·비밀번호 설정을 보존합니다.
