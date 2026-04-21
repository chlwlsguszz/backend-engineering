# 기능·DB 설계 초안 (v0)

Market Engine 학습용 **최소 마켓** 기준입니다. 검색·결제·장바구니 영속화 등은 이 초안에서 제외하거나 후속 단계로 둡니다.

---

## 1. 범위 (이번 초안)

| 구분 | 포함 | 비고 |
|------|------|------|
| 회원 | 가입·조회·수정·(삭제) | 인증(JWT)은 API 레이어에서 추후 연동, DB에는 자격 증명 저장 공간만 둠 |
| 상품 | 등록·조회·수정 | **판매자 다중 테넌트**는 단순화: 단일 셀러 또는 `seller_id`만 FK로 확장 가능 |
| 재고 | 상품별 수량 | 주문 시 차감은 트랜잭션·동시성은 3단계에서 심화 |
| 주문 | 주문 생성·조회·(상태 변경) | 결제 연동 없음: `CREATED` → `CONFIRMED` 정도로만 모델링 |

---

## 2. 도메인 경계 (개략)

- **회원(Member)**: 사용자 식별·프로필
- **카탈로그(Product)**: 판매 단위(이름, 가격)
- **재고(Inventory)**: 상품 1개당 가용 수량 (상품과 1:1로 두어 단순화)
- **주문(Order)**: 주문 헤더 + 주문 상세(OrderLine)

---

## 3. ER 개요 (텍스트)

```
Member 1 ── * Order
Product 1 ── 1 Inventory (quantity)
Product 1 ── * OrderLine * ── 1 Order
```

- 한 회원이 여러 주문을 가진다.
- 한 상품은 재고 한 행을 가진다(1:1).
- 한 주문은 여러 주문 상세(라인)를 가진다.
- 주문 라인은 주문 시점 **단가 스냅샷**을 저장한다(가격 변경에 대비).

---

## 4. 테이블 초안

### 4.1 `members`

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | `BIGSERIAL` | PK | |
| email | `VARCHAR(255)` | `UNIQUE NOT NULL` | 로그인 식별자 |
| password_hash | `VARCHAR(255)` | `NULL` 허용 | JWT 도입 전까지 nullable 가능 |
| name | `VARCHAR(100)` | `NOT NULL` | 표시 이름 |
| created_at | `TIMESTAMPTZ` | `NOT NULL` | |
| updated_at | `TIMESTAMPTZ` | `NOT NULL` | |

인덱스: `email` (unique 이미 인덱스 역할).

---

### 4.2 `products`

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | `BIGSERIAL` | PK | |
| name | `VARCHAR(255)` | `NOT NULL` | |
| description | `TEXT` | | 선택 |
| price_amount | `NUMERIC(19,4)` | `NOT NULL CHECK (price_amount >= 0)` | 통화는 고정 가정 시 컬럼 생략 가능 |
| status | `VARCHAR(32)` | `NOT NULL` | 예: `ACTIVE`, `DISCONTINUED` |
| created_at | `TIMESTAMPTZ` | `NOT NULL` | |
| updated_at | `TIMESTAMPTZ` | `NOT NULL` | |

검색·ES는 4단계에서 별도 설계.

---

### 4.3 `inventories`

상품과 **1:1**. 재고만 별도 테이블로 두어 이후 **락·버전 컬럼** 추가가 쉽게.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| product_id | `BIGINT` | PK, FK → `products(id)` | |
| quantity | `INT` | `NOT NULL CHECK (quantity >= 0)` | 가용 재고 |
| updated_at | `TIMESTAMPTZ` | `NOT NULL` | |

추후(3단계): `version` (낙관적 락) 또는 별도 재고 이력 테이블 검토.

---

### 4.4 `orders`

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | `BIGSERIAL` | PK | |
| member_id | `BIGINT` | `NOT NULL`, FK → `members(id)` | |
| status | `VARCHAR(32)` | `NOT NULL` | 예: `CREATED`, `CONFIRMED`, `CANCELLED` |
| total_amount | `NUMERIC(19,4)` | `NOT NULL CHECK (total_amount >= 0)` | 라인 합계와 정합 |
| idempotency_key | `VARCHAR(64)` | `NULL` 허용, 멱등 도입 시 **부분 유니크 인덱스**(`WHERE idempotency_key IS NOT NULL`) 권장 | 3단계 |
| created_at | `TIMESTAMPTZ` | `NOT NULL` | |
| updated_at | `TIMESTAMPTZ` | `NOT NULL` | |

인덱스: `(member_id)`, `(created_at)` 조회용은 부하 보고 추가.

---

### 4.5 `order_lines`

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | `BIGSERIAL` | PK | |
| order_id | `BIGINT` | `NOT NULL`, FK → `orders(id)` | `ON DELETE CASCADE` 여부는 정책에 따라 |
| product_id | `BIGINT` | `NOT NULL`, FK → `products(id)` | |
| unit_price | `NUMERIC(19,4)` | `NOT NULL` | 주문 시점 단가 |
| quantity | `INT` | `NOT NULL CHECK (quantity > 0)` | |
| line_total | `NUMERIC(19,4)` | `NOT NULL` | `unit_price * quantity`와 일치 검증은 앱/제약 |

인덱스: `(order_id)`.

---

## 5. 핵심 유스케이스와 DB

| 유스케이스 | DB에서 중요한 점 |
|------------|------------------|
| 회원 CRUD | `email` 유일성 |
| 상품 CRUD | `status`로 판매 중단 표현 가능 |
| 재고 조회/수정 | `inventories.quantity` 단일 진실 공급원 |
| 주문 생성 | `orders` + `order_lines` insert, 재고 차감은 **동일 트랜잭션**으로 묶을 계획(구현 단계에서 처리) |
| 주문 조회 | `member_id` + `order_id` 권한 검사는 서비스 레이어 |

---

## 6. 의도적으로 미룬 것

- 결제, 배송, 장바구니 세션, 쿠폰, 다중 창고, 예약 재고
- 멱등 키·재고 동시성·이력 테이블 → **3단계**에서 상세화
- 상품 검색 인덱스·비정규 필드 → **4단계**

---

## 7. 다음 작업 제안

1. 이 초안에 대해 **컬럼명·상태값(enum 문자열)** 확정  
2. **Flyway/Liquibase** 마이그레이션으로 스키마 버전 관리  
3. JPA 엔티티 매핑 시 양방향 연관은 최소화(주문 → 라인은 단방향 + 편의 메서드 등)

---

문서 버전: v0 (초안). 스키마 변경 시 본문과 마이그레이션을 함께 갱신합니다.
