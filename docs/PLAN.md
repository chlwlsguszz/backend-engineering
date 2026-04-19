# Market Engine — 학습·포트폴리오 계획

## 목표

- 백엔드 포트폴리오를 위한 **학습 목적** 프로젝트
- **대용량 트래픽**이 몰리는 환경을 가정하여 **재고 정합성**을 보장하고 **조회 성능**을 최적화하는 마켓 백엔드 엔진 구축
- **대용량 테스트 데이터**를 저장하고 **부하 테스트**로 각종 실험
- 핵심 로직인 **검색**과 **주문 시스템**에 집중

---

## 계획 (단계별)

### 1단계: 기본 아키텍처 뼈대

- 실무에 가까운 API, DB, 디렉토리 설계
- DDD (도메인 분리까지만)
- 예외 처리 (전역 예외 핸들러)

### 2단계: 핵심 기능 구현 (비즈니스) 및 품질 레이어

- 재고 / 주문 기능
- 기본 CRUD API
- 테스트 코드 (Service 중심)
- 로깅 (request/response, error log)
- API 문서 자동화 (Swagger/OpenAPI)
- JWT 보안 관리
- 기초 CI 구축: GitHub Actions + 테스트 자동화

### 3단계: 동시성 / 정합성 문제

- 재고·주문 동시성 해결 (락, 트랜잭션)
- 멱등성 키 도입

### 4단계: 성능 / 검색 최적화

- 검색 속도 및 최적화 (DB index, query 개선)
- Elasticsearch 도입 (검색 고도화)

### 5단계: 비동기 / 확장 아키텍처

- 메시지 큐 비동기 처리 (Kafka / RabbitMQ)

### 6단계: 운영 레벨

- 분산 트래킹 및 모니터링 (trace / log / metrics)
- Graceful shutdown 설계
- Resilience4j (Circuit Breaker)
- CD 고도화: Blue-Green 무중단 배포 (AWS ALB)
- Terraform 인프라 자동화
- Kubernetes (선택 — 과설계라 판단되면 보류)

---

## 기술 스택·버전

| 구분 | 내용 |
|------|------|
| 백엔드 | Spring Boot 3.5.x, Java 21, Gradle |
| 프론트엔드 | Next.js + TypeScript |
| DB | PostgreSQL |
| 백엔드 패키지 | `com.marketengine.backend` |
| 프론트엔드 패키지 | `@marketengine/web` |

---

## 문서 유지

이 파일(`docs/PLAN.md`)을 **단일 소스**로 두고, 단계 진행·범위 변경 시 여기를 갱신합니다.
