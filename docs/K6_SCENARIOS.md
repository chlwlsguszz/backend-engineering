# 검색과 주문의 상황별 k6 실험

Windows PowerShell에서 프로젝트 루트를 기준으로 명령을 한 줄씩 실행한다. 예시는 로컬 k6와 `http://localhost:8080`의 백엔드를 사용한다. 첫 실험은 결과가 정해진 기능 검사부터 시작하고, 기능이 맞는 경로에서 도착률을 올려 성능을 측정한다.

PowerShell에서 `k6` 명령을 찾을 수 없으면 Docker Desktop으로 같은 스크립트를 실행할 수 있다. 아래 예시의 `CASE`와 파일명을 바꿔 다른 상황도 선택한다. 주문 실험에서는 먼저 내보낸 `order-targets.json`도 같은 폴더에서 읽는다.

```powershell
docker run --rm -v "${PWD}/scripts/k6:/scripts:ro" grafana/k6:1.5.0 run -e BASE_URL=http://host.docker.internal:8080 -e CASE=pagination /scripts/search_scenarios.js
```

## 검색

1,000만 건 색인이 완료된 B9 카탈로그를 사용한다. 삭제 표시를 제외한 검색 가능 상품의 `count`가 `10000000`인지 먼저 확인한다.

```powershell
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-10m'
Invoke-RestMethod 'http://localhost:9200/products/_count' -Method Post -ContentType 'application/json' -Body '{"query":{"bool":{"must_not":[{"term":{"outboxDeleted":true}}]}}}'
k6 run -e CASE=pagination scripts/k6/search_scenarios.js
```

- **페이지 경계 (`pagination`).** `StudioCapsule`을 최신순과 인기순으로 각각 조회한다. 상품 13개가 첫 페이지 12개와 커서 다음 페이지 1개로 나뉘고 두 페이지에서 ID 1~13이 정확히 한 번씩 나와야 한다. 최대 `size + 1`개를 읽어 다음 페이지 유무를 판단한다.
- **인기 피드 (`feed`).** 짧은 간격으로 같은 홈과 카테고리 목록을 요청한다. 캐시 ON/OFF 두 실행에 같은 입력과 도착률을 사용해 Redis가 ES 호출을 얼마나 줄이는지 본다.
- **검색어별 결과 (`discovery`).** 넓게 매칭되는 검색어, 존재하는 모델 코드, 의도적으로 없는 코드를 구분한다. 응답 시간은 검색 종류별로 비교한다. 결과가 드문 키워드도 같은 페이지를 다시 요청하면 캐시에 적중할 수 있다.
- **연속 탐색 (`browse`).** 한 사용자가 같은 조건에서 응답의 `nextCursor`를 다음 요청의 `after`로 전달한다. `hasNext`가 거짓이면 중단한다. 처음, 1~2회 추가 탐색, 3회 이상 추가 탐색의 지연을 나눠 본다. 커서 요청은 모두 캐시를 우회한다. RATE는 세션/초이며 HTTP 요청/초와 다르다.

PG의 상품명 검색은 대소문자를 정규화한 부분 문자열, ES는 분석한 토큰과 카탈로그 별칭을 사용한다. 최신순에서 PostgreSQL은 원본 시각, 기존 ES `date` 매핑은 밀리초로 정렬하고 각 저장소에서 동점이면 ID가 큰 상품이 먼저다. 생성 시각이 같은 밀리초 안에서만 다른 상품은 양쪽의 상대 순서가 달라질 수 있다. 페이지 경계 실험은 고정된 카탈로그와 한 저장소에서 이어지는 페이지를 사용한다.

```powershell
k6 run -e CASE=feed -e RATE=5 -e DURATION=30s scripts/k6/search_scenarios.js
k6 run -e CASE=discovery -e RATE=5 -e DURATION=30s scripts/k6/search_scenarios.js
k6 run -e CASE=browse -e RATE=5 -e DURATION=30s -e MAX_PAGE=8 scripts/k6/search_scenarios.js
```

`RATE`는 초당 시작하는 탐색 세션 수다. 연속 탐색은 한 세션에서 여러 번 요청하므로 실제 HTTP 처리량은 k6의 `http_reqs`로 읽는다. 10만 건 파일럿에서 `discovery`를 실행할 때는 `-e MODEL_COUNT=25000`을 추가한다. [검색 스크립트](../scripts/k6/search_scenarios.js)

### 검색 품질 회귀 평가

ES 검색은 [카탈로그 검색어 규칙](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductKeywordQuery.java)을 조회 시점에 적용한다. 기존 인덱스와 매핑을 사용하므로 이 변경을 위한 재색인은 필요하지 않다.

- `나이키 검정 후드`, `뉴발란스 러닝화`처럼 등록된 브랜드·색상·성별·카테고리·품목 표현을 영문 카탈로그에 연결한다. 원문 상품명도 함께 검색하므로 실제 한글 상품명도 유지된다. `운동화`는 Sneakers와 Running Shoes를 포함한다.
- `New Balance`, `Long Sleeve` 같은 등록된 여러 단어의 표현은 하나의 구절로 해석한다. 상품명에서는 단어가 연속해야 하며, 브랜드 등 속성에서는 전체 값으로 비교한다. 나머지 검색 조건과 명시적 필터는 모두 충족해야 한다.
- 별칭이 아닌 ASCII 영문 6자 이상 단어에는 편집 거리 1, 접두사 2자 고정, 최대 확장 20개로 오타를 허용한다. 예를 들어 `hoodiee`, `snekaers`를 다룬다. `shirt`/`short` 같은 짧은 단어와 숫자가 포함된 모델·컬렉션 코드는 확장하지 않는다. 등록되지 않은 한글 표현의 형태소 분석이나 오타 교정은 제공하지 않는다.
- 정렬은 최신순 또는 인기순이며, 관련도순 순위 평가는 이번 범위에 포함하지 않는다. 검색 결과가 없어진 페이지에서만 느슨한 검색으로 전환하지 않으므로 같은 조건으로 페이지를 이어 읽을 수 있다. PG의 리터럴 부분 문자열 검색 계약은 그대로다.

[검색 품질 평가셋](../backend/src/test/resources/search/product-search-quality.json)은 상품 33개와 검색어 87개(결과 있음 75개, 결과 없음 12개)의 기대 상품을 담는다. [통합 테스트](../backend/src/test/java/com/marketengine/backend/product/infrastructure/search/ProductSearchContractIntegrationTest.java)는 이전 이름 토큰 검색과 개선된 검색을 같은 데이터에서 비교하고, 오탐 방지·명시적 필터·삭제 상품 제외·최신순/인기순의 offset 및 cursor 탐색도 검사한다. 실행 시 기대 상품을 하나 이상 찾은 검색어 비율, 결과 집합이 정확히 일치한 사례 수, 전체 반환 상품 중 기대 상품 비율을 출력한다. 이 평가셋의 결과는 해당 예시에 대한 회귀 지표이며 운영 검색 품질이나 1,000만 건 성능을 입증하지 않는다.

Docker와 JDK 21을 준비한 뒤 저장소 루트에서 실행한다.

```powershell
.\backend\gradlew.bat -p backend test --tests '*ProductKeywordQueryTest' --tests '*ProductSearchContractIntegrationTest'
```

Gradle HTML 보고서는 `backend/build/reports/tests/test/index.html`에 생성된다. 검색 규칙 변경 후 이전 결과를 재사용하지 않도록 Redis 피드 이름은 `productListFeed:v6`이고 ES 커서에도 검색 규칙 버전을 포함한다. 배포 전 발급된 ES 커서는 다시 사용할 수 없으므로 첫 페이지부터 조회한다. 성능 비교는 같은 검색어 분포와 캐시 조건으로 별도 수행한다.

### 같은 피드로 캐시 효과 비교

**1,000 SKU에서 확인한 호출 수.** 격리된 단일 샤드에서 홈, 카테고리 TOP, 모델 코드 ME0000001, 3페이지를 각각 21회 호출했다. Redis를 켜면 앞의 세 조건은 첫 요청만 ES로 갔고, 3페이지는 21회 모두 ES로 갔다. 끄면 네 조건 모두 21회씩 ES로 갔다. 홈의 첫 요청은 ON 348ms, OFF 302ms였고 이어진 20회에서 관측한 p95는 각각 14.49ms, 31.87ms였다. 이 값에는 로컬 HTTP 클라이언트 시간이 포함된다. 도착률 한계나 1,000만 SKU 지연을 추정하기에는 표본이 작다. [격리 검증 명령](../scripts/verify/run-commerce-smoke.ps1)

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

현재 Redis 피드 키의 TTL은 기본 30~60초이고 Caffeine은 최대 2초다. Caffeine은 Redis의 절대 만료 시각을 넘기지 않는다. 캐시 ON/OFF 설정은 두 계층 모두에 적용된다. 예열한 키도 측정 도중 만료될 수 있으므로 결과에 TTL 설정을 함께 기록한다. 캐시를 켠 실험 DB에서 필터 없이 최신순 첫 페이지(`page=0`, `size=12`)를 조회한 직후 아래 한 줄로 남은 시간을 밀리초 단위로 확인한다.

```powershell
docker compose exec -T redis redis-cli PTTL 'productListFeed:v6:elasticsearch::feed:q2?cat=&brand=&gender=&color=&min=&max=&kw=&sort=LATEST&page=0&size=12'
```

상품 수정의 영향을 확인하려면, 양수인 키의 TTL을 기록하고 다른 상품 하나를 수정한 직후 피드를 다시 조회하기 전에 같은 키를 확인한다. 자연 만료 전이라면 키는 남아 있고 TTL은 경과한 만큼 줄어든다.

```powershell
docker compose exec -T redis redis-cli INFO stats | Select-String 'keyspace_hits|keyspace_misses'
Invoke-RestMethod 'http://localhost:9200/products/_stats/search?filter_path=_all.total.search.query_total' | ConvertTo-Json -Depth 6
```

### Elasticsearch 힙 크기 비교

1,000만 건 환경에서 Redis를 끄고 `discovery`를 사용한다. 각 검색이 ES로 가므로 힙 크기를 비교하기 쉽다. 아래 명령은 프로젝트 루트의 PowerShell에서 사용한다. ES를 재생성하는 동안 검색은 잠시 중단된다. 같은 Compose 프로젝트와 데이터 볼륨을 사용하므로 재색인은 필요하지 않다.

1. Redis 캐시를 끄고 백엔드의 `/actuator/health`가 `UP`인지 확인한다.

```powershell
$env:MARKETENGINE_CACHE_REDIS_ENABLED='false'; docker compose -p marketengine-catalog-10m up -d --no-deps backend
```

2. 첫 측정은 `512m`, 두 번째 측정은 `2g`로 아래 명령을 실행한다. 양쪽 모두 ES 재시작 후 같은 조건으로 예열한다.

```powershell
$env:ES_HEAP_SIZE='512m'; docker compose -p marketengine-catalog-10m up -d --wait --no-deps --force-recreate elasticsearch
```

3. 아래 검색을 한 번 예열하고 같은 명령으로 다시 측정한다. 50 RPS는 시작점이다. 목표 도착률, VU, 검색어 분포, ES 문서 수를 두 조건에서 맞춘다.

```powershell
docker run --rm --network marketengine-catalog-10m_default -v "${PWD}/scripts/k6:/scripts:ro" grafana/k6:0.58.0 run -e BASE_URL=http://backend:8080 -e CASE=discovery -e RATE=50 -e DURATION=60s -e PREALLOCATED_VUS=50 -e MAX_VUS=50 /scripts/search_scenarios.js
```

4. 각 측정 직전과 직후에 다음 카운터를 읽는다. 같은 ES 기동 내에서 GC 시간, 샤드 검색 수, 회로 차단 및 검색 거절 수의 증가분을 구하고 k6의 검색 유형별 p99, HTTP 실패, `dropped_iterations`와 함께 비교한다. Old 영역 점유율은 측정 중에도 표본을 수집한다. 재시작하면 노드 누적 카운터가 초기화된다.

```powershell
Invoke-RestMethod 'http://localhost:9200/_nodes/stats/jvm,indices,breaker,thread_pool?filter_path=nodes.*.jvm.mem.heap_max_in_bytes,nodes.*.jvm.mem.pools.old,nodes.*.jvm.gc.collectors,nodes.*.indices.search.query_total,nodes.*.breakers.*.tripped,nodes.*.thread_pool.search.rejected' | ConvertTo-Json -Depth 12
```

기존 실험 환경(512MiB, Redis ON)으로 복구할 때는 다음 명령을 실행한다. [Elastic JVM 메모리 압박 진단](https://www.elastic.co/docs/troubleshoot/elasticsearch/high-jvm-memory-pressure)

```powershell
$env:ES_HEAP_SIZE='512m'; $env:MARKETENGINE_CACHE_REDIS_ENABLED='true'; docker compose -p marketengine-catalog-10m up -d --wait elasticsearch backend
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

`replay`는 응답 이후 재전송, `burst`는 동시 제출, `last-unit`은 재고가 정확히 한 개인 상품의 동시 제출이다. 통과하려면 모든 요청이 같은 유효한 주문 ID를 받고, 해당 회원과 키로 DB 주문이 한 건이어야 하며, 실제 재고는 한 개만 줄어야 한다. k6가 출력하는 읽기 전용 SQL 확인 명령까지 실행한다. 마지막 한 개 조건에서 후보가 없으면 다른 추출 시드를 사용한다. [멱등성 스크립트](../scripts/k6/order_idempotency.js)

### 동시 중복 요청이 다른 구매자의 주문을 늦추는가

Windows가 기본 포트 9090을 예약한 장비에서는 아래 준비 명령 전에 `$env:PROMETHEUS_HOST_PORT='19090'`을 설정한다. 실행 스크립트는 Compose가 실제로 공개한 포트에서 HikariCP 표본을 수집한다.

수정한 백엔드가 실행 중인 B9 실험 환경에서 PowerShell로 아래 세 줄을 차례로 실행한다. 첫 줄은 같은 Compose 프로젝트에서 Prometheus를 시작하고 대상 목록을 한 번 내보낸다. 파일럿을 사용한다면 프로젝트 이름을 `marketengine-catalog-pilot`으로 바꾼다. 실행 스크립트는 Prometheus가 `backend:8080`의 Hikari 메트릭을 수집하기 시작한 뒤 부하를 보낸다. [Prometheus 수집 대상](http://localhost:9090/targets)

```powershell
$env:COMPOSE_PROJECT_NAME='marketengine-catalog-10m'; docker compose up -d prometheus; if ($LASTEXITCODE -ne 0) { throw 'Prometheus 시작 실패' }; .\scripts\sql\run-seed-order-products.ps1 -Seed 42 -Count 1000
.\scripts\k6\run-order-duplicate-contention.ps1 -DuplicatePairPct 0
.\scripts\k6\run-order-duplicate-contention.ps1 -DuplicatePairPct 50
```

**무엇을 고정하는가.** 기본값은 20초 동안 초당 단독 주문 4건과 인기 상품에 대한 동시 주문 8쌍, 총 20 POST/s다. 두 조건에서 같은 순서로 회원과 상품을 고른다. 단독 주문의 절반은 인기 상품, 절반은 그 밖의 상품으로 보낸다. 0%에서는 모든 쌍이 같은 회원과 상품에 서로 다른 키 두 개를 사용한다. 50%에서는 절반의 쌍이 키를 공유한다. 기본값 기준 예정 POST 400건 중 중복 전송은 80건(20%)이다. 중복이 늘면 신규 주문 수와 재고 쓰기 수도 줄어든다. k6는 쌍을 병렬 전송하지만 실제 처리 시점에 따라 첫 번째 주문이 먼저 커밋되어 두 번째 요청이 선조회에서 종료될 수 있다. [부하 스크립트](../scripts/k6/order_duplicate_contention.js)

**재고 조건.** 같은 `order-targets.json`을 두 실행에 사용한다. 대상 선정에는 서로 다른 주문만 보내는 조건의 두 실행분을 잡는다. 예를 들어 기본 인기 SKU 하나의 재고 예산은 실행당 36개이므로 내보낸 재고가 72개 이상인 후보에서 선정한다. 매 실행 직전에 예정된 각 SKU의 실제 재고가 한 실행분 이상인지 확인하고, `k6.log`에 SKU별 시작 재고와 필요 수량을 남긴다. 재고가 충분하면 두 명령을 연속 실행할 수 있다. 같은 시작 재고와 주문 수에서 엄밀히 비교하려면 동일한 실험용 스냅샷으로 조건별 실행을 시작한다. 시간이나 도착률을 바꿀 때는 두 조건 모두 같은 `-Seconds`, `-ProbeRps`, `-PairRps`, `-HotCount`를 지정한다.

**어떻게 판정하는가.** 각 실행은 출력한 `%TEMP%\k6-order-contention-...` 폴더에 설정과 대상 파일 해시(`settings.json`), 콘솔 로그(`k6.log`), k6 집계(`k6-summary.json`), Hikari 원본 표본(`hikari.csv`)을 남긴다. `db-orders.json`에서는 실행 키로 확인한 실제 신규 주문 수와 시작 재고 대비 차감 수량을 확인한다. 상품 재고 대사를 위해 사용하는 SKU는 실행 동안 다른 주문이나 관리자 변경에서 제외한다.

- 먼저 계획한 POST 수와 실제 `http_reqs{scenario:probe}` 및 `http_reqs{scenario:pairs}`의 합이 일치하고, 누락 반복(`dropped_iterations`), 품절, HTTP 응답 오류, 쌍별 주문 ID 불일치가 모두 0인지 확인한다. 같은 키의 두 응답은 주문 ID가 같아야 하고 서로 다른 두 키는 다른 주문이어야 한다. 실제 중복 POST 비율은 `contention_duplicate_pairs` 건수를 두 시나리오의 POST 수 합으로 나눈다. 준비 단계의 재고 조회 GET은 분모에서 제외한다.
- `contention_hot_probe_duration`의 p95(ms)는 같은 인기 상품을 구매하는 독립 주문, `contention_normal_probe_duration`의 p95는 다른 상품을 구매하는 독립 주문의 지연이다. 기본값에서는 각각 40건이므로 반복 측정하거나 같은 재고 예산 안에서 `-Seconds`를 늘려 표본을 확보한다.
- `hikari.csv`의 `hikaricp_connections_pending`은 DB 커넥션을 빌리려고 기다리는 스레드 수다. 스크립트는 실제 k6 도착 구간의 표본만 Prometheus에서 읽어 풀별 관측 최댓값을 출력한다. 같은 시각의 `active` 및 `max`와 비교한다. 인기 상품 p95만 상승하면 상품 잠금 대기를 살펴보고, 다른 상품 p95와 풀 대기까지 함께 증가하면 다른 주문으로 지연이 퍼지는지 확인한다. 수집 간격은 5초이므로 짧은 피크가 표본 사이에서 지나갈 수 있다. Outbox 워커도 같은 풀을 사용한다. [수집 설정](../observability/prometheus.yml)과 [실행 스크립트](../scripts/k6/run-order-duplicate-contention.ps1)

상품 잠금에서 실제로 대기 중인 세션을 보려면 부하 중 별도 PowerShell 창에서 아래 읽기 전용 쿼리를 실행한다. 실험 프로젝트 이름이 다르면 `-p` 값을 맞춘다. 결과는 실행 시점의 표본이며, `blocking_pids`는 대기를 유발한 세션을 가리킨다.

```powershell
docker compose -p marketengine-catalog-10m exec -T postgres psql -U marketengine -d marketengine -c "SELECT pid, wait_event, pg_blocking_pids(pid) AS blocking_pids FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ~* 'update[[:space:]]+products[[:space:]]+set'"
```

#### 2026-11-10 실측 결과

1,000만 상품 실험 DB에서 22:50 KST에 기준 조건과 중복 조건을 각각 20초 동안 한 번씩 실행했다. 두 실행은 같은 대상을 같은 순서로 사용했다. 인기 상품 10개에 독립 주문 40건, 그 밖의 상품 40개에 독립 주문 40건, 인기 상품에 동시 주문 160쌍으로 조건별 POST 400건을 보냈다. 시작 재고 확인 GET은 조건별 50건이다.

- **중복 쌍 0%.** 신규 주문 400건. 인기 상품에 보낸 독립 주문의 p95는 7.64ms, 그 밖의 상품은 5.30ms였다.
- **중복 쌍 50%.** 신규 주문 320건. 인기 상품 독립 주문 p95는 6.82ms, 그 밖의 상품은 6.17ms였다. 전체 POST의 20%에 해당하는 중복 전송 80건 모두 백엔드 로그에서 서로 다른 키의 멱등성 인덱스 충돌로 확인됐다.
- **정합성과 커넥션 풀.** 두 실행 모두 HTTP 오류, 누락 반복, 품절, 주문 ID 불일치가 0건이었다. PostgreSQL에서 본 측정의 주문 720건과 50개 상품의 재고 720개 차감을 확인했고, 상품별 불일치는 0건이었다. HikariCP 대기 스레드는 각 실행의 5초 간격 표본 4개에서 모두 0이었다.

20 POST/s에서 같은 키의 쓰기 경합은 재현됐다. 일반 상품의 p95 차이는 약 0.86ms였고 두 조건은 순차로 한 번씩 실행했다. 이 결과만으로 일반 주문 지연이 커넥션 풀을 통해 전파됐다고 판단하거나 부하 한계를 정하기는 어렵다.

원본은 로컬 결과 폴더 `k6-order-contention-20261110-225005-050-39832-pct0`과 `k6-order-contention-20261110-225026-347-39832-pct50`에 있다. 앞선 예비 실행에서는 도착 구간 끝에 반복이 한 번씩 더 시작돼 POST와 주문이 403건 발생했다. 주문 전송 수를 스크립트에서 제한하고 대상 목록을 다시 내보낸 뒤 본 측정을 시작했다. 예비 실행분은 위 수치에서 제외했다.

변경 후에는 SKU 1,000개의 독립 DB에서 6 POST/s를 10초간 실행했다. POST 60건 중 중복은 10건이었고 주문 50건과 재고 차감 50개를 확인했다. 대상 SKU 15개의 불일치는 0건이었다. 시작 재고를 한 상품만 1개 바꾼 음성 대조에서는 같은 대사 SQL이 불일치 1건을 검출했다. 이 실행은 대사 절차의 동작을 검증한 결과다.

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

상품 커서는 동일 필터, 정렬, 크기와 검색 백엔드에만 유효하다. `after`를 보낼 때 page는 생략하거나 0으로 둔다. 커서 응답의 page는 0이며 실제 탐색 깊이는 호출자가 관리한다. ES는 `search_after`, PG는 정렬값과 ID를 비교한다. PIT 스냅샷을 유지하지 않는 실시간 탐색이므로 탐색 도중 상품의 정렬값이 변경되면 앞뒤 위치도 달라질 수 있다.

짧은 검수는 `scripts/verify/run-commerce-smoke.ps1 -K6 -Browser`로 별도 1,000건 환경에서 실행할 수 있다. 피드 캐시 ON/OFF, Redis와 ES 장애, 마지막 재고와 재입고, k6의 3초 feed/browse 및 페이지 경계, 실제 브라우저를 확인한다. `-UseLocalJar`는 미리 생성한 bootJar를 사용한다. 이 검수는 최대 처리량 측정이 아니다.

검수 종료 시 실행별 라벨을 확인한 컨테이너만 중지한다. 컨테이너와 볼륨을 자동 삭제하지 않으므로 반복 실행하면 검수 데이터가 남는다. 2026-11-11 이전 정리 코드에서 기존 1,000만 건 볼륨이 삭제된 사고와 미복구 상태는 [사고 기록](VERIFICATION_INCIDENT_2026-11-11.md)에 남겼다. 아래 기존 프로젝트 대상 명령은 해당 환경이 준비된 경우에 사용한다.

실제 환경의 SQL 누적 시간과 WAL, HOT/dead tuple, Redis 메모리, ES GC/큐와 백엔드 지표는 아래 한 줄로 파일에 저장한다. SQL 원문은 저장하지 않는다. 전후 스냅샷의 차이와 동일 시나리오의 p95/p99를 함께 본다.

```powershell
& .\scripts\verify\write-performance-snapshot.ps1 -Project marketengine-catalog-10m
```

빠른 sweep에서 발견한 구간만 유지해서 확인하려면 `-HoldSeconds 90`을 추가한다. 기존 기본값은 유지하고, 실행 기록에는 커밋, 미커밋 변경 여부, k6 이미지와 Compose 프로젝트도 남긴다. 생성기에서 VU를 늘리는 비용을 배제하려면 필요한 VU를 미리 할당한 뒤 도착률을 올린다. feed는 전체 최신순, TOP 최신순, 전체 인기순 세 URL이므로 결과는 인기 피드 전달 성능으로 해석한다.

```powershell
& .\scripts\k6\run-feed-capacity.ps1 -Sweep -TargetRps 15000 -RampSeconds 60 -HoldSeconds 90 -InitialVUs 2000 -MaxVUs 2000
```

매 실행에 코드 버전, DB 또는 색인 규모, 시작 재고나 캐시 예열 상태, 목표 도착률, 실제 완료 요청 수, `dropped_iterations`, p95/p99, 오류 코드별 건수를 함께 기록한다. 짧은 기본 실행의 p99는 표본 수와 함께 읽는다.

검색은 같은 요청에서 캐시 설정만 바꾸고, 연속 탐색은 같은 조건의 페이지 깊이별 지연을 확인한다. 주문은 `normal`, `mixed`, `control` 세 경우의 **일반 주문 흐름**을 비교한다. 혼합 조건에서만 지연이 커지면 같은 시간대의 Hikari 대기 연결 수와 PostgreSQL 잠금 대기를 확인한다. 처리율을 못 맞춰 `dropped_iterations`가 증가했다면 실제로 전달된 부하부터 대조한다. 개선 전후에는 원인 후보 한 가지를 변경하고 같은 시나리오를 다시 실행한다.

## 2026-11-11: 인기 피드 15,000 RPS 상승 테스트

재생성한 B9 상품 1,000만 건과 ES 1,000만 문서를 사용했다. Docker Desktop을 시작하고 백엔드, PostgreSQL, ES와 Redis를 기동한 뒤 50 RPS로 10초 예열했다. 본 실행은 0~10,000 RPS 30초와 10,000~15,000 RPS 30초이며 유지 구간은 없다. VU 2,000개를 사전 할당하고 상한도 2,000으로 고정했다. k6와 서비스는 같은 장비에 있으며 `Direct` 경로로 `http://backend:8080`을 호출했다.

```powershell
& .\scripts\k6\run-feed-capacity.ps1 -Sweep -TargetRps 15000 -RampSeconds 60 -HoldSeconds 0 -InitialVUs 2000 -MaxVUs 2000 -ElasticsearchUrl http://localhost:19200
```

ES 외부 포트가 변경된 환경에서는 `-ElasticsearchUrl`로 사전 확인 주소를 지정한다. 이 옵션은 부하 대상 API 주소를 바꾸지 않는다. 실행 메타데이터는 커밋 `925df4e`, 미커밋 변경 있음으로 기록됐다. 해당 변경은 이 ES 주소 옵션이며 검색 시나리오의 요청 구성은 그대로다.

**본 실행 결과:** HTTP 요청 524,561건, HTTP 실패 0건, 응답 내용 검사 실패 0건, 시작하지 못한 요청(`dropped_iterations`) 1건이다. 전체 응답 시간 평균은 5.43ms, p95는 24.43ms, p99는 50.73ms, 최대는 354.59ms였다. 예열의 499건과 미실행 2건은 이 결과에 포함하지 않는다.

| 요청 시작 시 목표 구간 | 표본 수 | p95 | p99 | HTTP 실패 |
|---|---:|---:|---:|---:|
| 9,000~10,000 RPS | 28,480 | 7.72ms | 11.09ms | 0 |
| 10,000~11,000 RPS | 62,974 | 8.24ms | 15.64ms | 0 |
| 11,000~12,000 RPS | 68,946 | 19.83ms | 57.69ms | 0 |
| 12,000~13,000 RPS | 74,898 | 21.71ms | 44.08ms | 0 |
| 13,000~14,000 RPS | 81,092 | 36.12ms | 61.98ms | 0 |
| 14,000~15,000 RPS | 86,924 | 35.62ms | 72.66ms | 0 |

첫 인접 구간 대비 큰 p99 상승은 11,000~12,000 RPS에서 관측됐다. 모든 구간의 p99는 200ms 이내였지만, 마지막 구간에서 활성 VU가 순간적으로 2,000개에 도달해 경고와 미실행 1건이 발생했다. 마지막 구간의 계획 평균은 14,500 RPS이고, 시작 구간으로 환산한 관측값은 14,487.33 RPS다. 15,000 RPS를 지속 유지할 수 있다고 입증한 실행은 아니다. 이론적 램프 적분량 525,000건과 실제 요청 수의 차이를 모두 드롭으로 계산하지 않는다.

예열을 포함한 525,060개 요청에서 로컬 캐시 즉시 적중은 523,895회, 로컬 캐시 미적중은 1,165회였다. 같은 키의 로딩을 합친 결과 실제 Redis 조회는 108회, 원본 검색은 6회였다. 캐시 오류, 검색 오류와 동시성 제한에 의한 거부는 모두 0이었다. 이 결과는 홈 최신순 60%, TOP 최신순 30%, 홈 인기순 10%의 세 가지 첫 페이지를 반복하는 캐시 응답 성능이다.

약 5초 간격 Docker 표본에서 백엔드 CPU 최대는 401%, 본 실행 k6 최대는 962%였다. 최고값의 관측 시각은 서로 다르다. 연결 준비 대기와 iteration의 최대 시간도 약 1.04초로 HTTP 응답 시간보다 길었다. 두 프로세스가 같은 장비의 CPU를 사용하므로 지연 증가를 백엔드 하나의 한계로 단정하지 않는다. 실행 후 백엔드 상태는 UP이었다. 다음 지속 부하 검증을 한다면 11,000~15,000 RPS 구간을 고정해서 별도로 확인한다.

원본 결과는 `C:\Users\chlwl\AppData\Local\Temp\k6-feed-20261111-123419-264-8100-Direct-sweep`의 `settings.json`, `feed-sweep.json`, `feed-sweep.csv`, `feed-sweep-report.txt`, `feed-sweep.log`에 있다. 전후 지표와 컨테이너 표본은 `C:\Users\chlwl\AppData\Local\Temp\k6-15000-observations-20261111-123321`에 저장했다.
