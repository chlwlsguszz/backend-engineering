# Market Engine 프로젝트 개선 및 리팩토링 계획

2026-11-10 작업 트리 기준. 포트폴리오에서 증명할 대상은 **1,000만 상품을 탐색하고, 하나의 SKU를 주문하고, 그 결과가 PostgreSQL 재고와 검색에 일관되게 반영되는 과정**이다. 이 문서에서 작업 순서와 완료 조건을 관리한다. 완료된 변경과 실측 수치는 [개선 기록](IMPROVEMENT_LOG.md), 재현 절차는 [k6 시나리오](K6_SCENARIOS.md)에 남긴다.

## 실행 현황

2026-11-11 후속 변경에서 전체 백엔드 검사 122개와 브라우저 검사 5개, 짧은 k6 및 장애 복구 검수가 통과했다. 단, 검수 종료 정리 중 기존 1,000만 건 DB와 ES 볼륨이 잘못 삭제됐으며 아직 복구되지 않았다. 개선 코드의 검증 상태와 기존 환경의 보존 실패를 구분한다. [사고 기록](VERIFICATION_INCIDENT_2026-11-11.md)

2026-11-10 로컬 검증에서 Gradle 테스트 88개가 모두 통과했다. 생략된 테스트는 0개였으며 CI의 필수 통합 테스트 보고서 검사도 통과했다. 프론트 lint, 프로덕션 빌드, Chromium 테스트 5개가 통과했다. 별도 Docker 환경에서 캐시 우회, ES 복구와 주문 재고 대사를 실행했다. [개선별 실측](IMPROVEMENT_LOG.md)

- **주문과 재고.** 단가 반환을 포함한 조건부 재고 갱신, 수량 차이 반영, 취소 후 멱등성 유지, 관리자 변경 충돌 방지를 적용했다. 기존 주문은 V11 적용 당시 수량을 최초 요청 비교에 사용한다. 한 상품에 집중되는 SQL 비교와 1,000개에 분산되는 비교를 짧게 수행했다. [주문 정책과 측정값](ORDER_INVENTORY.md)
- **목록과 검색.** 주문과 회원은 ID 커서로 다음 페이지를 읽고, 상품은 PG와 ES에서 같은 시작 위치로 최대 한 건 더 읽는다. 검색 조건 정규화, 캐시 키, 저장소별 검색 방식의 차이를 코드와 OpenAPI에 반영했다. [PG 및 ES 계약 검사](../backend/src/test/java/com/marketengine/backend/product/infrastructure/search/ProductSearchContractIntegrationTest.java)
- **브라우저와 CI.** 주문 응답 유실 후 새로고침, 새 구매, 품절, 판매 중단, 필터 변경과 다음 페이지 실패를 브라우저에서 검사한다. CI는 필수 통합 테스트가 Docker 부재로 생략돼도 실패하도록 설정했다. [브라우저 검사](../frontend/README.md) / [CI](../.github/workflows/ci.yml)
- **데이터와 검색 운영.** 개발 기본값은 SKU 1,000개이며 빈 스키마, 10만 및 1,000만 건 환경을 선택할 수 있다. 재색인은 원본 쓰기를 잠그고 건수와 표본 필드를 대조한다. Outbox는 대기 수, 최장 대기 시간, 성공과 실패 횟수를 노출한다. [데이터 구성](DUMMY_DATA_DESIGN.md) / [격리 운영 검사](../scripts/verify/run-commerce-smoke.ps1)
- **실험과 접근 범위.** Grafana 주문 지표에는 HTTP 성공 응답임을 표시하고, 주문 부하 기록에는 실행 키로 DB를 조회한 신규 주문 수와 재고 차이를 남긴다. 공개 범위는 루프백 로컬 데모로 정했다. [실험 절차](K6_SCENARIOS.md)

최대 HTTP TPS 재측정은 별도 부하 실험으로 남겼다. 2026-11-11 후속 작업에서 Caffeine/Redis 캐시, 원본 검색 동시성 제한, 커서 검색, ES 연결과 색인 설정, 주문 락 제한, 검색 재고 표시 분리를 추가했다. 단계별 검수는 [개선 기록](IMPROVEMENT_LOG.md)의 후속 성능 개선을 따른다. 멱등성 키 전용 락, Kafka, 온라인 재색인과 외부 서비스 인증은 도입 조건이 생기면 다시 판단한다.

## 계획 수립 당시의 기준선

- **데이터.** B9의 10만 건과 1,000만 건 카탈로그에서 분포와 재고 대사를 수행했다. 1,000만 SKU 환경의 시드 시점 회원은 100,000명, 주문은 126,373건이다. 검색과 주문은 같은 상품을 사용한다. [데이터 설계](DUMMY_DATA_DESIGN.md)
- **주문.** 멱등성 선조회와 회원 조회를 주문 쓰기 트랜잭션으로 옮겨 신규 주문의 HikariCP 대여를 3회에서 1회로 줄였다. 같은 주문의 재시도도 1회다. PostgreSQL의 오류 코드, 테이블, 인덱스 이름으로 중복을 식별하며, 마지막 재고에 대한 동시 중복 요청도 같은 주문 ID를 반환한다. [주문 생성](../backend/src/main/java/com/marketengine/backend/order/application/OrderCreateExecutor.java)과 [검증 기록](IMPROVEMENT_LOG.md)
- **검색 반영.** 상품 변경 및 신규 주문에 의한 재고 차감은 트랜잭셔널 Outbox가 ES에 반영한다. 피드는 정규화된 키와 기본 30~60초 TTL을 사용한다. 전체 재색인은 별도의 오프라인 작업이다. [Outbox 워커](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductOutboxWorker.java)와 [재색인 구현](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductBulkReindexer.java)
- **중복 경합.** 20 POST/s, 조건별 20초 실험에서 전체 POST의 20%를 중복 전송하자 인덱스 충돌 80건이 발생했다. 다른 상품의 독립 주문 p95는 5.30ms에서 6.17ms로 바뀌었다. Hikari 대기 스레드는 조건당 4개 표본에서 모두 0이었다. 이 결과는 해당 부하 구간에 한정한다. [실험 조건과 결과](K6_SCENARIOS.md#2026-11-10-실측-결과)

아래는 변경 전 코드에서 잡은 작업 순서와 검증 기준이다.

## 1순위 | 주문 화면과 재고 정합성

프론트 주문 계약과 백엔드 재고 정합성은 병행할 수 있다. 백엔드의 첫 작업은 주문 수정과 취소를 재현하는 PostgreSQL 테스트다. 상품 페이지 경계와 목록 조회는 별도 변경으로 진행하고, 재고 성능 실험은 재고 정합성 작업이 끝난 버전에서 시작한다.

**첫 프론트 PR은 브라우저 주문 계약을 고친다.** 백엔드의 `CreateOrderRequest`는 `idempotencyKey`를 필수로 받지만 프론트의 `createOrder`는 회원, 상품, 수량만 보낸다. 현 계약대로면 정상적인 주문 버튼 요청도 400이다. 구매 의사 한 건에 키를 한 번 발급하고, 응답 유실 후 같은 주문을 재시도할 때 그 키를 재사용한다. 수량이나 상품을 바꾸거나 주문 성공 후 새로 구매하면 새 키를 사용한다. 오류 코드와 재고 부족을 화면에 구분해서 표시하고 성공 후 상품 상세를 다시 읽는다.

**완료 기준**  실제 브라우저에서 주문 한 건이 생성되고, 응답 유실 후 재시도해도 주문 ID와 재고 차감 횟수가 같다. [프론트 API](../frontend/features/products/shared/api.ts), [주문 화면](../frontend/app/products/%5Bid%5D/page.tsx), [백엔드 DTO](../backend/src/main/java/com/marketengine/backend/order/api/OrderDtos.java)

**첫 백엔드 PR은 주문 수정과 취소를 같은 재고 규칙에 연결한다.** 현재 `update`는 상태와 수량만 바꾸고, `delete`는 행을 삭제해 멱등성 키까지 지운다. 다음은 구현 전에 확정할 **권장 API 정책**이다. [주문 서비스](../backend/src/main/java/com/marketengine/backend/order/application/OrderService.java)

1. `CREATED`와 `CONFIRMED`는 현재 주문 수량을 점유하고 `CANCELLED`는 0개를 점유한다. 주문 행을 잠그고 **변경 후 점유량 − 변경 전 점유량**만 적용한다. 3개에서 5개로 바꾸면 2개 추가 차감, 같은 수량을 확정하면 차감 0개다. 금액은 최초 주문 단가로 재계산한다.
2. `DELETE /api/orders/{id}`는 취소로 처리해 주문 행과 키를 보존한다. 반복 취소는 성공, 취소 후 수량 변경과 재활성화는 충돌로 처리한다. 확정 후 허용할 수량 변경과 취소 범위, 이전 클라이언트에 보일 응답을 OpenAPI에 명시한다.
3. 생성 시 최초 수량을 별도로 저장한다. 현재 재시도 검증은 변경 가능한 주문 수량과 비교한다. 신규 주문은 최초 요청과 대조하고, 동일 요청에는 같은 ID의 현재 주문을 반환하는 계약을 권한다. 신규 버전 마이그레이션으로 필드를 추가하고, 기존에 수정된 주문의 원래 수량을 복원할 수 없는 경우는 호환 처리와 적용 시점을 명시한다. 삭제된 이력은 자동 복구 대상으로 집계하지 않는다.
4. 주문 변경은 주문 행, 상품 행 순으로 잠그고 재고와 주문을 함께 커밋한다. 실제 재고 변경 시에만 Outbox에 기록한다. 상품 관리 API의 절대 재고 덮어쓰기도 점검한다. 일반 상품 정보와 입출고 조정을 분리하거나, 절대값 변경 시 버전 불일치를 거절해 오래된 관리자 화면이 주문 차감을 되돌리지 않게 한다. 판매 중단과 수량 0의 표시 및 주문 가능 조건도 확정한다. [상품 수정 경로](../backend/src/main/java/com/marketengine/backend/product/application/ProductService.java)

**완료 기준**  수량 증감, 추가 재고 부족 시 전체 롤백, 두 번 취소해도 한 번만 복원, 수정과 취소의 동시 실행, 관리자 수정과 주문의 경합, 수정 또는 취소 후 최초 POST 재시도를 PostgreSQL에서 검증한다. 주문, 상품 재고, Outbox, 최종 ES 수량까지 대조한다. 기존 주문의 과거 불일치와 배포 이후 발생한 차이는 별도로 기록한다.

## 2순위 | 누락 없는 검색과 크기가 제한된 목록 API

주문 도메인 작업과 병행할 수 있다. 두 목록은 사용자가 보는 결과와 응답 계약이 달라 별도 PR로 낸다.

- **상품 페이지 경계부터 수정.** ES는 한 건을 더 읽으려고 페이지 크기를 `size + 1`로 바꾸면서 시작점까지 `page × (size + 1)`로 이동한다. 13개 상품을 12개씩 조회하면 첫 페이지에서 보류한 13번째가 두 번째 페이지에서 빠진다. 먼저 기존 API에서 `from = page × size`, 조회 개수 `size + 1`을 독립적으로 지정한다. 프론트는 응답 성공 후에만 페이지를 확정하고, 필터 변경 전 요청의 늦은 응답을 폐기한다.

   **완료 기준**  고정 데이터 0, 12, 13, 25개 및 정렬 동점에서 PG와 ES 각각 누락과 중복이 0이다. 캐시 ON/OFF, 중간 요청 실패 후 재시도, 빠른 필터 전환도 검증한다. [ES 검색](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ElasticsearchProductListSearcher.java), [프론트 목록 훅](../frontend/features/products/home/hooks/useProductList.ts), [기존 pagination 검사](../scripts/k6/search_scenarios.js)
- **주문과 회원 목록에 조회 상한 적용.** 두 서비스 모두 `findAll()` 뒤에 응답 DTO를 만든다. 주문 목록은 `id DESC`와 `beforeId`를 기준으로 기본 20개, 최대 100개, 조회는 `size + 1`로 제한한다. 회원 목록에도 같은 방식의 상한을 둔다. 주문 응답은 주문 컬럼과 회원 ID, 상품 ID만 투영한다. 배열에서 `items, nextCursor, hasNext`로 바뀌므로 Swagger와 호출처를 함께 바꾸고 기존 계약 유지가 필요하면 버전 경로로 이관한다.

   **완료 기준**  주문 10만 건 이상에서도 한 페이지의 목록 SQL 1회, 최대 101행, 별도 전체 건수 조회 0회. 현재 N+1 및 OOM 발생 여부는 SQL 횟수와 메모리로 확인한다. [주문 응답](../backend/src/main/java/com/marketengine/backend/order/api/OrderDtos.java), [회원 서비스](../backend/src/main/java/com/marketengine/backend/member/application/MemberService.java)
- **검색어와 정렬 계약을 한 곳에 정의.** PG의 부분 문자열 `LIKE`와 ES의 분석된 토큰 `match`는 같은 검색어에도 결과가 달라질 수 있다. 모델 코드, 브랜드 대소문자, 한 글자, 공백, 리터럴 `%`와 `_`, 가격 범위, 빈 인덱스와 정렬 동점을 고정 예시로 정의한다. 잘못된 범위와 검색 가능 페이지 상한의 4xx 처리도 문서화한다. 기존 `ProductListFeedQuery`를 활용해 필터와 정렬 정규화를 검색 어댑터와 캐시 키가 공유하도록 한다.

   **완료 기준**  계약상 동일한 검색은 같은 결과를 내고, 의도한 차이는 테스트에 드러난다. 새 인덱스에서도 `id` 정렬이 동작하는지 실제 매핑으로 확인한다. 오타 허용과 깊은 탐색의 `search_after`는 요구가 확인되면 별도로 비교한다. [PG 검색](../backend/src/main/java/com/marketengine/backend/product/domain/ProductRepositoryImpl.java), [캐시 키](../backend/src/main/java/com/marketengine/backend/product/application/ProductListFeedKeyResolver.java), [Elastic 페이지네이션](https://www.elastic.co/guide/en/elasticsearch/reference/8.17/paginate-search-results.html)

## 3순위 | 재고 쓰기 성능 실험

**1순위의 정합성 테스트를 통과한 버전을 기준선으로 고정한다.** 트랜잭션 대여 횟수와 SQL 왕복 횟수는 다른 지표다. 정상 신규 주문의 대여 1회, 마지막 재고 재시도, 유니크 충돌 복구를 회귀 테스트로 유지한다.

- **비교할 SQL**  현재 상품 잠금 조회 및 JPA 재고 UPDATE를 `UPDATE products SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty RETURNING price_amount`로 교체한다. 판매 가능 상태 규칙도 같은 조건에 반영한다. 반환 가격으로 주문 단가를 확정하고 엔티티를 다시 전체 조회하는지 SQL로 확인한다. 결과 0행이면 상품 없음, 품절, 판매 중단 계약에 맞게 판별한다. 재고 변경과 주문, Outbox는 하나의 트랜잭션이다. [현재 주문 쓰기](../backend/src/main/java/com/marketengine/backend/order/application/OrderCreateExecutor.java)와 [PostgreSQL UPDATE](https://www.postgresql.org/docs/16/sql-update.html)
- **정합성 검증**  기존 동시성 테스트의 잠금 관찰 조건은 잠금 SELECT에 맞춰져 있다. 원자 갱신 경로에서도 두 세션이 같은 상품에서 실제 경합했는지 확인한다. 재고 초과 판매 0건, 단가 일치, 실제 유니크 오류 또는 Outbox 실패 시 JDBC 차감 롤백을 PostgreSQL에서 검증한다. H2에서 대체 SQL이 통과한 결과로 판단하지 않는다.
- **채택 기준**  같은 시작 재고, SKU 분포, Hikari 풀, 도착률로 서로 다른 키의 인기 상품 주문과 분산 주문을 각각 실행한다. 예열 후 A/B와 B/A 순서로 반복하고, 포화 전후 구간을 유지해 **성공한 신규 주문 TPS**, 정상 주문 p95/p99, 품절률, 미시작 반복, DB 락 대기, 풀 대기를 비교한다. 정합성 통과 후 반복 측정에서 개선이 재현돼야 채택한다. 조건부 UPDATE에서도 같은 행의 쓰기는 커밋까지 경합한다.
- `@DynamicUpdate` 효과를 알고 싶으면 JPA 기준선에 그것만 추가해 별도로 실험한다. 직접 작성한 재고 SQL은 이미 한 컬럼을 변경한다. HOT 비율은 `n_tup_hot_upd / n_tup_upd`의 구간 증가분과 페이지 여유 공간을 함께 보고, WAL 감소를 주장할 때만 WAL 바이트를 계측한다. [PostgreSQL HOT 조건](https://www.postgresql.org/docs/16/storage-hot.html)
- **중복 요청용 키 락은 조건부 후속 작업.** 같은 HTTP 도착률에서 중복 비율을 바꿨을 때 일반 구매자의 지연과 풀 대기가 반복해서 커지면 회원과 키 단위의 직렬화를 별도 실험한다. 키를 기다리는 커넥션과 정상 신규 주문에 추가되는 DB 작업까지 비교한다. 원자 재고 갱신 실험과 분리한다.

## 4순위 | 검색 반영, 데이터 생성, 실험 운영

1. **Outbox의 반영 지연을 관찰한다.** 부분 실패 재시도와 버전 기반 역순 방지는 구현돼 있다. 여기에 대기 상품 수, 가장 오래 대기한 시간, 처리 및 실패 건수를 추가한다. 현재 `available_at`은 임대와 재시도 때 갱신되므로 최초 대기 시각과 구분한다. 상품 ID나 멱등성 키를 메트릭 라벨로 쓰지 않는다.

   **완료 기준**  실행 중 ES 중단 → 상품 변경 및 주문 → ES 복구에서 DB 커밋, 적체 증가와 해소, 최종 재고 및 삭제 반영을 확인한다. 서비스 재시작 시 ES와 Redis 의존성은 별도 점검한다. [Outbox 저장소](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductSearchOutbox.java)와 [기동 설정](../docker-compose.yml)
2. **피드 캐시는 실제 적중과 미스 비용으로 평가한다.** 인기 피드, 필터 조합, 희소 검색어, 3페이지 이후 탐색을 따로 기록한다. 같은 입력으로 Redis ON/OFF, 냉간/예열 후 적중률, 사용 메모리, 원본 검색 호출 수와 p95/p99를 비교한다. PG와 ES 전환이나 검색 규칙 변경 시에는 이전 결과가 남지 않도록 키 공간 또는 실험 환경을 분리한다. Redis 중단 시 피드 오류 또는 우회 정책을 정하고 우회 부하에서 DB/ES가 견디는지 확인한다. [피드 캐시 정책](../backend/src/main/java/com/marketengine/backend/common/config/RedisCacheConfig.java)
3. **Flyway와 벤치마크 준비를 구분한다.** 빈 DB의 기본 1,000만 건 시드는 로컬 기동 시간을 좌우한다. 적용된 V8/B9/V10과 기존 볼륨을 보존하고, 다음 마이그레이션부터는 스키마 변경과 실험 데이터 생성 책임을 분리할 경로를 마련한다. 개발과 CI는 작은 카탈로그, 분포 검증은 10만 건, 최종 부하는 1,000만 건으로 명시한다.

   **완료 기준**  빈 DB 설치와 사용 중 DB 업그레이드 양쪽이 통과하고 기존 상품과 주문이 보존된다. 시드 직후의 분포 검사와 부하 이후 주문별 재고 대사는 별도로 수행한다. [시드 및 대사 절차](DUMMY_DATA_DESIGN.md)
4. **재색인은 복구까지 검증한다.** 현재 오프라인 재색인에서 쓰기 정지, Outbox 비우기 또는 워커 중단, 건수 대조에 더해 가격, 재고, 날짜의 표본 일치와 중간 실패 후 재실행을 확인한다. 온라인 재색인이 필요해지면 새 인덱스 생성, 적재 중 변경분 반영, 검증, alias 전환, 복구 순서를 설계한다. [재색인 코드](../backend/src/main/java/com/marketengine/backend/product/infrastructure/search/ProductBulkReindexer.java)

## 공통 작업 | CI, API 접근 범위, 포트폴리오 근거

- **리팩토링은 업무 규칙과 저장 기술의 경계에서 한다.** 주문의 상태 전이, 점유 수량, 단가 계산은 주문 도메인 한 곳에서 결정하고 서비스가 잠금 순서, 트랜잭션과 Outbox를 조정한다. 재고의 조건부 SQL은 저장소에서 감싸 PostgreSQL 결과를 수량 및 단가로 반환한다. 검색은 기존 `ProductListSearcher`의 두 구현을 유지하고 공통 조회 조건을 전달한다. 프론트와 k6에는 동일한 요청 예시를 제공해 멱등성 키 누락 같은 계약 이탈을 잡는다. 각 변경은 기존 API를 통한 회귀 테스트와 함께 독립적으로 병합한다.
- **첫 PR부터 검증 경로를 확장한다.** 현재 GitHub Actions는 Gradle 테스트만 수행한다. 프론트 변경에는 `npm ci`, lint, build와 브라우저 주문 스모크를 연결한다. DB/ES 의존 변경에는 Docker 사용 가능한 CI에서 핵심 Testcontainers 테스트의 실행 건수와 skipped를 확인한다. Redis 직렬화와 만료는 실제 Redis로 검증한다. PR에서는 작은 데이터로 정확성을 검사하고 대용량 부하는 전용 환경에서 실행한다. [현재 CI](../.github/workflows/ci.yml)
- **회원과 관리 API의 공개 범위를 결정한다.** 현재 화면은 회원 1번으로 주문하고 회원 요청 DTO가 `passwordHash`를 직접 받는다. 로컬 데모는 가상 회원과 접근 범위를 문서화하고 DB, Redis, ES, Grafana 노출 범위를 확인한다. 외부 공개를 선택하면 인증된 회원에서 주문 소유자를 결정하고 타인의 주문 조회, 변경, 취소 및 상품 관리 권한을 검증한다. 비밀번호를 사용하는 경우 서버가 해시한다. 이메일 동시 가입과 주문이 있는 회원 삭제의 알려진 제약 충돌에는 안정된 409 응답을 정의한다.

   **공개 전 완료 기준**  소유자, 타인, 관리자 권한 테스트와 데모 계정/운영 설정 분리가 통과한다. [회원 계약](../backend/src/main/java/com/marketengine/backend/member/api/MemberDtos.java)과 [회원 서비스](../backend/src/main/java/com/marketengine/backend/member/application/MemberService.java)
- **벤치마크 기록을 재현 가능하게 한다.** 사용 시나리오, 커밋과 미커밋 변경 여부, B9 시드 및 입력 파일 해시, DB/ES/Redis 설정, 도착률, 구간별 표본 수, 반복 순서와 원본 결과를 함께 보관한다. 고정 상품 ID를 쓰는 구형 k6는 과거 결과용으로 표시하고 신규 실험은 현재 대상 목록에서 선택한다. Grafana의 HTTP 200 주문 요청률에는 성공한 재시도가 포함되므로, 커밋된 신규 주문 TPS는 실행 키로 DB와 대조한다. 피드 캐시 적중 RPS, ES 미스 지연, DB SQL 실행 시간, 전체 재색인 시간은 별도 지표다. [부하 시나리오](K6_SCENARIOS.md)와 [주문 대시보드](../observability/grafana/dashboards/order-api-tps.json)
- **README의 성과를 정리한다.** 결제 연동은 현재 범위 밖이므로 주문 중복 방지와 재고 보장으로 표현한다. 과거의 TPS, 트라이그램 검색 성능, 캐시 적중률에는 측정 당시 버전과 조건을 붙인다. 워커 8개로 기록한 8분 27초는 Gradle 전체 시간이고, 같은 실행의 ES 재색인 자체는 5분 14.815초였다. 현재 구현과 일치하는 재현 명령이나 근거가 있는 수치부터 대표 성과로 게시한다. [기존 실측 내역](IMPROVEMENT_LOG.md)

Kafka, 온라인 재색인, 무중단 배포와 Kubernetes는 후속 후보로 둔다. Outbox 적체, 허용 중단 시간과 배포 요구처럼 도입 여부를 결정할 근거가 생길 때 다음 실험으로 올린다. 2계층 캐시와 ES 힙 기본값 확대는 2026-11-11 후속 단계에 적용했다.
