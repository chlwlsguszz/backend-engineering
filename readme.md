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

### 1. 1,000만 건 대용량 데이터 검색 성능 극대화

- QueryDSL을 도입하여 복잡한 필터 검색 코드를 효율적으로 개선했습니다.
- 
    
    `totalCount` 연산 병목을 무한 스크롤 형태의 `Slice` 방식으로 해결하여 조회 속도를 1400ms에서 25ms로 대폭 단축했습니다.
    
- 
    
    `EXPLAIN ANALYZE`로 병목을 식별하고 B-tree 인덱스를 적용하여 최신순 검색을 1842ms에서 0.08ms로 개선했습니다.
    
- 
    
    `pg_trgm` 역색인 인덱스와 `word_similarity` 함수로 오타 및 유사도 검색 속도를 5000ms에서 0.8ms로 단축했습니다.
    
- RDBMS 환경의 힙 스캔 병목 한계 극복을 위해 ElasticSearch 마이그레이션을 단행했습니다.
- JDBC 배치를 활용해 1,000만 건의 데이터를 28분 만에 초기 적재(Reindex)했습니다.

### 2. Redis 다중 캐싱을 통한 검색 부하 분산

- 
    
    `OffsetDateTime` JSON 직렬화 에러를 `JavaTimeModule` JSR-310 지원 설정을 통해 완벽하게 해결했습니다.
    
- 빈도가 높은 검색 필터 조합을 식별하여 Redis에 캐싱 처리하고 캐시 적중률 95%를 달성했습니다.
- k6 부하 테스트 결과 동시 접속자 3만 명 수준인 최대 6,350 RPS를 기록했습니다.
- 5000 RPS 구간에서도 p99 지연율 약 200ms의 안정적인 처리량을 확보했습니다.

### 3. 고동시성 환경의 주문/결제 무결성 보장

- 
    
    `SELECT FOR UPDATE` 쿼리를 통한 비관적 락(Pessimistic Lock)으로 동시 접근에 의한 재고 감소 누락을 원천 방지했습니다.
    
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

개인 블로그에 모든 과정을 기록했습니다.

[chlwlsguszz.tistory.com/category/백엔드_엔지니어링_일지](https://chlwlsguszz.tistory.com/category/%EB%B0%B1%EC%97%94%EB%93%9C%20%EC%97%94%EC%A7%80%EB%8B%88%EC%96%B4%EB%A7%81%20%EC%9D%BC%EC%A7%80)
