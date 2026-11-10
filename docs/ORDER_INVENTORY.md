# 주문 변경과 재고 처리

`POST /api/orders`는 주문, 재고 차감, 검색 Outbox를 같은 PostgreSQL 트랜잭션에 커밋한다. 재고가 충분한 판매 가능 상품을 조건부 UPDATE하고, 같은 SQL의 `RETURNING price_amount`에서 주문 단가를 얻는다. 품절과 상품 없음은 기존 API처럼 구분한다. [재고 SQL](../backend/src/main/java/com/marketengine/backend/product/infrastructure/ProductInventory.java)

- `CREATED`와 `CONFIRMED`는 주문 수량을 점유한다. 두 상태에서 수량을 변경하면 차이만큼 재고를 추가 차감하거나 복원한다. 금액은 최초 단가로 산출한다. 확정 주문을 다시 생성 상태로 바꾸는 요청은 409다.
- `DELETE /api/orders/{id}`는 취소다. 행과 생성 멱등성 키를 보존하며 반복 취소는 성공한다. PUT으로 취소할 때는 현재 주문 수량을 유지한다. 취소 후 재활성화와 수량 변경은 409다. 주문 행, 상품 행 순서로 잠그고 실제 재고가 바뀐 경우 Outbox를 기록한다.
- V11부터 최초 요청의 수량을 저장한다. 같은 생성 요청을 재시도하면 변경 또는 취소된 주문의 현재 상태를 반환한다. 이전 주문의 수량 변경 이력은 기존 스키마에 없으므로 V11 적용 시점의 수량을 기존 주문의 재시도 비교 기준으로 사용한다. 이미 물리적으로 삭제한 주문은 복원할 수 없다. 배포 시 기존 쓰기 프로세스를 중지한 뒤 새 버전에서 마이그레이션을 수행한다.
- 상품 상세의 `version`을 `PUT /api/products/{id}` 본문에 함께 보낸다. 재고 및 상품 정보의 모든 변경이 이 버전을 사용하므로 판매자가 오래된 화면에서 재고를 덮어쓰면 409가 된다. 판매 중단 상품은 재입고나 취소에서도 중단 상태를 유지한다. 판매 중인 상품은 재고가 0이면 품절, 양수면 판매 가능으로 표시한다.

## 짧은 PostgreSQL 비교

2026-11-10 로컬 Docker의 PostgreSQL 16에서 별도 볼륨과 네트워크 연결 없는 임시 DB를 만들었다. 같은 8개 클라이언트, 2개 작업 스레드, prepared 모드, 시드 42에서 각 조건마다 상품과 재고를 초기화했다. 1초 예열과 3초 측정으로 잠금 조회 후 UPDATE와 조건부 UPDATE RETURNING을 A/B 및 B/A 순서로 비교했다. 각 거래는 한 단위의 재고를 차감했고, 모든 실행에서 DB 수량 변화와 완료 거래 수가 일치했다. [재현 스크립트](../scripts/sql/compare-stock-write.sh)

- 상품 1개에서 첫 비교의 초당 재고 거래 수는 잠금 방식 872, 원자 갱신 1,073이었고 p95는 각각 21.90ms, 17.91ms였다. 반대 순서에서는 원자 갱신 614, 잠금 방식 487이었고 p95는 각각 36.97ms, 47.82ms였다.
- 상품 1,000개에서는 첫 비교가 잠금 방식 2,527, 원자 갱신 3,931이었다. 반대 순서에서는 원자 갱신 4,412, 잠금 방식 5,249로 순위가 달라졌다.

이 수치는 재고 SQL 트랜잭션의 짧은 로컬 측정이다. 실제 HTTP 주문은 회원 확인, 멱등성 확인, 주문 저장과 Outbox까지 수행한다. 이번 채택 근거는 재고 성공 경로의 SQL 한 번과 잠금 경합 조건에서의 결과이며, 정상 주문 HTTP TPS 또는 지연 개선 폭은 별도로 측정한다.

프로젝트 루트 PowerShell에서 같은 DB 실험을 실행하는 명령은 다음과 같다.

```powershell
$bench=(Resolve-Path 'scripts/sql/compare-stock-write.sh').Path; docker run --rm --network none --user postgres --mount "type=bind,source=$bench,target=/benchmark.sh,readonly" postgres:16-alpine sh -c 'tr -d "\r" < /benchmark.sh | sh'
```
