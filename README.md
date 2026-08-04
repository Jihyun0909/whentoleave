# 몇시에 나가

지하철로 출퇴근/이동할 때 "막차 놓치지 않으려면 몇 시에 출발해야 하는지"를 바로 알려주는 개인 프로젝트입니다.

## 왜 만드는가

기존 지도 앱(카카오맵, 네이버지도 등)에서 막차 시간을 확인하려면 출발 시각을 계속 바꿔가며 "이 시간엔 가지네, 이 시간엔 안 가지네"를 수동으로 반복 검색해야 합니다. 이 서비스는 출발지/도착지만 입력하면 **"OO시 OO분까지는 출발해야 막차로 도착 가능"**을 한 번에 계산해서 보여줍니다.

## MVP 범위

지금 만드는 v1은 의도적으로 범위를 좁혔습니다. 아래는 스코프에 포함/제외된 것과 그 이유입니다.

| 범위 | 포함 여부 | 이유 |
|---|---|---|
| 지하철 전용 경로 | ✅ v1 | 버스는 고정 시간표가 아니라 실시간 도착정보 API를 별도로 타야 해서 데이터 소스 자체가 다름. 개발 범위를 지키기 위해 우선 제외 |
| 버스 포함 환승 경로 | ⏭ v1.1 이후 | |
| "늦어도 몇 시 출발" 계산 | ✅ v1 | 핵심 pain point |
| "목표 도착시간 → 역산 출발시각" | ⏭ v1.1 | 같은 엔진 위에서 계산 방향만 다름, 나중에 추가 |
| 웹(Thymeleaf) | ✅ v1 | 별도 프론트/앱 배포 없이 가장 빠르게 완성 가능 |
| PWA | ⏭ 향후 | 앱스토어 등록비($99/yr Apple, $25 Google) 없이 "앱처럼" 쓸 수 있는 가장 저렴한 확장 경로 |
| 네이티브 앱(Flutter 등) | ⏭ 필요해지면 | 백엔드를 API 우선 구조로 짜뒀기 때문에 클라이언트만 새로 붙이면 됨 |

## 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어/프레임워크 | Java 21 (LTS) + Spring Boot 4 | 금융권 백엔드 개발자 취업 준비와 방향 일치, 실무 표준 |
| ORM | Spring Data JPA | |
| DB | PostgreSQL | 무료 티어 풍부, 실무 표준 |
| 뷰 | Thymeleaf (서버사이드 렌더링) | 별도 프론트 배포/CORS 신경 안 써도 되게 MVP 단순화 |
| 외부 데이터 | [ODsay Lab API](https://lab.odsay.com) | 대중교통 환승 길찾기 + 지하철역 시간표(막차 포함)를 모두 제공하는 무료 API (일 1,000회 이하 호출 제한) |
| 빌드 도구 | Gradle | |
| 문서화 | springdoc-openapi (Swagger) | `/api/**` 엔드포인트 자동 문서화 |

## 아키텍처

### 패키지 구조

Web(화면)과 API(JSON)를 물리적으로 분리하고, 비즈니스 로직은 어느 쪽에도 종속되지 않게 설계합니다. 이렇게 해두면 나중에 앱 클라이언트를 붙일 때 `service` 계층을 한 줄도 안 건드려도 됩니다.

```
com.example.transit
 ├─ web/          # Thymeleaf 컨트롤러 (화면 담당, 얇게 유지)
 ├─ api/          # @RestController, /api/v1/** JSON 응답
 ├─ service/      # 순수 비즈니스 로직 (HTTP 개념을 몰라야 함)
 ├─ domain/       # JPA Entity
 └─ repository/   # Spring Data JPA Repository
```

**원칙**
- `service` 패키지 안에는 `HttpServletRequest`, `Model` 등 HTTP 관련 타입이 절대 들어가지 않는다.
- API 응답에는 Entity를 직접 노출하지 않고 DTO로 감싼다.
- 인증 기능이 필요해지면 세션 기반이 아니라 JWT로 간다 (앱 클라이언트 재사용을 위해).

## 외부 API 연동 (ODsay)

### 1. 경로 탐색 — `searchPubTransPathT`

출발지/도착지 좌표(SX, SY, EX, EY)로 환승 경로를 조회합니다. 응답의 `subPath` 배열 중 `trafficType:1`(지하철) 구간에서 `stationID`, `wayCode`, `sectionTime`을 추출해 사용합니다.

- ⚠️ 이 API는 "현재 기준 소요시간"만 제공하며, 절대 시각(몇 시 몇 분)이나 막차 여부는 포함하지 않습니다.

### 2. 지하철역 시간표 — `searchSubwaySchedule`

역 ID + 방향(wayCode)으로 해당 역의 하루 전체 시간표를 조회합니다. `weekdaySchedule` / `saturdaySchedule` / `holidaySchedule`로 요일 유형이 분리되어 있고, 각 열차 항목에 `firstLastFlag`(0:일반, 1:첫차, 2:막차)가 명시됩니다.

**구현 시 주의할 점 (실제 API 테스트로 발견한 이슈)**

1. **24시 초과 표기**: `departureTime`이 `"24:35"`처럼 24시를 넘는 값으로 옵니다. 파싱 시 hour가 24 이상이면 `hour - 24`로 바꾸고 날짜를 +1일 처리해야 합니다.
2. **분기 노선의 막차 다중 존재**: 4호선처럼 노선이 갈라지는 경우, 목적지(종착역)별로 `firstLastFlag:2`가 여러 개 나옵니다 (예: 오이도행/안산행/금정행/사당행/서울역행 막차가 전부 따로 존재). "내가 타야 할 방향으로 가는 마지막 열차"를 목적지 기준으로 골라야 하며, 단순히 배열의 마지막 값을 쓰면 틀릴 수 있습니다. v1에서는 후보 중 가장 늦은 시각을 채택하되, **배포 전 서울교통공사 공식 막차시간표와 주요 노선 몇 개를 수동 대조 검증**하는 것을 인수 테스트에 포함합니다.

## 핵심 알고리즘 — 역산 로직 (의사코드)

여러 번 환승하는 경로에서, 가장 마지막 지하철 구간부터 거꾸로 계산해 최종 출발 마감 시각을 구합니다.

```
legs = 길찾기 API에서 추출한 지하철 구간 리스트 (정순)
requiredArrival = null  // 마지막 구간은 다음 제약이 없음

for leg in reversed(legs):
    candidates = DB에서 (leg.stationId, leg.wayCode, todayDayType)로 조회
    lastTrain = candidates 중 조건에 맞는 가장 늦은 departure_time

    if requiredArrival != null:
        deadline = requiredArrival - leg.sectionTime
        사용할 시각 = min(lastTrain.departureTime, deadline)
    else:
        사용할 시각 = lastTrain.departureTime

    requiredArrival = 사용할 시각 - 환승/도보 버퍼

최종 답 = requiredArrival  // 첫 승차역에서 몇 시까지 타야 하는지
```

## DB 스키마

외부 API 호출 횟수를 아끼고 응답 속도를 높이기 위해 막차 시간표를 캐싱합니다. 다만 **매일 전체 역을 배치로 미리 긁는 방식은 쓰지 않습니다** — 수도권 역이 700개가 넘어서 그것만으로 무료 호출 한도(일 1,000회)를 소진합니다. 대신 **실제 요청이 들어온 역만, 그날 처음 조회될 때 캐싱**하는 lazy cache-aside 방식을 씁니다 (`SubwayScheduleCacheService`). 지하철 시간표는 사실상 고정값이라, 같은 요일유형에 대해 한 번 캐싱하면 그 값을 계속 재사용합니다 (자정 넘어 다음 날짜가 돼도 같은 요일유형이면 재조회하지 않음 — TTL/무효화 정책은 이후 확장 기능에서 다룸).

```sql
CREATE TABLE subway_last_train (
    id BIGSERIAL PRIMARY KEY,
    station_id INT NOT NULL,
    way_code SMALLINT NOT NULL,            -- 1:상행, 2:하행
    day_type VARCHAR(10) NOT NULL,         -- WEEKDAY, SATURDAY, HOLIDAY
    end_station_name VARCHAR(50) NOT NULL, -- 목적지 (분기 노선 대응)
    departure_time TIME NOT NULL,          -- 24시 초과분은 24를 빼고 저장 (next_day로 구분)
    next_day BOOLEAN NOT NULL DEFAULT false, -- departure_time이 "다음날 이 시각"인지 여부 (예: 24:35 -> 00:35 + next_day=true)
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (station_id, way_code, day_type, end_station_name)
);
```

`next_day`가 필요한 이유: `TIME` 컬럼 하나만으로는 "0시 35분"이 오늘인지 다음날인지 구분이 안 되는데, 자정을 넘나드는 막차 역산 계산에서는 이 구분이 정확도에 직결됩니다.

## 배포 전략 (무료 우선)

| 항목 | 선택 | 비고 |
|---|---|---|
| 서버 | Oracle Cloud Always Free (ARM 4코어/24GB) | 영구 무료, Render/Railway 무료 티어보다 상시 운영에 안정적 |
| DB | 서버와 같은 VM에 PostgreSQL 설치 | 별도 DB 호스팅 비용 없음 |
| 도메인 | DuckDNS 무료 서브도메인 | 필요시 로마자 표기 도메인 별도 구매 검토 |
| CI/CD | GitHub Actions | public repo 무제한 무료 |
| 비용이 발생할 수 있는 지점 | ODsay 무료 호출 한도(일 1,000회 이하) 초과 시 | 시간표 캐싱으로 호출량을 최소화해 방어 |

## 개발 환경

- **JDK**: Eclipse Temurin 21 (LTS)
- **IDE**: IntelliJ IDEA Community
- **DB (로컬)**: Docker 컨테이너로 PostgreSQL 실행 (`5433` 포트 — 다른 프로젝트가 기본 `5432`를 이미 쓰고 있어서 변경)
- **OS**: Windows 11 Home
- ⚠️ **프로젝트 경로는 한글/공백 없이 유지** (`C:\Users\quswl\projects\whentoleave`) — Windows 로케일이 UTF-8이 아니면(MS949 등) Gradle 테스트 워커가 한글/공백 포함 경로에서 `ClassNotFoundException`을 냅니다. JVM 옵션으로 못 고치는 문제라 경로 자체를 ASCII로 유지하는 게 유일한 실용적 해결책입니다.

## Git 전략

혼자 진행하는 프로젝트라 Git Flow(`develop`/`release`/`hotfix` 다중 브랜치)는 쓰지 않습니다. Git Flow는 여러 명이 동시에 다른 기능을 작업하면서 정해진 릴리즈 일정에 맞춰 배포를 조율해야 할 때 가치가 있는데, 혼자 개발하고 계속 배포하는 상황에서는 그 조율 대상 자체가 없어서 관리 부담만 늘어납니다.

- `main` 브랜치 — 항상 정상 동작하는 상태 유지
- 큰 기능 단위로 **GitHub 이슈 생성 → `feature/*` 브랜치 생성 → 구현 → `main`에 머지(이슈 종료) → 브랜치 삭제**
  - 이슈를 먼저 만들어두면 왜 이 브랜치를 팠는지, 어떤 논의가 있었는지가 기록에 남아서 나중에 포트폴리오 설명할 때도 근거가 됨
  - 예: `feature/route-cache`, `feature/idempotent-notification`, `feature/circuit-breaker`
- 커밋 메시지는 [Conventional Commits](https://www.conventionalcommits.org/) 스타일 유지 (`feat:`, `fix:`, `chore:`, `docs:`, `test:`)

## 로드맵

1. ~~기획 (MVP 범위 확정)~~
2. ~~API 리서치 — ODsay 길찾기/시간표 API 스파이크 완료~~
3. ~~개발 환경 세팅~~
4. ~~DB 스키마 확정 + 시간표 캐싱 서비스 구현~~ (`SubwayLastTrain`, `SubwayScheduleCacheService`, ODsay 클라이언트)
5. 핵심 서비스 로직(역산 알고리즘) 구현 + 단위 테스트 (진행 예정)
6. API/화면 구현
7. 본인 실제 출퇴근 경로로 수동 검증
8. 배포 (Oracle Cloud + GitHub Actions)
9. (v1.1) 목표 도착시간 역산 기능
10. (향후) 버스 포함 경로, PWA
11. (향후) 동시성/안정성 강화 기능 3종 — 상세 설계는 [docs/future-features-concurrency.md](docs/future-features-concurrency.md) 참고
    - 경로 탐색 캐시 + 캐시 스탬피드 방어
    - 경로 저장 + 막차 임박 알림 (멱등성 처리, 인증 도입)
    - 외부 API(ODsay) 장애 대응 — Resilience4j Circuit Breaker
