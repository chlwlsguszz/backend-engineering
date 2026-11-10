# Market Engine 프론트엔드

프론트는 포트 5173에서 실행되며 `/backend/api` 요청을 Spring API로 프록시한다. `BACKEND_ORIGIN` 기본값은 `http://localhost:8080`이다. 배포 빌드에서 프록시 목적지는 `next build` 전에 지정한다.

## 주문과 목록

데모 화면은 시드 회원 1번으로 주문한다. 탭의 `sessionStorage`에 상품, 수량과 구매 의도별 키를 기록해 연결이 끊긴 후 새로 고쳐도 같은 주문을 조회할 수 있다. 수량 변경과 주문 성공 후의 새 구매에는 새 키를 발급한다. 성공, 재고 부족 또는 판매 중단 응답을 받으면 PostgreSQL 상품 상세에서 최신 재고와 판매 상태를 읽는다. 목록은 필터를 바꾸면 이전 요청을 중단하며, 다음 페이지 읽기에 실패한 경우 같은 페이지를 재시도한다.

## 격리된 브라우저 검사

프로젝트의 `frontend` 폴더에서 아래 PowerShell 명령을 순서대로 실행한다. 테스트용 Compose는 32개 상품을 가진 별도 PostgreSQL과 Spring API를 만든다. 테스트는 그 API에 상품과 주문을 생성한다. Chromium이 실행하는 프론트는 `127.0.0.1:15173`을 사용한다.

```powershell
npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw '의존성 설치 실패' }; npx.cmd playwright install chromium --only-shell
docker compose -p browser-smoke -f e2e/compose.yml up -d --build; if ($LASTEXITCODE -ne 0) { throw '테스트 환경 기동 실패' }; $env:BACKEND_ORIGIN='http://' + (docker compose -p browser-smoke -f e2e/compose.yml port backend 8080)
npm.cmd run lint; if ($LASTEXITCODE -ne 0) { throw '린트 실패' }; npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw '빌드 실패' }; curl.exe -fsS --retry 30 --retry-all-errors --retry-delay 2 "$env:BACKEND_ORIGIN/actuator/health"; if ($LASTEXITCODE -ne 0) { throw '테스트 API 기동 실패' }; npm.cmd run test:e2e
docker compose -p browser-smoke -f e2e/compose.yml down -v
```

테스트는 마지막 재고 주문의 성공 응답을 브라우저에서 유실시킨 후 페이지를 새로 고치고, 같은 키와 주문 번호로 성공하는지 확인한다. 별도의 새 주문에는 서로 다른 키가 쓰이고 상품 재고가 갱신되는지도 검사한다. 목록 테스트는 브라우저 네트워크에서 응답 순서와 오류를 제어해 페이지 누락 및 이전 필터 결과가 섞이는 경우를 재현한다.
