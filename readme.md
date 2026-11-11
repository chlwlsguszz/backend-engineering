# Market Backend Engine

Java 21과 Spring Boot 기반의 단일 SKU 주문 및 상품 검색 포트폴리오다. PostgreSQL 16이 주문과 재고의 원본이고 Elasticsearch 8.17은 검색용 복제본이다. Next.js 화면에서 검색, 주문, 재시도를 검증하고 k6로 부하를 생성한다. [프로젝트 계획과 검증 현황](docs/PLAN.md)

[포트폴리오 읽기](docs/PORTFOLIO.md) / [제출용 PDF](output/pdf/market-engine-portfolio.pdf)

ES 검색은 `나이키 검정 후드`, `뉴발란스 러닝화` 같은 카탈로그 별칭과 긴 영문 단어의 제한적인 오타를 처리한다. [검색 규칙과 품질 평가 실행 방법](docs/K6_SCENARIOS.md#검색-품질-회귀-평가)

## 구현과 측정 결과

- **주문과 재고.** 주문 생성은 회원과 멱등성 키를 확인한 뒤 같은 트랜잭션에서 재고를 조건부 차감하고 단가를 받아 주문을 저장한다. 검색용 Outbox는 품절과 재입고 전환에서 기록한다. 수량 수정은 점유량 차이만 반영하고 취소는 한 번만 재고를 복원한다. 최초 요청 수량을 보존해 수정 또는 취소 후에도 동일한 POST를 재시도할 수 있다. 주문 락 대기는 기본 500ms로 제한한다. [주문 실행](backend/src/main/java/com/marketengine/backend/order/application/OrderCreateExecutor.java)
- **중복 주문 실험.** 이전 쓰기 경로에서 20 POST/s로 조건별 20초 동안 측정했다. 중복 전송 80건을 포함한 400 POST에서 PostgreSQL에 신규 주문 320건만 남았다. 이는 특정 부하의 정합성 결과이며 최대 처리량은 별도로 측정한다. [원본 조건과 p95](docs/K6_SCENARIOS.md#2026-11-10-실측-결과)
- **상품 검색과 캐시.** 기본 ES 및 선택 가능한 PostgreSQL 검색을 제공한다. 최신순과 인기순 모두 ID로 동점을 해소하며 `after` 커서로 이어 읽는다. 기존 page 방식의 offset 상한은 10,000건이다. 크기 12의 첫 세 page 요청은 Caffeine 2초와 Redis 30~60초 캐시를 사용하고 동일 키의 동시 요청을 합친다. 커서 요청은 캐시를 우회한다. 목록은 판매 가능 여부, 상세는 정확한 DB 재고를 제공한다. [검색 실험](docs/K6_SCENARIOS.md)
- **1,000만 SKU 재색인.** V8 카탈로그에서 워커 8개, 배치 5,000개로 재생성한 인덱스의 1,000만 건을 검증했다. 재색인 벽시계 시간은 5분 14.815초, Flyway를 포함한 Gradle 전체 시간은 8분 27초였다. 검색 RPS, SQL 단건 지연, 재색인 시간은 별도 지표로 관리한다. [실험 이력](docs/IMPROVEMENT_LOG.md)
- **재현.** CI는 Gradle 테스트와 PostgreSQL/ES 컨테이너 실행 여부, 프론트 lint/build 및 실제 브라우저 주문을 검사한다. Grafana의 주문 HTTP 200 요청률에는 재시도가 포함된다. 신규 주문 처리량과 재고는 k6 실행 키로 PostgreSQL에서 대조한다. [CI](.github/workflows/ci.yml)와 [시나리오](docs/K6_SCENARIOS.md)

## API 및 데모 운영

로컬 데모에는 인증이나 결제 연동이 없다. 화면의 회원 1번은 시드된 가상 회원이다. 회원 API는 이메일과 이름만 다루며 비밀번호 입력을 사용하지 않는다. Compose의 공개 포트는 루프백에 바인딩한다. 외부 호스팅은 별도 소유자/관리자 권한과 자격 증명 설정이 전제다.

- 회원 및 주문 목록은 `?size=20&beforeId=<nextCursor>`를 사용한다. 응답의 `data`는 `items`, `nextCursor`, `hasNext`이고 마지막 페이지의 커서는 null이다. 과거 배열 응답을 쓰는 클라이언트는 `data.items`로 이관한다.
- 상품 목록은 첫 응답의 `nextCursor`를 다음 요청의 `after`로 전달한다. 필터, 정렬, 크기를 유지하고 page는 생략한다. 필터 또는 검색 엔진이 바뀌면 첫 페이지부터 시작한다. 목록의 `stockQuantity`는 `available`로 변경되었으며 상세 응답의 수량은 유지된다.
- `DELETE /api/orders/{id}`는 취소다. 주문 이력과 키가 유지되고 재취소는 성공한다. 확정 또는 생성 상태에서 수량을 조정할 수 있으며 취소 상태는 최종 상태다. 기존 주문의 최초 수량 기록이 없었던 경우 V11은 업그레이드 당시 수량을 재시도 비교 기준으로 사용한다.
- 일반 개발 환경은 빈 DB에서 B9 상품 1,000개를 만든다. 10만 건 및 1,000만 건은 아래 명령으로 명시한다. 상품과 회원이 없는 새 환경은 `SPRING_PROFILES_ACTIVE=empty`를 지정한다. 이 환경에서 Compose로 재색인을 직접 실행하면 프로필을 `--spring.profiles.active=empty,reindex`로 전달한다. 기존 볼륨의 환경 변수를 바꿔도 시드는 다시 생성되지 않는다.
- 기본 Compose는 백엔드 기동 전에 PostgreSQL과 ES가 준비될 때까지 기다린다. 기동 후 ES가 중단돼도 주문은 DB에 커밋되고 Outbox에서 반영을 재시도한다. Redis 장애 중에는 피드가 원본 검색으로 우회하므로 해당 기간의 검색 부하는 ES 또는 PG에서 처리한다.
- 기본 ES 힙은 2GB, 캐시 전용 Redis 상한은 512MB다. Redis 장애는 Circuit Breaker로 차단하고 원본 검색 동시 실행을 32개로 제한한다. 포화 시 503으로 재시도를 안내한다. 이전 ES 문서 형식도 읽을 수 있으며 정렬 최적화는 새로 만든 인덱스부터 적용된다.

2026-11-11 로컬 검수 정리 중 기존 1,000만 건 DB와 ES 볼륨이 삭제됐다. 이후 사용자 승인으로 같은 B9 시드의 초기 1,000만 상품과 ES 색인을 재생성하고 정합성을 검증했다. 시드 이후 추가됐던 주문과 수정 이력은 복구되지 않았다. 자동 볼륨 삭제는 제거했다. [사고 영향과 재생성 결과](docs/VERIFICATION_INCIDENT_2026-11-11.md)


## 로컬에서 검색과 주문 실행

새 개발 DB에서 처음 실행할 때 저장소 루트의 PowerShell에서 아래 명령을 한 줄씩 실행한다. 첫 줄은 DB와 ES를 준비하고, 두 번째 줄은 상품을 생성해 최초 검색 인덱스를 채운다. 세 번째 줄로 구매 화면을 시작한다.

```powershell
docker compose up -d --wait postgres elasticsearch
docker compose run --build --rm --no-deps backend --spring.profiles.active=reindex --recreate-index
docker compose up -d --build frontend
```

이후 같은 볼륨을 사용할 때는 세 번째 명령으로 시작한다. 주문과 상품 변경은 Outbox가 ES에 반영한다. [구매 화면](http://localhost:5173) / [API 문서](http://localhost:8080/swagger-ui.html)

ES 외부 포트는 `.env`의 `ES_HOST_PORT`로 지정하며 기본값은 9200이다. Windows 예약 포트와 충돌하면 사용 가능한 다른 포트를 지정한다. 2026-11-11 이 장비에서는 9200이 예약 범위에 포함돼 19200을 사용했다. 호스트에서 실행하는 ES 조회 명령과 도구의 URL도 해당 포트에 맞춰야 한다. 컨테이너 내부 ES 주소는 `http://elasticsearch:9200`으로 유지된다.

별도 임시 환경에서 재색인, 캐시 적중과 장애 우회, 주문 후 ES 복구를 함께 검증하는 PowerShell 명령은 `.\scripts\verify\run-commerce-smoke.ps1`이다. 실제 브라우저 검사는 [프론트 실행 절차](frontend/README.md)를 따른다.

## 검색과 주문의 공용 더미 데이터

대용량 프로필의 실험 규모는 **SKU 10,000,000개, 모델 2,500,000개, 회원 100,000명**입니다. 주문 수는 수요와 가용 재고에 따라 결정됩니다. [B9 카탈로그 생성 SQL](backend/src/main/resources/db/migration/B9__realistic_commerce_catalog.sql)

### 1,000만 건 환경

PowerShell에서 파일럿에 사용했던 설정을 아래 값으로 바꿉니다. 파일럿이 실행 중이면 `docker compose -p marketengine-catalog-pilot stop`으로 포트를 비웁니다. 검증한 시드와 기준 시각은 두 환경에서 같습니다.

```powershell
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-10m'
$env:CATALOG_PRODUCT_COUNT = '10000000'
$env:CATALOG_SEED = '42'
$env:CATALOG_AS_OF = '2026-11-01T00:00:00Z'
docker compose up -d --build backend
docker compose logs -f backend
```

빈 프로젝트 볼륨에서 처음 실행하면 B9가 1,000만 건을 생성합니다. 이미 생성한 환경은 기존 데이터를 사용합니다. 백엔드 시작이 완료된 뒤 같은 PowerShell 창에서 `.\scripts\sql\run-check-catalog-distribution.ps1`을 실행하면 DB의 실제 건수와 재고를 확인할 수 있습니다.

Windows에서 Buildx가 `docker-credential-desktop`을 찾지 못하면 Docker 설치 폴더와 그 앞의 시스템 `Path` 항목을 확인합니다. 이 프로젝트의 개발 장비에서는 k6 경로 끝의 따옴표가 원인이었고, 환경 변수 편집 후에도 따옴표 한 글자인 항목과 끝에 따옴표가 붙은 Docker 경로가 남았습니다. 이 두 형태는 프로젝트 루트의 PowerShell에서 `.\scripts\windows\repair-docker-path.ps1`로 복구할 수 있습니다. 필요한 경우 Windows 관리자 승인을 요청하며, 현재 터미널에도 교정한 경로를 적용한 뒤 `node:22-alpine` 레지스트리 조회를 검증합니다.

### 10만 건 파일럿 생성

Windows PowerShell에서 프로젝트 루트를 기준으로 실행합니다. 1,000만 건 환경이 실행 중이면 먼저 `docker compose -p marketengine-catalog-10m stop`으로 포트를 비웁니다. 아래 프로젝트 이름을 처음 사용할 때 별도 볼륨이 생성됩니다.

```powershell
docker compose -p marketengine-catalog stop
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog-pilot'
$env:CATALOG_PRODUCT_COUNT = '100000'
$env:CATALOG_SEED = '42'
$env:CATALOG_AS_OF = '2026-11-01T00:00:00Z'
docker compose up -d --build backend
docker compose logs -f backend
```

`Started BackendApplication`을 확인하면 `Ctrl+C`로 로그 보기를 끝냅니다. 같은 창에서 **상품 수정이나 주문 생성 전에** 스냅샷을 검증합니다.

```powershell
.\scripts\sql\run-check-catalog-distribution.ps1
```

[읽기 전용 검증 SQL](scripts/sql/check_catalog_distribution.sql)은 하나의 DB 스냅샷에서 SKU 수, 브랜드군과 카테고리별 색상 비율, 라인업 크기, 모델별 옵션 수, 출시 월, 수요 구간별 실제 판매량을 출력합니다. 수량, 날짜, 주문 금액, 중복 색상, 라인업 소유 브랜드 또는 재고 대사에 오류가 있으면 비정상 종료합니다. 성공 메시지는 `Catalog seed checks passed.`입니다. 이 단계는 PostgreSQL에서 완료할 수 있습니다.

### 데이터 생성 규칙

- **상품과 라인업.** 대형, 중형, 소형 브랜드의 비중과 브랜드별 주력 카테고리를 다르게 정했습니다. 상시 라인업은 여러 모델이 공유하고, 한정 드롭과 도시 컬렉션은 더 적은 모델이 공유합니다. 모델마다 색상 옵션을 1~7개 배정합니다. 품목과 라인업에 따라 다른 가중치로 중복 없이 뽑아 기본 의류와 트레일 라인의 색상 분포가 달라집니다.
- **날짜.** 모델 출시일은 기준 시각 전 365일에 분포합니다. 시즌 라인은 특정 주에 집중되며 같은 한정 컬렉션은 출시 주를 공유합니다. 회원 가입과 상품 출시 후에 주문을 생성합니다.
- **수요와 재고.** 모델과 옵션의 수요 점수(1~100)가 구매 확률, 주문 수량, 인기순 점수에 반영됩니다. 일반 상품은 한정판보다 기초 가용 재고가 많고 인기 일반 상품은 입고 확률도 높습니다. 재고를 먼저 정하고 가능한 수량만 주문에 배정합니다. 각 SKU에서 `기초 가용 재고 + 누적 입고 − CREATED 및 CONFIRMED 주문 수량 = 현재 가용 재고`가 성립해야 합니다. 취소 주문의 순차감은 0입니다. 입고량은 시드 기간의 누적 공급으로 취급하며 개별 입고 시각은 모델링하지 않습니다.
- **재현.** PostgreSQL 16에서 같은 생성기 버전, 시드, 기준 시각과 건수를 지정하면 같은 데이터가 생성됩니다. 이 값은 `catalog_seed_run`, SKU별 기초 재고와 입고량은 `catalog_seed_inventory`에서 확인합니다. 분포는 실험을 위한 가정입니다. DTO와 검색 API에는 기존 상품 필드를 사용합니다.
- **규모 확대.** `CATALOG_PRODUCT_COUNT`는 1~10,000,000입니다. 10만 건 결과를 검토한 뒤 1천만 건은 별도의 빈 Compose 프로젝트에서 생성합니다. 이미 사용한 볼륨의 환경 변수만 변경해도 Flyway는 시드를 다시 실행하지 않습니다. 비교에 사용하는 ES와 Redis도 해당 프로젝트의 것을 사용합니다.

### 확인용 사례

10만 건과 1,000만 건 환경 모두 상품명 토큰 기준 `Drop000001`은 32건, `City000001`은 128건, `ME0000001`은 4건, `ME0000000`은 0건이 기대값입니다. `StudioCapsule`은 첫 13개 SKU의 컬렉션으로, 페이지 크기 12의 경계 검증에 사용합니다. SKU 1은 재고 120개, SKU 2는 1개, SKU 3은 0개입니다. SKU 1에는 취소 주문도 있어 재고 대사에서 취소 수량을 잘못 차감하는 경우를 잡을 수 있습니다.

### Elasticsearch 재색인 비교

[재색인 스크립트](scripts/elasticsearch/run-reindex-products.ps1)는 PostgreSQL `products`의 모든 상품을 ID 순으로 읽어 Elasticsearch의 `products` 인덱스에 같은 ID로 저장합니다. `-RecreateIndex`는 검색 API가 사용하는 해당 인덱스를 삭제하고 매핑부터 다시 만듭니다. 비교에는 검색 트래픽이 없는 환경을 사용하고, 마지막 검증까지 상품 등록과 수정, 삭제, 주문에 의한 재고 변경을 멈춰 원본을 고정합니다.

상품 등록, 수정, 삭제와 주문 재고 변경은 Outbox를 통해 검색에 반영됩니다. 오프라인 재색인 전에는 쓰기 요청을 중지하고 Outbox 처리와 ES로 이미 전송한 Bulk 요청이 끝날 때까지 기다린 뒤 백엔드를 정지합니다. 재색인은 상품과 Outbox의 쓰기 잠금을 즉시 얻을 수 없거나 처리 중인 임대가 남아 있으면 중단됩니다. 중단된 워커의 임대가 남았다면 백엔드를 다시 시작해 해당 작업이 처리된 뒤 재시도합니다. `reindex` 프로필 자체는 Outbox 워커를 실행하지 않습니다. [상품 변경의 검색 반영](docs/IMPROVEMENT_LOG.md#상품-변경의-검색-반영)

아래 PowerShell 명령은 배치당 5,000건으로 고정하고 작업자 수만 바꿉니다. `0`은 DB 읽기와 ES 쓰기를 순차로 실행하는 기준값입니다. `1`은 다음 배치를 읽는 동안 직전 배치를 ES에 쓰며, `2`, `4`, `8`은 동시에 ES에 쓰는 작업자를 늘립니다. 인수를 생략하면 Spring 설정을 사용하며 기본값은 작업자 1개와 배치당 5,000건입니다.

```powershell
foreach ($workers in 0, 1, 2, 4, 8) {
    .\scripts\elasticsearch\run-reindex-products.ps1 -RecreateIndex -Workers $workers -BatchSize 5000
    if ($LASTEXITCODE -ne 0) { throw "재색인 실패 (workers=$workers)" }
}
```

- **시간 비교** 같은 DB와 서버 자원으로 예열한 뒤 실행 순서를 바꾸어 반복합니다. 인덱스를 재생성한 뒤에도 PostgreSQL과 운영체제의 읽기 캐시가 남을 수 있습니다. 최초 실행과 예열 후 실행을 구분해 전체 경과 시간과 초당 문서 수를 기록합니다.
- **병목 확인** 재색인 로그의 DB 조회 및 행 매핑 누적 시간, ES bulk 요청 누적 시간, 마지막 refresh 시간, 전체 경과 시간을 비교합니다. 작업자들이 보낸 요청은 동시에 진행될 수 있어 누적 시간의 합은 전체 경과 시간을 초과할 수 있습니다. 작업자를 늘렸을 때 실제 완료 시간이 줄었는지는 동일 조건의 측정으로 판단합니다.
- **결과 확인** 재색인은 작업 동안 상품과 Outbox 쓰기를 잠그고 최종 refresh 후 삭제 표시를 제외한 문서 수를 원본과 대조합니다. 재생성한 인덱스의 건수가 다르면 작업이 실패합니다. 기존 인덱스의 추가 문서는 경고를 기록합니다. ID 범위에서 뽑은 최대 5개 상품의 가격, 재고, 날짜를 포함한 검색 필드도 자동으로 대조합니다.
- **중단 후 재실행** 실패 직전까지 저장된 배치가 대상 인덱스에 남을 수 있습니다. 원본을 고정한 상태에서 `-RecreateIndex`로 처음부터 다시 적재하고 검증합니다. 옵션을 생략하면 같은 ID의 문서를 갱신하며, 현재 DB에 없는 과거 문서는 인덱스에 남을 수 있습니다.

### 기존 상품에서 주문 대상 추출

기존 [주문 준비 스크립트](scripts/sql/run-seed-order-products.ps1)는 DB를 한 번 읽어 `ACTIVE`이며 재고가 있는 상품을 최대 1,000개 뽑습니다. 과거 주문 실험용 `OrderTestBrand`는 추출에서 제외합니다. 구간별 선택과 목록 정렬은 `-Seed`로 재현할 수 있으며, 같은 결과를 비교하려면 같은 DB 상태에서 추출해야 합니다.

```powershell
.\scripts\sql\run-seed-order-products.ps1 -Seed 42 -Count 1000
```

출력 파일은 `scripts/k6/order-targets.json`입니다. 버전 2에는 회원 범위(`1..memberCount`), 상품 ID, 추출 시점의 가용 재고, 단가, 인기 점수가 들어 있습니다. 이전에 내보낸 파일은 위 명령으로 다시 생성합니다. 기존 `order_spread_products.js`와 `order_hot_stock.js`의 기본 ID는 과거 카탈로그를 가리킵니다. 현재 카탈로그를 사용하는 검색과 주문 실험은 [상황별 k6 실행 가이드](docs/K6_SCENARIOS.md)를 따릅니다.

개인 블로그에 모든 과정을 기록했습니다.

[chlwlsguszz.tistory.com/category/백엔드_엔지니어링_일지](https://chlwlsguszz.tistory.com/category/%EB%B0%B1%EC%97%94%EB%93%9C%20%EC%97%94%EC%A7%80%EB%8B%88%EC%96%B4%EB%A7%81%20%EC%9D%BC%EC%A7%80)
