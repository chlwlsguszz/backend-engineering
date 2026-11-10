# 커머스 백엔드 설계 개선 기록

데이터 모델, 데이터 흐름, 처리 책임을 실제로 변경한 항목을 기록한다. 각 항목에는 기존 구조의 한계, 선택한 설계와 그 이유, 확인 결과를 남긴다.

## 더미 데이터와 주문 대상

### 1. 날짜 보정을 신규 카탈로그의 최초 INSERT에 통합

V4는 1천만 상품을 같은 시각으로 생성했다. 후속 V7은 설명문에서 마지막 단어 `color`를 누락하고 ID 범위까지 고정해 실제 V4 배치를 놓쳤다. 2026-11-10에 기존 DB를 확인했을 때 V7 이력은 성공이었으나 상품 501~503은 같은 생성 시각이었고, 수정 시각은 생성일보다 앞섰다. V4 형식의 상품은 이전 상한 10,000,500을 넘는 ID에서도 발견됐다.

빈 DB에서 다시 시작하기로 결정해 기존 SQL 여덟 개(V1~V7과 B7)를 단일 V8로 통합했다. V8은 모델별 생성 시각을 기준 시각 직전 365일에 분산하고, 수정 시각을 생성 시각과 기준 시각 사이로 결정한 뒤 상품을 한 번만 INSERT한다. DB 제약으로 수정일 역전을 막고, 적재 중 날짜 범위를 확인한다. 네 개까지는 한 모델의 색상일 수 있으므로 같은 생성 시각을 허용한다. 기존 설명문이나 ID로 보정 대상을 추측할 필요가 사라졌다. 이전 DB의 상품과 주문은 이전 볼륨에 보관되며 새 DB로 이관되지 않는다. 2026-11-10 빈 DB에서 상품 1,000만 건을 생성한 V8 마이그레이션은 Flyway 로그 기준 2분 56.199초에 완료됐다. [V8 초기화 SQL](../backend/src/main/resources/db/migration/V8__initialize_commerce_catalog.sql)

**2026-11-10 날짜 분포 확인.** V8 PostgreSQL의 상품 10,000,000건에서 최초와 최종 생성 시각의 차이는 364일 23시간 59분 37.227853초였다. 최초 생성 시각부터 24시간씩 나눈 365개 구간은 최소 26,516건, 평균 27,397건, 최대 28,372건이었으며 수정일 역전은 0건이었다. ES에서도 `createdAt` 값 10,000,000개와 밀리초 정밀도에서 동일한 양 끝 시각을 확인했다.

### 2. 검색과 주문이 공유하는 모델 및 SKU 생성 규칙 도입

기존 상품명은 제한된 단어 조합을 반복했고, 판매 상태와 재고는 독립 난수로 정했다. 특정 모델을 검색했을 때 기대할 상품 ID를 정하기 어려웠고 품절 상품의 가용 재고가 양수인 경우도 생겼다.

새 카탈로그는 모델마다 브랜드와 품목을 정하고 색상별 SKU를 최대 네 개 생성한다. 각 SKU의 판매 상태와 가용 재고를 함께 정하며, 초기 주문은 같은 SKU의 ID와 단가를 참조한다. 같은 PostgreSQL 16 환경에서 기준 시각을 고정하면 데이터 규모를 늘려도 기존 SKU 속성을 재현할 수 있다. 13개 이상 생성했을 때 모델 코드 `ME0000001`은 4건, `ME0000000`은 0건, 컬렉션명 `StudioCapsule`은 13건이 설계상 기대값이다. [카탈로그 생성 SQL](../backend/src/main/resources/db/migration/V8__initialize_commerce_catalog.sql)

### 3. 주문 준비를 카탈로그 변경에서 읽기 전용 표본 추출로 전환

기존 준비 스크립트는 고정 ID 구간의 상품과 주문을 삭제한 뒤 주문 전용 상품 1,000개를 다시 만들었다. 이 상품의 인기도가 일반 상품보다 높아 주문 실험을 준비하는 것만으로 인기순 검색 결과가 달라졌다.

현재는 기존 카탈로그에서 재고가 양수인 `ACTIVE` 상품을 최대 1,000개 선택해 상품 ID, 시작 재고, 단가를 파일에 기록한다. 같은 DB 상태와 시드값으로 대상을 재현할 수 있고, 주문 준비가 검색 순위에 영향을 주지 않는다. 주문 k6와 재고 대사 SQL을 이 파일에 연결하는 작업은 남아 있다. [대상 선정 SQL](../scripts/sql/seed_order_products_1000.sql) / [추출 스크립트](../scripts/sql/run-seed-order-products.ps1)

### 10만 SKU 파일럿에서 보강한 생성 규칙

V8에서는 모델마다 색상이 네 개였고 검정과 녹색 모두 전체 상품의 약 12.5%였다. 판매 이력과 현재 재고도 따로 생성해 초기 데이터만으로 판매 수량과 재고의 관계를 확인하기 어려웠다.

B9는 각 브랜드 아래 여러 모델이 공유하는 라인업을 만들고, 모델마다 1~7개 색상을 품목별 가중치로 중복 없이 배정한다. 수요 점수를 주문 발생과 인기도에 함께 사용하며 한정판과 상시 상품의 공급 조건을 구분한다. SKU별로 기초 재고와 입고량을 기록해 실제 주문 행에서 계산한 재고 점유량과 대조할 수 있다. 기본 10만 건에서 확인할 사례는 한정 라인업 32건, 도시 컬렉션 128건, 재고 120개인 상품과 마지막 1개 및 품절 상품이다. 이는 생성 규칙의 기대값이며 실제 분포와 불일치 건수는 적재 후 검증 SQL로 확인한다. [B9 생성 SQL](../backend/src/main/resources/db/migration/B9__realistic_commerce_catalog.sql) / [분포와 재고 대사](../scripts/sql/check_catalog_distribution.sql)

**2026-11-10 B9 파일럿 검증.** 시드 42로 생성한 상품 100,000개, 모델 25,000개, 회원 1,000명, 주문 1,291건을 확인했다. 기초 재고 3,645,103개에 입고 539,997개를 더하고 예약 및 확정 수량 2,277개를 빼면 현재 가용 재고 4,182,823개와 일치했다. SKU별 재고 불일치, 상태 오류, 날짜 오류, 주문 오류, 가격 불일치는 모두 0건이었다. 필터 일치 건수는 `TOP + BLACK`이 4,440개, `Oakley + GREEN`이 96개였다. 이를 확인한 뒤 신규 DB의 기본 규모를 1,000만 건으로 올렸다.

**2026-11-10 B9 1,000만 SKU 전수 검증.** 같은 시드와 기준 시각으로 별도 DB를 초기화했다. Flyway 실행 시간은 12분 51.305초였다. 상품 10,000,000개, 모델 2,500,000개, 회원 100,000명, 주문 126,373건을 확인했다. 기초 재고 364,964,687개에 입고 54,232,362개를 더하고 예약 및 확정 수량 220,890개를 차감한 418,976,159개가 현재 가용 재고와 일치했다. SKU별 재고 불일치와 주문, 날짜, 가격 오류는 모두 0건이었다. `TOP + BLACK`은 440,490개, `Oakley + GREEN`은 8,881개였고, `Drop000001` 32개와 `ME0000001` 4개도 기대값과 일치했다.

## Redis 상품 피드 캐시

상품 등록, 수정, 삭제 때마다 목록 캐시 전체를 지우면 변경과 관계없는 홈, 카테고리, 검색어 피드까지 동시에 다시 채워야 했다. 기본 Redis 캐시 삭제 전략은 키 패턴 탐색에 `KEYS`를 사용하고 매칭된 항목을 `DEL`로 지운다. 상품 쓰기 경로에서 전체 삭제를 제거하고, Redis에 피드 결과를 저장할 때마다 기본 30~60초의 TTL을 부여하도록 변경했다. 기존 30분 TTL 항목은 이전 이름 공간에서 자연 만료된다. [피드 캐시 설정](../backend/src/main/java/com/marketengine/backend/common/config/RedisCacheConfig.java)

이 정책은 목록 응답의 Redis 체류 시간을 제한한다. 주문으로 재고가 줄면 Outbox 워커가 ES에 반영하고, 이미 캐싱한 목록은 TTL 만료 후 새 재고를 읽는다. 만료 시점에 같은 인기 키를 동시에 조회하면 여러 요청이 검색 저장소로 갈 수 있다. 변경 후 실측은 [피드 비교 절차](K6_SCENARIOS.md#같은-피드로-캐시-효과-비교)에 따른다.

### 필터 조합별 분기를 공통 캐시 키로 교체

기존에는 키를 만드는 분기가 있는 조합만 캐싱해 `category + minPrice`와 `brand + color`는 매번 검색 저장소를 호출했다. 이제 `size=12`, `page=0~2`인 요청은 모든 필터, 정렬, 페이지 번호, 페이지 크기를 고정된 순서로 키에 넣는다. 문자열 값은 URL 인코딩하므로 브랜드나 검색어에 `&`나 `=`가 있어도 키의 필드 경계가 유지된다. [피드 키 생성 코드](../backend/src/main/java/com/marketengine/backend/product/application/ProductListFeedKeyResolver.java)

키워드는 검색과 같은 규칙으로 소문자로 바꾸고 공백으로 나눈 뒤 길이 1인 토큰을 제외한다. 브랜드, 성별, 색상은 앞뒤 공백만 제거해 대소문자와 내부 공백을 보존한다. 임의의 필터를 캐싱하면 반복 검색은 재사용할 수 있지만, 한 번만 조회되는 조합도 만료 전까지 Redis에 남는다. 새 정책의 키 수, 적중률, 메모리 사용량은 아직 측정하지 않았다.

## HTTP 요청 로깅

기존 필터는 빠른 성공 응답에도 매번 완료 로그를 출력했고, 클라이언트가 요청 ID를 생략하면 `UUID.randomUUID()`를 호출했다. 이제 필터는 200ms 이상인 성공 응답과 4xx를 WARN, 5xx를 ERROR로 기록한다. 필터 밖으로 전파되는 예외는 경과 시간과 스택을 남기고 다시 던진다. 처리 시간은 단조 시계로 측정하고 임계값은 `marketengine.logging.slow-request-threshold`로 조절한다.

클라이언트가 보낸 `X-Request-Id`는 응답과 로그에 사용한다. 헤더가 없거나 비어 있으면 `ThreadLocalRandom`으로 추적용 UUID v4를 생성한다. 전체 요청 수와 지연 분포는 k6와 HTTP 메트릭으로 확인하고, 개별 느린 요청과 오류는 요청 ID로 찾는다. 정상 요청의 로그가 줄어드는 효과와 실제 RPS, p99 변화는 같은 조건에서 다시 측정한다. [요청 필터](../backend/src/main/java/com/marketengine/backend/common/logging/RequestResponseLoggingFilter.java)

## 상품 변경의 검색 반영

기존에는 상품을 등록하거나 수정, 삭제하는 DB 트랜잭션 안에서 Elasticsearch를 호출했다. ES 응답을 기다리는 동안 DB 커넥션을 점유했고, ES 쓰기 성공 후 DB 커밋이 실패하면 검색에만 변경이 남을 수 있었다.

V10부터 `ProductService`는 상품 변경과 `product_search_outbox` 기록을 같은 PostgreSQL 트랜잭션에 저장한다. 상품 ID당 대기 행 하나를 두고 변경할 때마다 새 버전을 부여한다. 워커가 가져가기 전에 같은 상품이 여러 번 바뀌면 마지막 상태만 보낸다. V10은 빈 Outbox를 생성한다. 기존 카탈로그의 1,000만 건과 ES 문서는 그대로 사용한다. [상품 서비스](../backend/src/main/java/com/marketengine/backend/product/application/ProductService.java)와 [마이그레이션](../backend/src/main/resources/db/migration/V10__product_search_outbox.sql)

워커는 기본 250ms 간격으로 최대 500개를 선점하고 커밋된 상품을 읽는다. DB 커넥션을 반환한 뒤 한 번의 ES Bulk 요청을 보내고 성공한 항목만 정리한다. 실패한 상품의 재시도 간격은 1초부터 최대 60초까지 늘어난다. 워커가 도중에 종료되면 60초 뒤 다른 워커가 인계할 수 있다. ES는 저장된 버전보다 새 변경만 적용한다. 삭제한 상품에는 ID, 버전, 삭제 표시만 남겨 뒤늦은 수정으로 다시 노출되는 것을 방지한다. 이 표시는 검색과 재색인 검증 건수에서 제외되며, 인덱스를 재생성하면 정리된다. [Outbox 저장소](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductSearchOutbox.java)와 [ES 반영 코드](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductOutboxPublisher.java)

상품 쓰기 API의 성공은 PostgreSQL 커밋을 의미한다. 상세 조회에는 즉시 반영되고, 목록은 워커 처리와 ES refresh 후 갱신된다. 이미 캐싱한 목록에는 Redis TTL만큼 이전 값이 보일 수 있다. 상품 쓰기 p95와 검색 반영 지연은 아직 실측하지 않았다.

1,000만 건 Compose 환경에서 다음 명령으로 대기 중인 상품 수를 확인한다. 실패와 워커 재시작을 시험할 때는 실행 중인 백엔드에서 ES를 중지하고 상품 하나를 여러 번 수정한다. DB 상세 조회에 마지막 수정이 보이고 Outbox에 해당 ID 한 건이 남는지 확인한다. ES를 다시 시작한 뒤에는 대기 행이 사라지고 `GET /products/_doc/{id}`에 최종 값이 보이는지 확인한다.

```powershell
docker compose -p marketengine-catalog-10m exec -T postgres psql -U marketengine -d marketengine -c "SELECT count(*) AS pending_products, count(*) FILTER (WHERE retry_count > 0) AS retrying_products FROM product_search_outbox"
```

### 주문 생성 시 차감한 재고를 검색에 반영

기존 주문은 PostgreSQL 재고만 차감해 검색 결과에 이전 수량이 남았다. 이제 주문 생성 트랜잭션이 주문 저장 후 같은 상품 ID를 기존 Outbox에 기록한다. 주문 생성이나 Outbox 기록이 실패하면 주문과 차감이 함께 롤백된다. 이미 처리한 멱등성 키로 같은 주문을 다시 요청하면 기존 주문을 반환한다. 한 SKU에 주문이 몰려도 대기 행은 하나이며, 워커는 PostgreSQL에서 읽은 최종 재고 수량을 ES에 쓴다. [주문 생성 코드](../backend/src/main/java/com/marketengine/backend/order/application/OrderCreateExecutor.java)

성공한 신규 주문마다 DB에 Outbox 쓰기 한 번이 추가된다. 주문 TPS와 색인 지연은 아직 측정하지 않았다. 배포 전에 이미 어긋난 수량은 해당 상품의 다음 변경이나 기존 재색인 작업에서 바로잡힌다.

## 마지막 재고에 대한 중복 주문 복구

재고 한 개에 같은 멱등성 키로 두 요청이 동시에 들어오면 둘 다 기존 주문 조회를 통과할 수 있다. 첫 요청이 마지막 수량을 가져가면 두 번째 요청은 주문 INSERT 전에 재고 부족으로 종료됐다. PostgreSQL 16에서 재현했을 때 DB에는 주문 한 건과 정확한 재고가 남았지만, 같은 요청을 보낸 클라이언트는 서로 다른 응답을 받았다.

이제 쓰기 트랜잭션이 재고 부족으로 롤백되면 바깥 서비스가 같은 회원과 멱등성 키로 커밋된 주문을 한 번 조회한다. 상품과 수량이 같으면 기존 주문을 반환하고, 요청 내용이 다르면 키 충돌을 반환한다. 주문이 없으면 원래 재고 부족 오류를 반환한다. 이 추가 조회는 상품 잠금을 해제한 후에 실행한다. [주문 서비스](../backend/src/main/java/com/marketengine/backend/order/application/OrderService.java)와 [동시 주문 회귀 테스트](../backend/src/test/java/com/marketengine/backend/order/application/OrderStockOutboxIntegrationTest.java)

PostgreSQL 16 회귀 테스트에서 두 요청은 같은 주문 ID를 반환했고, 저장된 주문 한 건과 최종 재고 0개, Outbox 한 건을 확인했다. 같은 환경에서 HikariCP 대여 횟수는 신규 주문 성공 3회, 이미 성공한 주문의 순차 재시도 1회였다.

### 멱등성 조회와 회원 조회를 주문 쓰기 트랜잭션으로 이동

이전 구현은 멱등성 키 조회, 회원 조회, 주문 쓰기마다 커넥션을 빌렸다. 이제 `OrderCreateExecutor.execute()`의 기본 `@Transactional` 안에서 기존 주문을 먼저 조회한다. 재시도라면 기존 응답을 반환한다. 신규 주문이면 같은 커넥션에서 회원을 확인하고 상품을 잠근 뒤 주문, 재고, Outbox를 함께 커밋한다. 바깥 서비스는 호출자의 트랜잭션을 보류하고 실행 빈의 롤백이 끝난 뒤 중복 요청을 복구한다. [주문 실행 코드](../backend/src/main/java/com/marketengine/backend/order/application/OrderCreateExecutor.java)와 [복구 경계](../backend/src/main/java/com/marketengine/backend/order/application/OrderService.java)

2026-11-10 PostgreSQL 16에서 동일한 입력으로 신규 주문과 순차 재시도 전후의 `hikaricp.connections.acquire` 카운터 차이를 비교했다. 신규 주문은 **3회에서 1회**로 줄었고 재시도는 두 실행 모두 **1회**였다. 기대값을 각각 1회로 고정한 뒤 전체 테스트 68개가 통과했다. 이 측정의 단위는 주문 요청당 풀 대여 횟수다. [측정 테스트](../backend/src/test/java/com/marketengine/backend/order/application/OrderStockOutboxIntegrationTest.java)

### 주문 멱등성 충돌을 PostgreSQL 오류 필드로 식별

이전에는 예외 메시지에 `idempotency`가 들어가면 기존 주문을 재조회했다. 주문 INSERT에는 `idempotency_key` 열이 있어 다른 DB 오류에도 같은 문자열이 나타날 수 있다. 이제 PostgreSQL이 보고한 SQLSTATE가 `23505`이고 테이블이 `orders`, 제약 이름이 `uk_orders_member_idempotency_key`일 때만 복구한다. 나머지 오류는 원래 예외로 전파한다. [주문 서비스](../backend/src/main/java/com/marketengine/backend/order/application/OrderService.java)

PostgreSQL 16 회귀 테스트에서 재고 10개에 같은 키로 주문 10건을 동시에 시도했다. 10개 응답의 주문 ID가 같았고, 저장된 주문 한 건과 남은 재고 9개, Outbox 한 건을 확인했다. 별도의 유니크 인덱스 `uk_test_idempotency_product`를 위반한 경우에는 같은 SQLSTATE에도 실제 제약 이름을 가진 DB 오류가 전파됐다. [통합 테스트](../backend/src/test/java/com/marketengine/backend/order/application/OrderStockOutboxIntegrationTest.java)

## Elasticsearch 재색인

### 4. 순차 배치 루프에서 DB 조회와 ES 쓰기를 분리

기존에는 상품 5,000개를 읽은 뒤 ES 저장이 끝나야 다음 배치를 조회했다. 2026-10-21 실행 기록에서 PostgreSQL에 있던 상품 10,000,500건을 ES에 옮기는 데 **1,283초(21분 23초)**가 걸렸다.

현재는 DB 리더 하나가 ID 순서로 읽고 ES 워커가 저장한다. 워커 0은 순차 비교 기준, 1은 읽기와 쓰기 중첩, 2~8은 병렬 쓰기다. 미완료 배치를 최대 `워커 수 + 1`개로 제한해 ES가 느릴 때 리더가 데이터를 계속 쌓지 않도록 했다. [재색인 코드](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductBulkReindexer.java) / [비교 절차](../readme.md#elasticsearch-재색인-비교)

**2026-11-10 실측.** 기존 DB의 상품 10,000,500건, 배치당 5,000건, 인덱스 재생성 조건에서 워커 4의 전체 재색인 시간은 529,740ms(8분 49.740초)였다. `indexed`와 `targetDocuments`는 모두 10,000,500이었다. DB 조회 및 매핑은 39,444ms, ES 쓰기 요청의 워커별 누적 합은 2,086,920ms, 최종 refresh는 84ms였다.

워커 0과 1은 중간에 중단했다. 세 실행에서 시작 로그부터 첫 250만 건 저장 완료까지는 각각 337.624초(워커 0), 320.626초(워커 1), 129.522초(워커 4)였다. 이 구간에서 워커 4의 처리량은 워커 0의 약 2.61배였다. 실행 순서는 0, 1, 4였으며 예열 상태를 통제하지 않았다. 이 비율은 전체 재색인 시간의 개선 배수로 사용하지 않는다.

**2026-11-10 V8 카탈로그, 워커 8 실측.** 배치당 5,000건으로 상품 10,000,000건을 ES에 재색인하는 데 **5분 14.815초**(`wallMs=314815`)가 걸렸다. 저장 성공 건수(`indexed`)와 최종 ES 문서 수(`targetDocuments`)는 모두 10,000,000이었다. **8분 27초**는 Gradle 전체 실행 시간이며, 같은 실행에서 수행한 V8 마이그레이션 2분 56.199초와 ES 재색인 시간을 포함한다. 기존 워커 4 기록은 다른 카탈로그에서 측정했으므로 두 기록만으로 워커 증가의 효과를 산정할 수 없다. 원본 로그는 `market-reindex-v8-workers-8-20261110-021546.log`의 67, 187, 193행이다.

### 5. 병렬 재색인의 조회 진행도와 쓰기 완료 조건 분리

병렬 쓰기에서는 나중에 읽은 배치가 먼저 끝날 수 있다. 마지막으로 읽은 상품 ID를 저장 완료 지점으로 간주하면 앞선 배치의 실패를 놓친다.

조회 커서는 DB에서 읽은 ID로 전진시키고, 완료 건수는 ES 쓰기가 성공한 배치만 합산한다. 모든 쓰기가 끝난 후 최종 refresh를 수행하고 원본 건수, 성공 건수, ES 문서 수를 대조한다. 인덱스를 재생성한 실행에서는 건수가 다르면 실패한다. 일부 배치가 이미 저장된 상태에서 실패할 수 있으므로, 이 절차는 원본을 고정한 오프라인 재색인을 전제로 한다. 단계별 시간도 기록하며, 병렬 워커의 누적 쓰기 시간은 전체 경과 시간과 구분한다. [완료 조건과 계측 코드](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductBulkReindexer.java)
