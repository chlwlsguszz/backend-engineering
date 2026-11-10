# 검색과 주문의 상황별 k6 실험

Windows PowerShell에서 프로젝트 루트를 기준으로 명령을 한 줄씩 실행한다. 예시는 로컬 k6와 `http://localhost:8080`의 백엔드를 사용한다. 첫 실험은 결과가 정해진 기능 검사부터 시작하고, 기능이 맞는 경로에서 도착률을 올려 성능을 측정한다.

PowerShell에서 `k6` 명령을 찾을 수 없으면 Docker Desktop으로 같은 스크립트를 실행할 수 있다. 아래 예시의 `CASE`와 파일명을 바꿔 다른 상황도 선택한다. 주문 실험에서는 먼저 내보낸 `order-targets.json`도 같은 폴더에서 읽는다.

```powershell
docker run --rm -v "${PWD}/scripts/k6:/scripts:ro" grafana/k6:1.5.0 run -e BASE_URL=http://host.docker.internal:8080 -e CASE=pagination /scripts/search_scenarios.js
```

## 검색

1,000만 건 색인이 완료된 B9 환경을 사용한다. 아래 조회에서 `count`가 `10000000`인지 먼저 확인한다.

```powershell
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-10m'
Invoke-RestMethod 'http://localhost:9200/products/_count'
k6 run -e CASE=pagination scripts/k6/search_scenarios.js
```

- **페이지 경계 (`pagination`).** `StudioCapsule`을 최신순과 인기순으로 각각 조회한다. 상품 13개가 첫 페이지 12개와 다음 페이지 1개로 나뉘고 두 페이지에서 ID 1~13이 정확히 한 번씩 나와야 한다. 현재 ES 구현은 페이지 시작 위치까지 `size + 1`로 계산한다. 이 검사에서 실패하면 누락 문제를 먼저 재현하고, 수정 뒤 같은 명령으로 확인한다.
- **인기 피드 (`feed`).** 짧은 간격으로 같은 홈과 카테고리 목록을 요청한다. 캐시 ON/OFF 두 실행에 같은 입력과 도착률을 사용해 Redis가 ES 호출을 얼마나 줄이는지 본다.
- **검색어별 결과 (`discovery`).** 넓게 매칭되는 검색어, 존재하는 모델 코드, 의도적으로 없는 코드를 구분한다. 응답 시간은 검색 종류별로 비교한다. 결과가 드문 키워드도 같은 페이지를 다시 요청하면 캐시에 적중할 수 있다.
- **연속 탐색 (`browse`).** 한 사용자가 같은 조건에서 다음 페이지로 이동한다. 응답의 `hasNext`가 거짓이면 중단한다. `page=0..2`와 `page>=3`의 지연을 나눠 본다. 기본 크기 12에서 3페이지부터는 애플리케이션의 피드 캐시를 우회한다.

```powershell
k6 run -e CASE=feed -e RATE=5 -e DURATION=30s scripts/k6/search_scenarios.js
k6 run -e CASE=discovery -e RATE=5 -e DURATION=30s scripts/k6/search_scenarios.js
k6 run -e CASE=browse -e RATE=5 -e DURATION=30s -e MAX_PAGE=8 scripts/k6/search_scenarios.js
```

`RATE`는 초당 시작하는 탐색 세션 수다. 연속 탐색은 한 세션에서 여러 번 요청하므로 실제 HTTP 처리량은 k6의 `http_reqs`로 읽는다. 10만 건 파일럿에서 `discovery`를 실행할 때는 `-e MODEL_COUNT=25000`을 추가한다. [검색 스크립트](../scripts/k6/search_scenarios.js)

### 같은 피드로 캐시 효과 비교

아래 두 블록을 순서대로 사용한다. 백엔드를 재생성할 때마다 `/actuator/health`가 `UP`이 된 후 k6를 실행한다. 각 조건에서 같은 짧은 예열을 한 번 하고 두 번째 실행을 기록한다. 캐시 ON 조건에서는 기존 Redis 키도 재사용될 수 있으므로 예열 여부를 기록한다.

```powershell
$env:MARKETENGINE_CACHE_REDIS_ENABLED = 'false'
docker compose up -d --no-deps backend
# 백엔드 시작 후 실행
Invoke-RestMethod 'http://localhost:8080/actuator/health'
k6 run -e CASE=feed scripts/k6/search_scenarios.js
```

```powershell
$env:MARKETENGINE_CACHE_REDIS_ENABLED = 'true'
docker compose up -d --no-deps backend
# 백엔드 시작 후 실행
Invoke-RestMethod 'http://localhost:8080/actuator/health'
k6 run -e CASE=feed scripts/k6/search_scenarios.js
```

각 측정 직전과 직후에 아래 카운터를 읽고 차이를 기록한다. Redis 수치는 인스턴스 전체, ES의 `query_total`은 샤드 단위 누적값이다. 같은 환경에 다른 트래픽이 섞이면 수치 차이를 이 실험에만 귀속할 수 없다. 캐시를 우회하는 요청은 Redis 미적중 카운터에도 들어가지 않는다.

현재 피드 키의 TTL은 기본 30~60초다. 예열한 키도 측정 도중 만료될 수 있으므로 결과에 TTL 설정을 함께 기록한다. 캐시를 켠 실험 DB에서 필터 없이 최신순 첫 페이지(`page=0`, `size=12`)를 조회한 직후 아래 한 줄로 남은 시간을 밀리초 단위로 확인한다.

```powershell
docker compose exec -T redis redis-cli PTTL 'productListFeed:v2::feed:q1?cat=&brand=&gender=&color=&min=&max=&kw=&sort=LATEST&page=0&size=12'
```

상품 수정의 영향을 확인하려면, 양수인 키의 TTL을 기록하고 다른 상품 하나를 수정한 직후 피드를 다시 조회하기 전에 같은 키를 확인한다. 자연 만료 전이라면 키는 남아 있고 TTL은 경과한 만큼 줄어든다.

```powershell
docker compose exec -T redis redis-cli INFO stats | Select-String 'keyspace_hits|keyspace_misses'
Invoke-RestMethod 'http://localhost:9200/products/_stats/search?filter_path=_all.total.search.query_total' | ConvertTo-Json -Depth 6
```

### 피드 처리량 한계 찾기

공통 예열은 50 RPS로 10초 동안 전체 최신순, 상의 최신순, 전체 인기순을 조회한다. 세 종류 모두 유효한 응답을 한 번 이상 받아야 하고, 전송한 요청의 응답 검사가 모두 통과해야 본 측정에 들어간다. 예열 중 시작하지 못한 요청 수는 `warmup.log`에 따로 기록한다.

ES 색인 건수와 캐시 상태를 확인하고, 같은 네트워크 경로와 같은 k6 버전에서 `feed`의 도착률만 올린다. 이 예시는 현재 장비에 있는 Docker 이미지 `grafana/k6:0.58.0`을 사용한다. 예열 뒤 각 단계는 30초다. 프로젝트 README의 기존 관측치를 참고해 **p99 200ms 이하**를 이번 실험의 임시 기준으로 둔다. 응답 검증 실패 또는 `dropped_iterations > 0`도 해당 단계를 중단시킨다.

```powershell
.\scripts\k6\run-feed-capacity.ps1
```

3,000 RPS에서 VU 300개를 모두 사용했다는 경고가 나오면, 같은 요청률에서 **VU만 600개로** 바꿔 한 단계만 다시 측정할 수 있다. 결과 폴더의 `feed-3000.log`에는 k6 경고 원문이, `feed-3000.json`에는 지연과 미시작 횟수가 남는다.

```powershell
.\scripts\k6\run-feed-capacity.ps1 -Rates 3000 -VUs 600
```

5,000 RPS에서 연결 거부나 연결 타임아웃이 발생하면, 기존 호스트 경유 측정과 같은 1,200 VU에서 경로만 바꿔 비교할 수 있다. `-Route Direct`는 k6를 `marketengine-catalog-10m_default`에 연결해 `http://backend:8080`으로 예열과 본 측정을 수행한다. 기본 경로인 `-Route Host`는 `http://host.docker.internal:8080`을 사용한다. 결과 폴더 이름 끝에 경로가 표시된다.

```powershell
.\scripts\k6\run-feed-capacity.ps1 -Rates 5000 -VUs 1200 -Route Direct
```

**급증과 점진 증가 비교.** 기본값은 50 RPS로 10초간 예열한 다음, 새 k6 프로세스에서 목표 요청률로 바로 시작하는 방식이다. `-RampSeconds 30`을 주면 예열 후 새 프로세스에서 VU를 미리 준비하고, 요청률을 30초간 50에서 목표치까지 선형으로 높인다. 이 프로세스가 연결을 이어 사용하며 목표 요청률을 30초간 유지한다. 7,000 RPS 급증 결과와 비교할 때는 경로, VU 수, 요청 종류를 맞춘다.

```powershell
.\scripts\k6\run-feed-capacity.ps1 -Rates 7000 -VUs 1200 -Route Direct -RampSeconds 30
```

점진 증가 결과의 `http_req_duration{phase:hold}`는 목표 유지 구간에 시작한 요청의 지연이다. 200ms 기준은 이 구간의 p99에 적용한다. `http_reqs{phase:hold}`의 건수를 유지 시간 30초로 나누면 이 구간에 시작해 결과가 기록된 초당 요청 수를 구할 수 있다. 기본 `http_reqs`의 초당 수치는 증가 구간도 포함한 평균이다. `dropped_iterations`는 전체 측정 동안 시작하지 못한 횟수라서, 이 숫자만으로 누락이 발생한 구간을 판별할 수 없다. 응답 검사와 누락 0건 기준은 전체 측정에 적용한다. [k6 요청률 점진 증가](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/)

VU 수는 최초 탐색을 위한 가정이다. 요청을 놓치면 해당 단계의 `http_reqs`, `iterations`, `iteration_duration`, k6와 백엔드의 CPU를 함께 확인한다. 생성기에 여유가 있고 VU가 부족했다면 같은 RPS에서 사전 VU를 늘려 재시도한다. 응답 지연 기준을 넘었다면 마지막 통과점과 첫 실패점 사이에서 다시 측정한다. 마지막 통과점은 2~3분씩 두 번 재현한 뒤 지속 가능 처리량으로 기록한다. 최고 단계까지 통과한 경우 그 수치는 이번 실험에서 확인한 하한이다. [k6 도착률과 VU 할당](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/)

### 0에서 10,000 RPS까지 올리며 지연과 실패율이 커지는 구간 찾기

아래 한 줄은 Docker 내부 경로에서 같은 세 가지 피드를 예열한 뒤, 한 k6 프로세스에서 요청률을 0부터 10,000 RPS까지 **200초간 선형으로** 높이고 마지막에 **30초 동안 10,000 RPS**를 유지한다. 100 VU를 미리 준비하고 응답이 밀려 더 많은 동시 작업이 필요해지면 k6가 최대 2,000 VU까지 추가한다. 한 번의 실행으로 전체 범위를 관찰하며, 앞 구간의 지연이 높아져도 다음 구간까지 측정한다.

```powershell
.\scripts\k6\run-feed-capacity.ps1 -Sweep
```

**10,000 RPS를 측정한 뒤 15,000 RPS까지 확장할 때**는 아래 명령을 사용한다. 10초간 예열한 다음 0→10,000 RPS와 10,000→15,000 RPS를 각각 30초에 걸쳐 올린다. 15,000 RPS에 도달하면 새 요청을 보내는 일정이 끝난다. 예정 시간은 예열 포함 1분 10초이며, k6 시작과 마지막 응답 대기 시간은 별도다.

```powershell
.\scripts\k6\run-feed-capacity.ps1 -Sweep -TargetRps 15000
```

기본 1,000 RPS 폭에서 10,000 아래의 각 구간은 3초, 그 위의 각 구간은 6초다. 보고서는 실행 시 저장한 `settings.json`에서 상승 일정을 읽어 각 구간의 실제 길이로 처리율과 전달 비율을 계산한다. 마지막 행은 14,000→15,000 RPS 상승 구간이다. 본 측정에서 계획한 요청 수는 총 525,000건이다. 이전 10,000 RPS 실험과 준비 VU(100), 최대 VU(2,000), 피드 요청 비중은 같고, 하위 구간은 빠르게 지나가므로 그 구간 p99를 비교할 때는 표본 크기도 확인한다.

실행 후 콘솔에 표시되는 결과 폴더(`%TEMP%\k6-feed-…-Direct-sweep`)에서 `feed-sweep-report.txt`를 연다. `feed-sweep.csv`에는 RPS 구간마다 한 줄이 기록되며, 유지 시간을 지정한 실험에는 유지 구간 행도 표시된다. 예를 들어 기본 10,000 RPS 실험의 `6000-7000` 행은 목표가 6,000에서 7,000으로 올라가는 20초 동안 **시작한 요청**의 결과다. 목표 평균은 6,500 RPS이고, 관측 요청 수를 20초로 나눈 값이 `ObservedRps`다. `CoveragePct`가 낮으면 발생기가 계획한 부하를 얼마나 전달했는지도 함께 본다. 연결 실패 등 HTTP 실패는 시도한 요청 수를 분모로 계산하고, 시작하지 못한 요청은 전체 `dropped_iterations`로 별도 기록한다.

보고서는 p95/p99(ms), HTTP 실패 건수와 실패율, 응답 검사 실패 수, 표본에서 관측한 준비 및 사용 VU 최댓값을 보여준다. 요청 표본이 100건 이상인 구간에서 아래 기준으로 첫 변화 후보를 표시한다.

- **기준 초과**는 p99가 200ms를 넘은 첫 구간과 HTTP 실패율이 0.1%를 넘은 첫 구간을 각각 표시한다.
- **급격한 변화**는 p99가 직전 구간의 2배 이상이면서 20ms 이상 증가하거나, HTTP 실패율이 직전보다 0.5%포인트 이상 증가한 첫 구간을 각각 표시한다.

이번 탐색에서 정한 판정 기준은 `settings.json`에 기록된 실행 설정 및 보고서에서 확인할 수 있다. 구간 중간의 정확한 임계점을 단정할 수는 없으므로, 후보가 나오면 그 구간 안의 고정 RPS에서 재측정한다. 동적으로 VU를 추가하는 k6 자체의 비용도 영향을 줄 수 있어, 재측정 때는 VU를 충분히 미리 할당하고 경로와 캐시 조건을 맞춘다. [k6의 VU 할당 방식](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/)

## 주문

아래 실험은 실제 주문을 생성하고 재고를 차감한다. 격리한 B9 실험 DB에서 현재 주문 대상 목록을 먼저 내보낸다. 추출이 실패하면 기존 파일이 남으므로 오류를 해결한 뒤 진행한다.

```powershell
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-10m'
.\scripts\sql\run-seed-order-products.ps1 -Seed 42 -Count 1000
```

### 주문 재시도와 동시 제출

별도 부하가 없는 상태에서 아래 세 경우 중 하나씩 실행한다. 실행마다 새 멱등 키를 사용하고, 한 실행 안에서는 같은 회원과 같은 상품에 같은 키를 보낸다. 재실행 전에 대상 목록을 새로 추출한다.

```powershell
k6 run -e CASE=replay scripts/k6/order_idempotency.js
k6 run -e CASE=burst scripts/k6/order_idempotency.js
k6 run -e CASE=last-unit scripts/k6/order_idempotency.js
```

`replay`는 응답 이후 재전송, `burst`는 동시 제출, `last-unit`은 재고가 정확히 한 개인 상품의 동시 제출이다. 통과하려면 모든 요청이 같은 유효한 주문 ID를 받고, 해당 회원과 키로 DB 주문이 한 건이어야 하며, 실제 재고는 한 개만 줄어야 한다. k6가 출력하는 읽기 전용 SQL 확인 명령까지 실행한다. 마지막 한 개 조건에서 후보가 없으면 다른 추출 시드를 사용한다. 현재 주문 코드는 큰 상품 ID의 동일성 비교와 마지막 한 개의 주문 처리 순서 때문에 이 검사에서 실패할 수 있다. [멱등성 스크립트](../scripts/k6/order_idempotency.js)

### 인기 상품 경합이 일반 상품에 미치는 영향

같은 시작 스냅샷에서 추출한 상품을 사용한다. 기본값에서는 재고가 20개 이상인 표본 중 인기 점수 상위 10개를 인기 상품으로 정한다. 다음 세 명령은 **같은 실험 DB 스냅샷으로 각각 복원한 뒤** 한 번씩 실행하는 비교 예시다. 대상 JSON을 새로 추출하는 작업은 DB의 주문이나 재고를 복원하지 않는다.

```powershell
k6 run -e CASE=normal scripts/k6/order_scenarios.js
k6 run -e CASE=mixed scripts/k6/order_scenarios.js
k6 run -e CASE=control scripts/k6/order_scenarios.js
```

- **평상시 (`normal`).** 일반 상품에 초당 3건을 보내 일반 주문의 지연을 측정한다.
- **인기 상품에 주문 집중 (`mixed`).** 같은 일반 상품에 계속 초당 3건을 보내면서, 별도 흐름이 인기 상품 10개에 초당 7건을 추가한다. 일반 주문까지 느려지는지가 관찰 대상이다.
- **같은 총량으로 분산 (`control`).** 일반 주문 초당 3건을 유지하고 추가 7건도 일반 상품 여러 개에 보낸다. `mixed`와 총량이 같으므로 일반 주문 지연이 인기 상품 집중과 관련 있는지 비교할 수 있다.

기본 지속 시간은 10초다. `NORMAL_RPS`, `EXTRA_RPS`, `SECONDS`로 조절한다. 처음에는 주문이 정상 생성되는지 확인하고, 비교 표본이 충분해지도록 실행 시간을 늘리되 인기 SKU의 가용 재고도 함께 확인한다. 세 조건 모두 콘솔의 `order_probe_duration`이 일반 주문 흐름의 p95/p99를 보여준다. `orders_stockout`은 재고 부족, `orders_key_conflict`는 멱등 키 충돌이다. 품절이 발생한 실행은 오류 코드별 건수를 남기고 성공 주문의 경합 실험과 구분한다. [주문 부하 스크립트](../scripts/k6/order_scenarios.js)

## 결과를 해석하는 순서

매 실행에 코드 버전, DB 또는 색인 규모, 시작 재고나 캐시 예열 상태, 목표 도착률, 실제 완료 요청 수, `dropped_iterations`, p95/p99, 오류 코드별 건수를 함께 기록한다. 짧은 기본 실행의 p99는 표본 수와 함께 읽는다.

검색은 같은 요청에서 캐시 설정만 바꾸고, 연속 탐색은 같은 조건의 페이지 깊이별 지연을 확인한다. 주문은 `normal`, `mixed`, `control` 세 경우의 **일반 주문 흐름**을 비교한다. 혼합 조건에서만 지연이 커지면 같은 시간대의 Hikari 대기 연결 수와 PostgreSQL 잠금 대기를 확인한다. 처리율을 못 맞춰 `dropped_iterations`가 증가했다면 실제로 전달된 부하부터 대조한다. 개선 전후에는 원인 후보 한 가지를 변경하고 같은 시나리오를 다시 실행한다.
