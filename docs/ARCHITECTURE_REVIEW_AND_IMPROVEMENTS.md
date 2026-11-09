# 🔍 Market Backend Engine — 설계 결함, 과설계 및 성능/시나리오 개선 리포트

> **작성 목적**: 대용량 상품 검색(1,000만 건) 및 고동시성 주문/결제 시스템의 현재 구현 코드를 시니어 엔지니어/면접관 시각에서 정밀 진단하고, **설계적 결함(Design Flaws)**, **과설계(Over-engineering)**, **가상 데이터 및 부하 테스트(k6) 왜곡 요인**, **기술 스택 및 Elasticsearch 최적화 포인트**를 종합 정리한 문서입니다.

---

## 목차
1. [대용량 검색 및 캐시(Search & Cache) 영역의 설계 결함](#1-대용량-검색-및-캐시search--cache-영역의-설계-결함)
2. [고동시성 주문 및 멱등성(Order & Concurrency) 영역의 설계 결함](#2-고동시성-주문-및-멱등성order--concurrency-영역의-설계-결함)
3. [공통 및 인프라(Common & Infra) 영역의 숨은 성능 병목](#3-공통-및-인프라common--infra-영역의-숨은-성능-병목)
4. [가상 데이터(Seed Data) 및 k6 부하 테스트 시나리오의 비현실성](#4-가상-데이터seed-data-및-k6-부하-테스트-시나리오의-비현실성)
5. [기술 스택 타당성 및 보완 포인트](#5-기술-스택-타당성-및-보완-포인트)
6. [Elasticsearch 정렬(`doc_values` vs `Index Sorting`) 심층 분석](#6-elasticsearch-정렬doc_values-vs-index-sorting-심층-분석)
7. [우선순위별 개선 로드맵 (Action Items)](#7-우선순위별-개선-로드맵-action-items)

---

## 1. 대용량 검색 및 캐시(Search & Cache) 영역의 설계 결함

### 🚨 1.1 상품 1개만 변경해도 전체 캐시를 폭파하는 `@CacheEvict(allEntries = true)`
- **관련 코드**: `ProductService.create()`, `ProductService.update()`, `ProductService.delete()`
- **현상**:
  ```java
  @Transactional
  @CacheEvict(cacheNames = RedisCacheConfig.PRODUCT_LIST_FEED, allEntries = true)
  public ProductDetailResponse create(CreateProductRequest request) { ... }
  ```
- **문제점**:
  1. **Redis 싱글 스레드 블로킹**: Spring Data Redis에서 `allEntries = true`는 기본적으로 `KEYS productListFeed*` 명령어를 실행해 매칭되는 모든 키를 조회한 뒤 삭제합니다. 키 개수가 많아질수록 싱글 스레드인 Redis 전체가 멈추는 장애 원인이 됩니다.
  2. **Cache Avalanche(캐시 눈사태 / Stampede)**: 7,000 RPS 부하를 캐시 적중률 95%로 방어하는 도중 판매자가 상품 1개를 등록/수정하면, 모든 피드 캐시가 동시에 증발하여 수천 RPS의 트래픽이 일제히 Elasticsearch/PostgreSQL로 쏟아집니다.
- **개선 방안**:
  - 상품 목록(피드)은 강한 실시간 정합성이 요구되지 않으므로 `@CacheEvict(allEntries = true)`를 제거하고, **짧은 TTL(예: 10~60초) + Jitter(만료 시간 무작위 분산)**로 자연 만료되게 설계합니다.
  - 필요시 **L1 로컬 캐시(Caffeine, TTL 3~5초) + L2 글로벌 캐시(Redis, TTL 1~5분)**의 2계층 캐시 구조로 전환합니다.

---

### 🤔 1.2 [과설계 & 벤치마크 맞춤형 하드코딩] `ProductListFeedKeyResolver`
- **관련 코드**: `ProductListFeedKeyResolver.resolve()`
- **현상**:
  - 약 200줄에 달하는 `if` 분기문으로 **특정 11가지 필터 조합만 캐시 키를 생성**하고, 나머지 조합은 `Optional.empty()`를 반환하여 캐시를 타지 못하게 막아두었습니다.
- **문제점**:
  1. **테스트 스크립트(`search_v3.js`)와의 과적합(Overfitting)**: 허용된 11가지 조합이 `search_v3.js`의 `COMPLEX_PROFILES`와 1:1로 일치합니다. 반면 실제 유저가 자주 쓸 법한 `category + minPrice`(키워드 없음)나 `brand + color` 조합은 캐시가 전혀 적용되지 않습니다.
  2. **키워드 정규화 누락**: `segment(keyword)`가 단순 `trim()`만 수행하고 대소문자 변환(`toLowerCase()`)이나 다중 공백 제거를 하지 않아, `"Nike"`와 `"nike"`가 ES에서는 동일한 검색임에도 Redis에는 별도 키로 중복 적재됩니다.
- **개선 방안**:
  - `size == 12 && page <= 2` 조건만 유지하되, 복잡한 분기문을 제거하고 모든 필터 파라미터를 정규화한 **단일 표준 키 포맷**으로 통합합니다.
  - 예시: `feed:v1:cat=%s:brand=%s:gen=%s:col=%s:min=%s:max=%s:kw=%s:sort=%s:p=%d`

---

### 🐛 1.3 오타 검색(`kw-typo`) 기능 누락 및 빈 인덱스 500 에러
- **관련 코드**: `ElasticsearchProductListSearcher.appendKeywordMust()`, `ProductDocument.id`
- **문제점**:
  1. **오타 교정(`fuzziness`) 누락**: PostgreSQL(`pg_trgm`) 단계에서 테스트하던 오타 키워드(`"Nikke"`, `"Nkie"`, `"Sneeker"`)가 `search_v3.js`의 `kw-typo` 시나리오에 남아있으나, 정작 ES 검색 코드(`appendKeywordMust`)는 `fuzziness("AUTO")` 없이 단순 `match` 쿼리만 수행합니다. 그 결과 현재 `kw-typo` 요청은 전부 **검색 결과 0건(`items: []`)**을 반환하고 있습니다. (k6가 `Array.isArray(items)`만 체크하여 성공으로 집계됨)
  2. **빈 인덱스 정렬 시 500 에러**: `ProductDocument`의 `@Id private Long id;` 필드에 `@Field(type = FieldType.Long)` 어노테이션이 빠져 있어, 문서가 0건인 초기 인덱스에서는 `id` 매핑이 생성되지 않아 정렬(`id DESC`) 시 `[search_phase_execution_exception] all shards failed` 500 에러가 발생합니다.

---

### 🔄 1.4 DB 트랜잭션 내 ES 동기 호출 및 주문 재고 미반영
- **관련 코드**: `ProductService.create/update/delete()`, `OrderCreateExecutor.execute()`
- **문제점**:
  1. **DB 트랜잭션과 외부 네트워크 I/O 결합**: `@Transactional` 내부에서 `productSearchIndexer.index(saved)`를 동기 호출합니다. ES 응답이 지연되면 DB 커넥션 풀(HikariCP)이 고갈되며, ES 저장 후 DB 트랜잭션이 롤백되면 데이터 불일치가 발생합니다.
  2. **주문 시 검색 엔진 재고 미갱신**: 주문 생성 시 `product.decreaseStock()`으로 PostgreSQL의 재고는 차감하지만 Elasticsearch 문서의 `stockQuantity`는 갱신하지 않아, 검색 결과의 재고 수량이 영구적으로 초기값에 머무릅니다.
- **개선 방안**:
  - `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` + 비동기 처리(또는 Transactional Outbox 패턴)로 DB 커밋 이후 ES 색인이 수행되도록 분리합니다.

---

## 2. 고동시성 주문 및 멱등성(Order & Concurrency) 영역의 설계 결함

### 🤔 2.1 주문 1건당 DB 커넥션을 2~3번 획득/반납하는 구조와 불필요한 `REQUIRES_NEW`
- **관련 코드**: `OrderService.create()`, `OrderService.createNewOrder()`, `OrderCreateExecutor.execute()`
- **현재 동작 흐름**:
  1. `OrderService.create()` (트랜잭션 없음) → `orderRepository.findByMemberIdAndIdempotencyKey()` (**커넥션 1 획득 후 반납**)
  2. `OrderService.createNewOrder()` (트랜잭션 없음) → `memberRepository.findById()` (**커넥션 2 획득 후 반납**, `Member`는 준영속 상태가 됨)
  3. `OrderCreateExecutor.execute()` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`) → `findByIdForUpdate()` + 재고 차감 + 주문 저장 (**커넥션 3 획득 후 반납**)
- **문제점**:
  - `OrderService.create()`에 애초에 `@Transactional`이 없으므로 `OrderCreateExecutor.execute()`에 선언된 `Propagation.REQUIRES_NEW`는 불필요한 설정입니다(기본값 `REQUIRED`여도 새 트랜잭션 시작).
  - 특히 `memberRepository.findById()`를 트랜잭션 외부에서 단독 호출하여, 정상 주문 1건당 DB 커넥션 풀을 불필요하게 1회 더 빌리고 반납하는 오버헤드가 발생합니다.
- **개선 방안**:
  - `memberRepository.findById()`(또는 외래키 참조만 필요한 경우 `getReferenceById()`)를 `OrderCreateExecutor.execute()` 트랜잭션 내부로 이동시켜 커넥션 대여 횟수를 줄입니다.

---

### 🚨 2.2 취약한 멱등성 예외 판별 방식 및 중복 요청의 상품 락 대기
- **관련 코드**: `OrderService.isIdempotencyKeyViolation()`
- **현상**:
  ```java
  private boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
      Throwable cause = ex.getMostSpecificCause();
      String message = cause != null ? cause.getMessage() : ex.getMessage();
      return message != null && message.toLowerCase().contains("idempotency");
  }
  ```
- **문제점**:
  1. **에러 메시지 문자열 의존**: DB 버전, 로케일, 드라이버 설정에 따라 에러 메시지 포맷이 달라지면 깨질 수 있습니다. Hibernate `ConstraintViolationException#getConstraintName()` 또는 PostgreSQL SQLState(`"23505"`, `unique_violation`) 코드로 검증하는 것이 안전합니다.
  2. **동시 중복 요청 시 불필요한 상품 락 경합**: 동일 유저가 따닥(동시 중복 요청)을 발생시켰을 때, 두 번째 요청도 `SELECT ... FOR UPDATE`로 인기 상품의 Row Lock 대기열에 줄을 서서 기다린 뒤에야 `INSERT` 시점에 Unique 제약조건으로 롤백됩니다.

---

### ⚡ 2.3 고동시성 재고 차감 쿼리 최적화 여지 (`SELECT FOR UPDATE` vs Atomic Update)
- **관련 코드**: `ProductRepository.findByIdForUpdate()`, `Product.java`
- **문제점**:
  1. **전체 컬럼 UPDATE (`@DynamicUpdate` 미적용)**: `Product` 엔티티에 `@DynamicUpdate`가 없어 재고(`stock_quantity`) 1개만 줄어들어도 `name`, `description`, `category`, `brand` 등 모든 컬럼을 덮어쓰는 `UPDATE` 문이 실행됩니다. 이는 PostgreSQL의 **HOT(Heap-Only Tuple) 업데이트** 최적화를 방해하고 WAL 크기를 키웁니다.
  2. **2회 왕복으로 인한 락 보유 시간 증가**: `SELECT ... FOR UPDATE` 후 애플리케이션에서 차감하고 `UPDATE`를 날리는 구조는 트랜잭션 내 DB 왕복이 최소 2회 발생합니다.
- **개선 방안**:
  - 단일 쿼리로 원자적 차감 및 조건 검증을 수행하는 **Atomic Conditional Update** 방식을 사용하면 락 보유 시간을 절반 이하로 줄여 단일 인기 상품(Hot Stock) TPS를 크게 높일 수 있습니다:
    ```sql
    UPDATE products
    SET stock_quantity = stock_quantity - :qty
    WHERE id = :id AND stock_quantity >= :qty
    ```

---

### 🐛 2.4 주문 수정/삭제 시 재고 미복구 및 전체 조회(`findAll`) OOM 위험
- **관련 코드**: `OrderService.update()`, `OrderService.delete()`, `OrderService.list()`
- **문제점**:
  1. **재고 정합성 파손**: 주문 생성 시에는 비관적 락으로 엄격하게 재고를 차감하면서, 정작 주문 수량 변경(`update`)이나 주문 삭제/취소(`delete`) 시에는 상품 재고를 복원하거나 가감하는 로직이 전혀 없습니다.
  2. **페이징 없는 `findAll()` + N+1 문제**: `OrderService.list()`가 페이징 없이 `orderRepository.findAll()`을 호출하며 `member`, `product` 페치 조인도 없어, 부하 테스트로 주문이 수만 건 쌓인 뒤 `GET /api/orders`를 1회만 호출해도 수만 번의 N+1 쿼리와 함께 OOM(Out Of Memory)이 발생할 수 있습니다.

---

## 3. 공통 및 인프라(Common & Infra) 영역의 숨은 성능 병목

1. **모든 요청에 대한 동기 콘솔 로깅 및 `UUID.randomUUID()` 호출 (`RequestResponseLoggingFilter`)**
   - 7,000 RPS 부하 시 초당 7,000번의 `UUID.randomUUID()`(`SecureRandom` 내부 동기화 락 경합)와 `log.info("Request completed ...")` 표준 출력(stdout) 동기 I/O + Docker JSON 로그 디스크 쓰기가 발생하여 응답 지연율을 높입니다.
   - **개선**: 정상 200 응답은 샘플링하거나 지정된 임계치(예: `durationMs >= 200ms`) 이상인 슬로우 요청 및 에러(4xx/5xx)만 로깅하도록 변경합니다.
2. **`hibernate.format_sql: true` 상시 활성화 (`application.yml`)**
   - SQL 로그를 출력하지 않아도 Hibernate가 내부적으로 모든 SQL 문자열을 줄바꿈/정렬 포맷팅하느라 CPU와 메모리(GC)를 소모합니다. 부하 테스트 및 운영 환경에서는 `false`로 비활성화해야 합니다.
3. **Elasticsearch 힙 메모리 512MB 제한 (`docker-compose.yml`)**
   - 1,000만 건 문서를 다루는 ES 컨테이너의 `ES_JAVA_OPTS: -Xms512m -Xmx512m` 설정은 과도하게 작습니다. 캐시 미스 비중이 높은 시나리오에서는 Old GC 빈발로 Tail Latency가 튀게 되므로 최소 `2g~4g` 할당을 권장합니다.

---

## 4. 가상 데이터(Seed Data) 및 k6 부하 테스트 시나리오의 비현실성

### 📊 4.1 1,000만 건 가상 데이터(`V4__seed_10m_products.sql`)의 3가지 왜곡
1. **1,000만 건의 `created_at`이 마이크로초 단위까지 100% 동일함**
   - `V4` 마이그레이션의 `INSERT` 컬럼 목록에서 `created_at`이 빠져 있어, PostgreSQL 트랜잭션 시작 시각(`DEFAULT CURRENT_TIMESTAMP`) 하나로 1,000만 건이 동일하게 들어갔습니다.
   - 결과적으로 최신순 정렬(`created_at DESC, id DESC`)에서 `created_at`은 변별력이 0이며, 100% `id DESC` 정렬로만 동작하고 있습니다.
   - **개선**: `CURRENT_TIMESTAMP - (random() * interval '365 days')`로 생성일시를 최근 1년 범위로 분산해야 합니다.
2. **상품명(`name`)의 고유 조합이 1,000만 건 중 단 14,400개뿐 (고유 단어 ~45개)**
   - `브랜드(20) × 성별(3) × 카테고리(6) × 품목(5) × 색상(8) = 14,400`개 조합만 반복되어, 동일한 상품명이 약 700개씩 중복됩니다.
   - 등장하는 영단어(토큰)가 약 45개뿐이라 어떤 키워드를 검색해도 무조건 33만~333만 건이 매칭(Low Selectivity)되며, 실제 이커머스 검색의 핵심인 **희소 키워드(Long-tail 모델명/일련번호 등 10~100건 매칭)** 검색 성능이 전혀 검증되지 않습니다.
3. **모든 속성(브랜드/카테고리/색상)이 1/N 완전 균등 분포(Uniform Distribution)**
   - `mod(hashtextextended(...), N)`로 생성하여 20개 브랜드가 정확히 50만 건(5%)씩 균등 분포합니다.
   - 현실의 파레토 분포(인기 브랜드/카테고리에 데이터 편중)에서 발생하는 **실행 계획(Query Plan) 흔들림이나 Data Skew 병목**을 재현할 수 없습니다.

---

### 🧪 4.2 검색 테스트(`search_v3.js`)의 왜곡: "ES 테스트가 아닌 Redis 180개 키 벤치마크"
- **현상**:
  - `randomPage()`가 `0, 1, 2`만 반환하고, `ProductListFeedKeyResolver`도 `page <= 2`를 모두 30분간 캐싱합니다.
  - 특히 전체 트래픽의 60%인 `simple` 시나리오의 가능한 모든 URL 조합은 **42개**, 20%인 `keyword_search`의 조합은 **138개**뿐입니다.
- **문제점**:
  - 테스트 시작 몇 초 후면 **전체 트래픽의 80% 이상이 Redis에 캐싱된 180개 키만 반복 조회(Cache Hit ~98%)**합니다.
  - 주석(`// Cap deep pagination: 0~2 only`)처럼 깊은 페이지 탐색을 막아두었기 때문에, Cache Miss 시 Elasticsearch가 실제로 감당하는 검색 성능이 가려져 있습니다.
- **개선 방안**:
  - 무한 스크롤(`Slice`) 도입 취지에 맞게 Offset 페이징(`page * size`)을 **커서 기반 페이징(`No-Offset` / ES `search_after`)**으로 고도화하여 깊은 페이지 탐색에서도 지연율이 폭발하지 않게 해결하는 스토리가 훨씬 강력합니다.
  - 시나리오에서도 **캐시 히트(인기 탐색 70~80%) + 캐시 미스(롱테일 키워드/커서 탐색 20~30%)**가 혼합되도록 재구성해야 합니다.

---

### 🛒 4.3 주문 테스트(`order_*.js`)의 비현실적인 가정
1. **회원 수가 50명뿐임 (`V2__seed_dummy_data.sql`)**
   - 수천 VU가 50명의 `member_id`로만 초당 수천 건의 주문을 생성하여, 1명의 회원이 1초에 100~200건씩 주문하는 비현실적인 인덱스 경합이 발생합니다. (회원 시드 최소 1만~10만 명 권장)
2. **극단적인 이분법 시나리오 (`hot_stock` 1개 몰빵 vs `spread_products` 1,000개 완전 균등)**
   - 실무에서 가장 위험한 시나리오는 **"소수 인기 상품(Hot SKU)의 비관적 락 대기가 DB 커넥션 풀 전체를 고갈시켜, 경합이 없는 일반 상품(Normal SKU) 주문까지 타임아웃으로 전파되는 현상(Head-of-Line Blocking)"**입니다. 인기 상품(70% 트래픽)과 일반 상품(30% 트래픽)을 하나의 스크립트에 혼합하면 훨씬 수준 높은 병목 분석이 가능합니다.
3. **중복 결제 테스트(`order_duplicate_submit.js`)가 동시 요청이 아닌 단일 VU 순차 요청임**
   - `vus: 1`에서 첫 번째 `http.post`가 완전히 끝나고 커밋된 뒤 두 번째 `http.post`를 호출하므로, `OrderService`의 첫 줄(`SELECT` 조회)에서만 걸러지고 **Unique 인덱스 충돌 예외 복구(`catch (DataIntegrityViolationException)`) 로직은 단 한 번도 테스트되지 않습니다.**

---

## 5. 기술 스택 타당성 및 보완 포인트

| 구분 | 현재 사용 스택 | 타당성 | 진단 및 보완 권장 사항 |
| :--- | :--- | :---: | :--- |
| **언어 / 런타임** | Java 21 | ⭐⭐⭐⭐ | 타당함. 단, I/O 대기 비중이 높은 시스템이므로 **`Virtual Threads` (`spring.threads.virtual.enabled=true`)** 적용 전후 비교 실험 추가를 강력 권장 |
| **프레임워크** | Spring Boot 3.5, JPA, QueryDSL 5.1, Flyway | ⭐⭐⭐⭐⭐ | 동적 쿼리 작성 및 스키마/시드 버전 관리에 가장 적합한 표준 구성 |
| **검색 / DB** | PostgreSQL 16 + Elasticsearch 8.17 | ⭐⭐⭐⭐⭐ | RDBMS 인덱스(`B-Tree`, `pg_trgm`) 한계 검증 후 ES로 마이그레이션한 근거가 매우 명확함 |
| **캐시** | Redis 7 (Spring Cache) | ⭐⭐⭐⭐ | README의 "다중 캐싱" 명칭에 부합하도록 앞단에 **Caffeine(L1 로컬 캐시)**을 추가하여 `Caffeine + Redis` 2계층 캐시 완성 권장 |
| **통합 테스트 DB** | **H2 In-Memory DB** | ⭐⭐ | 비관적 락(`SELECT FOR UPDATE`), Partial Unique Index(`V6`), Flyway 검증에 H2는 부적합 → **`Testcontainers (PostgreSQL)`**로 전환 필수 |
| **부하 / 관측** | k6 + Prometheus + Grafana | ⭐⭐⭐⭐⭐ | 고부하 발생 및 Native Histogram 기반 메트릭 시각화에 최적의 조합 |

---

## 6. Elasticsearch 정렬(`doc_values` vs `Index Sorting`) 심층 분석

### 6.1 현재 `doc_values` 동작 여부
- **적용 상태**: `ProductDocument`의 `createdAt`(`date`), `popularityScore`(`integer`), `id`(`long`) 모두 Elasticsearch 기본값인 **`doc_values: true`가 활성화**되어 있습니다.
- **기술적 의의**:
  - `ES_JAVA_OPTS: -Xms512m -Xmx512m`라는 극소 힙 메모리 환경에서도 1,000만 건 정렬 시 OOM(`CircuitBreakingException`)이 발생하지 않은 이유는, 정렬 데이터가 JVM Heap(`fielddata`)이 아닌 **디스크 기반 열 지향 저장소(`doc_values`)와 OS Page Cache**를 통해 처리되었기 때문입니다.

### 6.2 추가 최적화 포인트
1. **불필요한 `doc_values` 및 역색인 비활성화 (`stockQuantity`)**
   - 검색 필터나 정렬에 쓰이지 않고 단순 응답(`_source`) 출력용인 `stockQuantity`는 `@Field(type = FieldType.Integer, index = false, docValues = false)`로 설정하여 디스크 공간과 Reindex 시간을 절약할 수 있습니다.
2. **`Index Sorting` (물리적 세그먼트 정렬) 도입 시 선택 기준**
   - `doc_values`는 여러 필드에 동시 적용되지만, 디스크 저장 순서를 결정하는 `Index Sorting`은 클러스터형 인덱스처럼 **인덱스당 1가지 정렬 기준**만 선택 가능합니다.
   - **추천**: 트래픽의 85% 이상을 차지하고 한 번 생성된 후 값이 변하지 않는(Immutable) **`createdAt DESC, id DESC`를 Index Sorting 기준으로 설정**하면, 최신순 검색 시 세그먼트 앞부분만 읽고 끝내는 **Early Termination(조기 종료)**이 작동하여 캐시 미스 구간 성능을 극대화할 수 있습니다. (인기순 정렬은 기존과 동일하게 `doc_values`를 통해 정상 동작함)

---

## 7. 우선순위별 개선 로드맵 (Action Items)

### 🔥 Priority 1: 코드 결함 및 과설계 즉시 수정 (가성비 최고 / 면접 방어 필수)
- [ ] `ProductListFeedKeyResolver`의 11개 하드코딩 분기문을 **범용 정규화 키 생성 로직**으로 단순화 (키워드 소문자 정규화 포함)
- [ ] `ProductService`의 `@CacheEvict(allEntries = true)` 제거 (Cache Avalanche 방지)
- [ ] `ProductDocument.id`에 `@Field(type = FieldType.Long)` 명시 및 `ElasticsearchProductListSearcher` 키워드 검색에 `fuzziness` 옵션 추가
- [ ] `OrderService`의 `memberRepository.findById()`를 `OrderCreateExecutor` 트랜잭션 내부로 이동 및 `isIdempotencyKeyViolation` 제약조건 판별 정교화
- [ ] `OrderService.update/delete` 재고 증감 반영 및 `OrderService.list()` 페이징 적용
- [ ] `RequestResponseLoggingFilter` 정상 요청 로깅 최소화 및 `hibernate.format_sql: false` 변경

### 🚀 Priority 2: 아키텍처 & 시나리오 고도화 (포트폴리오 차별화)
- [ ] **무한 스크롤 커서 페이징(`No-Offset` / `search_after`)** 도입으로 `page >= 3` Deep Pagination 병목 근본 해결
- [ ] **Caffeine(L1) + Redis(L2)** 2계층 다중 캐싱 구현
- [ ] **Java 21 Virtual Threads** 활성화 전/후 고동시성 RPS 및 p99 지연율 비교 실험
- [ ] **Testcontainers (PostgreSQL)** 도입으로 동시성/멱등성 통합 테스트 신뢰도 확보
- [ ] `V4` 시드 데이터의 `created_at` 분산, 상품명 다양화(모델 번호 추가), 파레토 분포 반영 및 동시 중복 주문 k6 스크립트 보강
