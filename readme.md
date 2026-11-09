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

### 2. Redis 다중 캐싱을 통한 검색 부하 분산

- 
    
    `OffsetDateTime` JSON 직렬화 에러를 `JavaTimeModule` JSR-310 지원 설정을 통해 해결했습니다.
    
- 빈도가 높은 검색 필터 조합을 식별하여 Redis에 캐싱 처리하고 캐시 적중률 95%를 달성했습니다.
- k6 부하 테스트 결과 동시 접속자 3만 명 수준인 최대 6,350 RPS를 기록했습니다.
- 5000 RPS 구간에서도 p99 지연율 약 200ms의 안정적인 처리량을 확보했습니다.

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

빈 DB의 최초 기동 시 Flyway가 [V8 초기화 SQL](backend/src/main/resources/db/migration/V8__initialize_commerce_catalog.sql) 하나로 스키마와 카탈로그를 생성합니다. 기본 구성은 상품 1,000만 개, 회원 50명, 예시 주문 2,000건입니다. 주문은 같은 카탈로그의 상품 ID와 단가를 참조합니다. 시드 재고는 기준 시점의 가용 수량입니다.

- 한 모델은 브랜드·품목·가격이 같은 색상별 SKU를 최대 4개 가집니다. 상품명에는 브랜드, 품목, 모델 코드와 색상이 들어갑니다. 상품이 13개 이상인 카탈로그에서 `ME0000001`은 4개, `StudioCapsule`은 13개, `ME0000000`은 0개가 기대값입니다.
- 브랜드별 상품 비중과 주력 카테고리, 품목별 가격대를 다르게 정의했습니다. 이 분포는 실험용 가정입니다. 생성일은 기준 시점 이전 365일에 걸쳐 배정하고 수정일은 생성일과 기준 시점 사이에 둡니다. 이 데이터에서 `ACTIVE` 상품은 가용 재고가 양수이며 품절·판매중단 상품의 가용 재고는 0입니다.
- 새 DB에 사용할 수량은 `CATALOG_PRODUCT_COUNT`(1~10,000,000), 기준 시각은 `CATALOG_AS_OF`(기본값은 적재 트랜잭션 시작 시각)로 정합니다. 예를 들어 PowerShell에서 아래와 같이 지정한 뒤 해당 셸에서 기동하면 됩니다. 같은 PostgreSQL 16, 생성 규칙, 기준 시각을 쓰면 수량을 늘려도 기존 SKU의 속성을 재현할 수 있습니다.

```powershell
$env:CATALOG_PRODUCT_COUNT = '100000'
$env:CATALOG_AS_OF = '2026-01-01T00:00:00Z'
```

### 기존 개발 환경에서 V8로 전환

V8은 새 DB에서 시작하는 초기 버전입니다. 이전 Flyway 이력이 있는 DB는 V8 백엔드에 직접 연결할 수 없습니다. Compose의 새 기본 프로젝트 이름은 `marketengine-catalog`입니다. 이전 프로젝트가 같은 포트를 점유하고 있으면 처음 한 번 아래 순서로 전환합니다. 첫 명령은 기존 컨테이너만 중지하므로 이전 데이터 볼륨은 보관됩니다. 다른 프로젝트 이름을 사용했다면 `backend-engineering` 자리에 실제 이름을 사용하세요.

```powershell
docker compose -p backend-engineering stop
$env:COMPOSE_PROJECT_NAME = 'marketengine-catalog'
docker compose up -d --build
docker compose logs -f backend
```

백엔드 기동 완료를 확인하면 `Ctrl+C`로 로그 보기만 종료합니다. 이전 버전에서 로컬 Gradle 빌드를 사용했다면 프로젝트 루트에서 `.\backend\gradlew.bat -p backend clean`으로 예전 SQL 빌드 복사본을 정리한 뒤, 아래 재색인 스크립트로 새 ES 인덱스를 채웁니다. 이전 DB에서 내보낸 `order-targets.json`도 새 DB에서 다시 추출합니다. 상품 수와 기준 시각은 최초 기동 전에 정합니다. 같은 볼륨을 재기동하면 Flyway는 V8 시드를 다시 실행하지 않습니다.

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

출력 파일은 `scripts/k6/order-targets.json`입니다. 각 항목의 `productId`, `initialStock`, `unitPrice`(소수 문자열)는 추출 시점의 값입니다. 적합한 상품이 부족하면 실제 개수를 기록하고, 후보가 없으면 기존 파일을 보존하고 종료합니다. 기존 주문 k6 및 재고 확인 SQL의 기본 ID는 아직 예전 fixture 범위를 가리킵니다. 해당 스크립트의 입력 전환은 후속 단계입니다.

개인 블로그에 모든 과정을 기록했습니다.

[chlwlsguszz.tistory.com/category/백엔드_엔지니어링_일지](https://chlwlsguszz.tistory.com/category/%EB%B0%B1%EC%97%94%EB%93%9C%20%EC%97%94%EC%A7%80%EB%8B%88%EC%96%B4%EB%A7%81%20%EC%9D%BC%EC%A7%80)
