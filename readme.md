# 🚀 Market Backend Engine

## 📌 Project Overview

대용량 트래픽이 발생하는 e-commerce 환경을 가정하여, 1,000만 건 이상의 상품 데이터 검색 성능을 최적화하고 고동시성 환경에서의 주문 데이터 무결성을 보장하는 백엔드 시스템입니다. 단일 쿼리 튜닝부터 검색 엔진 마이그레이션, 비관적 락을 통한 동시성 제어까지 데이터 기반의 성능 개선을 목표로 개발되었습니다.

## 🛠 Tech Stack

| **Category** | **Technologies** |
| --- | --- |
| **Backend** | Java 21, Spring Boot 3.5.13, Gradle |
| **Database & Cache** | PostgreSQL 16, ElasticSearch, Redis |
| **Infra & DevOps** | Docker Compose, GitHub Actions, Flyway |
| **Test & Monitoring** | k6, Prometheus, Grafana, JUnit, Mockito |

## 🎯 Key Technical Achievements

### 1. 1,000만 건 대용량 데이터 검색 성능 최적화

- QueryDSL을 도입하여 복잡한 필터 검색 코드를 효율적으로 개선했습니다.
- 
    
    `totalCount` 연산 병목을 무한 스크롤 형태의 `Slice` 방식으로 해결하여 조회 속도를 1400ms에서 25ms로 단축했습니다.
    
- 
    
    `EXPLAIN ANALYZE`로 병목을 식별하고 B-tree 인덱스를 적용하여 최신순 검색을 1842ms에서 0.08ms로 개선했습니다.
    
- 
    
    `pg_trgm` 역색인 인덱스와 `word_similarity` 함수로 오타 및 유사도 검색 속도를 5000ms에서 0.8ms로 단축했습니다.
    
- RDBMS 환경의 힙 스캔 병목 한계 극복을 위해 ElasticSearch 마이그레이션을 단행했습니다.
- 기존 실험에서는 PostgreSQL에서 상품을 ID 순으로 나눠 읽고 Elasticsearch에 일괄 저장하여 1,000만 건을 28분에 초기 색인했습니다. 현재 구현은 같은 배치 크기에서 순차 처리와 병렬 처리 시간을 비교할 수 있습니다.

### 2. Redis 상품 피드 캐시

- 페이지 크기가 12이고 페이지 번호가 0~2이면 모든 필터 조합의 결과를 Redis에 저장합니다. 검색어 `Nike`와 `nike`는 같은 키를 공유합니다. 브랜드 필터는 정확히 일치하는 값을 검색하므로 대소문자를 구분합니다. 상품을 변경하면 각 피드는 만료 후 첫 조회에서 갱신됩니다.
- 기본 TTL은 저장할 때마다 30~60초 사이에서 무작위로 정합니다. 목록에 이전 가격이나 삭제된 상품이 잠시 보일 수 있습니다. 상품 상세는 PostgreSQL에서 조회하고 주문 가능 재고는 주문 트랜잭션에서 확인합니다.
- 피드 응답은 `JavaTimeModule`을 등록한 JSON 직렬화기로 저장해 `OffsetDateTime`을 보존합니다.
- 기존 측정 기록은 캐시 적중률 95%, 최대 6,350 RPS, 5,000 RPS에서 p99 약 200ms입니다. 새 키와 만료 정책의 적중률 및 지연은 같은 요청 분포에서 다시 측정해야 합니다. [k6 측정 방법](docs/K6_SCENARIOS.md#같은-피드로-캐시-효과-비교)

### 3. 고동시성 환경의 주문/결제 무결성 보장

- 
    
    `SELECT FOR UPDATE` 쿼리를 통한 비관적 락(Pessimistic Lock)으로 동시 접근에 의한 재고 감소 누락을 방지했습니다.
    
- 트랜잭션 대기로 인한 커넥션 풀 고갈을 식별하고 HikariCP 풀을 조정하여 전체 주문 최대 3100 TPS를 달성했습니다.
- UUID 기반의 멱등성 키와 회원 ID의 복합 유니크 인덱스를 설계하여 이중 결제를 차단했습니다.
- 독립 트랜잭션 분리(`@Transactional(propagation = Propagation.REQUIRES_NEW)`)를 통해 예외 처리 정합성을 확보했습니다.
- 중복 요청 부하 테스트에서도 2566 TPS를 안정적으로 달성했습니다.

### 4. 데이터 기반 모니터링 인프라 구축

- GitHub Actions를 통해 PR 및 Push 발생 시 병렬 빌드 및 테스트를 실행하는 자동화 파이프라인을 구축했습니다.
- Docker Compose로 백엔드, Next.js 프론트엔드, DB를 묶어 오케스트레이션 환경을 구성했습니다.
- Spring Boot Actuator, Prometheus, Grafana를 연동하여 JVM Heap, DB 커넥션, API 지연율 등을 실시간 추적했습니다.

## 🚀 Getting Started

**1. Clone the repository**

> `git clone https://github.com/사용자계정/리포지토리이름.git`
> 
> 
> `cd 리포지토리이름`
> 

**2. Run with Docker Compose**

> 
> 
> 
> `docker compose up --build` 
> 

**3. Access the Services**

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## 검색과 주문의 공용 더미 데이터

새 DB의 기본 실험 규모는 **SKU 10,000,000개, 모델 2,500,000개, 회원 100,000명**입니다. 주문 수는 수요와 가용 재고에 따라 결정됩니다. [B9 카탈로그 생성 SQL](backend/src/main/resources/db/migration/B9__realistic_commerce_catalog.sql)

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

아래 PowerShell 명령은 배치당 5,000건으로 고정하고 작업자 수만 바꿉니다. `0`은 DB 읽기와 ES 쓰기를 순차로 실행하는 기준값입니다. `1`은 다음 배치를 읽는 동안 직전 배치를 ES에 쓰며, `2`, `4`, `8`은 동시에 ES에 쓰는 작업자를 늘립니다. 인수를 생략하면 Spring 설정을 사용하며 기본값은 작업자 1개와 배치당 5,000건입니다.

```powershell
foreach ($workers in 0, 1, 2, 4, 8) {
    .\scripts\elasticsearch\run-reindex-products.ps1 -RecreateIndex -Workers $workers -BatchSize 5000
    if ($LASTEXITCODE -ne 0) { throw "재색인 실패 (workers=$workers)" }
}
```

- **시간 비교** 같은 DB와 서버 자원으로 예열한 뒤 실행 순서를 바꾸어 반복합니다. 인덱스를 재생성한 뒤에도 PostgreSQL과 운영체제의 읽기 캐시가 남을 수 있습니다. 최초 실행과 예열 후 실행을 구분해 전체 경과 시간과 초당 문서 수를 기록합니다.
- **병목 확인** 재색인 로그의 DB 조회 및 행 매핑 누적 시간, ES bulk 요청 누적 시간, 마지막 refresh 시간, 전체 경과 시간을 비교합니다. 작업자들이 보낸 요청은 동시에 진행될 수 있어 누적 시간의 합은 전체 경과 시간을 초과할 수 있습니다. 작업자를 늘렸을 때 실제 완료 시간이 줄었는지는 동일 조건의 측정으로 판단합니다.
- **결과 확인** 재색인은 원본 건수와 성공한 문서 수를 대조하고 최종 refresh 후 같은 ES 연결에서 문서 수(`targetDocuments`)를 읽습니다. 재생성한 인덱스의 건수가 다르면 작업이 실패합니다. 기존 인덱스에 추가 문서가 있으면 경고를 기록합니다. 내용 검증은 몇 개의 실제 상품 ID에 대해 `GET /products/_doc/{id}`의 상품명, 가격, 재고를 PostgreSQL과 대조합니다.
- **중단 후 재실행** 실패 직전까지 저장된 배치가 대상 인덱스에 남을 수 있습니다. 원본을 고정한 상태에서 `-RecreateIndex`로 처음부터 다시 적재하고 검증합니다. 옵션을 생략하면 같은 ID의 문서를 갱신하며, 현재 DB에 없는 과거 문서는 인덱스에 남을 수 있습니다.

### 기존 상품에서 주문 대상 추출

기존 [주문 준비 스크립트](scripts/sql/run-seed-order-products.ps1)는 DB를 한 번 읽어 `ACTIVE`이며 재고가 있는 상품을 최대 1,000개 뽑습니다. 과거 주문 실험용 `OrderTestBrand`는 추출에서 제외합니다. 구간별 선택과 목록 정렬은 `-Seed`로 재현할 수 있으며, 같은 결과를 비교하려면 같은 DB 상태에서 추출해야 합니다.

```powershell
.\scripts\sql\run-seed-order-products.ps1 -Seed 42 -Count 1000
```

출력 파일은 `scripts/k6/order-targets.json`입니다. 버전 2에는 회원 범위(`1..memberCount`), 상품 ID, 추출 시점의 가용 재고, 단가, 인기 점수가 들어 있습니다. 이전에 내보낸 파일은 위 명령으로 다시 생성합니다. 기존 `order_spread_products.js`와 `order_hot_stock.js`의 기본 ID는 과거 카탈로그를 가리킵니다. 현재 카탈로그를 사용하는 검색과 주문 실험은 [상황별 k6 실행 가이드](docs/K6_SCENARIOS.md)를 따릅니다.

개인 블로그에 모든 과정을 기록했습니다.

[chlwlsguszz.tistory.com/category/백엔드_엔지니어링_일지](https://chlwlsguszz.tistory.com/category/%EB%B0%B1%EC%97%94%EB%93%9C%20%EC%97%94%EC%A7%80%EB%8B%88%EC%96%B4%EB%A7%81%20%EC%9D%BC%EC%A7%80)
