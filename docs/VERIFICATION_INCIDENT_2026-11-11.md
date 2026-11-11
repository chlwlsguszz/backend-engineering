# 2026-11-11 검수 정리 중 데이터 삭제 사고

## 현재 상태

Codex가 성능 개선 검수를 실행한 뒤 정리하는 과정에서 기존 `marketengine-catalog-10m` 프로젝트를 잘못 삭제했다. 사용자 요청은 개선과 검수였으며 기존 데이터 삭제는 의도한 작업이 아니었다. 기능 검사 통과와 별개로 이 실행은 데이터 보존에 실패했다.

2026-11-11 10:48:37 KST 무렵 다음 볼륨과 해당 프로젝트의 컨테이너 및 기본 네트워크가 삭제됐다.

- `marketengine-catalog-10m_marketengine-pgdata`: 1,000만 상품 환경의 PostgreSQL 데이터
- `marketengine-catalog-10m_marketengine-esdata`: 해당 환경의 Elasticsearch 데이터

삭제 직전의 DB 백업은 조사한 저장소와 관련 임시 작업 폴더에서 찾지 못했다. 원본은 복구되지 않았으며 같은 이름의 볼륨을 재생성하지 않았다. Docker 디스크 수준의 복구 가능 여부는 확인되지 않았다.

이전 `marketengine-catalog`, `marketengine-catalog-pilot`, `backend-engineering` 프로젝트의 데이터 볼륨은 남아 있다. 생성 시각과 이름만 확인했으며 현재 데이터와의 일치 여부를 확인하거나 기동하지 않았다. B9 시드로 초기 상품과 초기 주문은 다시 생성할 수 있지만, 이후 주문과 수정 이력을 되살리는 백업은 아니다.

## 확인한 원인

검수는 `commerce-smoke-835da601dfad`라는 별도 프로젝트에서 정상 실행됐다. 문제는 마지막 정리 단계였다.

1. 로컬 JAR overlay가 필수 환경 변수 `BACKEND_TEST_JAR`를 참조했다.
2. `finally`가 이 환경 변수를 먼저 원래 값으로 복원한 뒤 Compose `down -v`를 실행했다.
3. 필수 변수가 사라져 Compose 설정 해석이 실패했다.
4. 설치된 Docker Compose 5.6.0의 프로젝트 이름 처리 경로는 설정 해석 실패 시 `COMPOSE_PROJECT_NAME`으로 돌아갈 수 있었다. 이 값은 저장소 `.env`의 기존 프로젝트 이름이었다.
5. 따라서 명시한 검수용 `-p`와 다르게 기존 프로젝트가 삭제 대상이 됐다. 삭제 로그와 Docker VM 로그의 프로젝트 필터에서 확인했다.

정리 코드가 설정 오류 시에도 안전하다고 가정한 것이 잘못이었다. [확인한 Compose 5.6.0 소스](https://github.com/docker/compose/blob/v5.6.0/cmd/compose/compose.go)의 `projectOrName()`이 이 경로를 포함한다.

## 조치

- 추가 삭제를 중단하고 사용자에게 영향과 미복구 상태를 알렸다.
- 살아 있는 검수 컨테이너 4개는 정확한 프로젝트 라벨과 이름을 확인한 후 ID로 중지했다. 검수 데이터는 보존했다.
- 검수 스크립트의 Compose `down -v`와 이미지 및 네트워크 자동 삭제를 제거했다.
- 새 중지 함수는 검수 전용 프로젝트 이름 형식, 실행별 검수 라벨, Compose 프로젝트 라벨과 컨테이너 이름을 모두 확인한다. 전체 대상 검증을 끝낸 뒤 ID로만 `docker stop`을 호출한다.
- 실행 중 프로젝트 환경 변수도 검수용으로 고정하고, 중지가 끝난 뒤 환경 변수를 복원한다.
- 잘못된 프로젝트, 라벨 불일치, 여러 대상 중 일부 불일치를 실제 Docker 없이 모의 테스트한다. PowerShell 5.1과 7에서 각각 11개 검사가 통과했다. 자동 데이터 삭제는 제공하지 않는다.

## 검수 증거

삭제 전 기능 검수는 통과했다. 전체 백엔드 검사 122개, 브라우저 검사 5개, 짧은 k6 검색 검사와 캐시 및 장애 복구 검사가 포함된다. 대규모 성능 개선 수치는 이번에 측정하지 않았다.

로컬 증거 위치:

- 전체 테스트 XML: `C:\Users\chlwl\AppData\Local\Temp\marketengine-full-suite-20261111-104525`
- 성공 검사와 삭제 로그를 포함한 원본: `C:\Users\chlwl\AppData\Local\Temp\marketengine-performance-smoke-20261111-104612.log`
- 검수 환경의 지표: `C:\Users\chlwl\AppData\Local\Temp\marketengine-metrics-20261111-104833-883`

임시 경로의 파일은 장기 보존 저장소가 아니다. 코드 커밋이나 이 문서는 삭제된 DB의 백업을 포함하지 않는다.
