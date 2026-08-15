# When To Leave

지하철로 출퇴근/이동할 때 "막차 놓치지 않으려면 몇 시에 출발해야 하는지"를 바로 알려주는 개인 프로젝트입니다.

## 왜 만드는가

기존 지도 앱(카카오맵, 네이버지도 등)에서 막차 시간을 확인하려면 출발 시각을 계속 바꿔가며 "이 시간엔 가지네, 이 시간엔 안 가지네"를 수동으로 반복 검색해야 합니다. 이 서비스는 출발지/도착지만 입력하면 "OO시 OO분까지는 출발해야 막차로 도착 가능"을 한 번에 계산해서 보여줍니다.

## MVP 범위

지금 만드는 v1은 의도적으로 범위를 좁혔습니다. 아래는 스코프에 포함/제외된 것과 그 이유입니다.

| 범위 | 포함 여부 | 이유 |
|---|---|---|
| 지하철 전용 경로 | ✅ v1 | 버스는 고정 시간표가 아니라 실시간 도착정보 API를 별도로 타야 해서 데이터 소스 자체가 다름. 개발 범위를 지키기 위해 우선 제외 |
| 버스 포함 환승 경로 | ⏭ v1.1 이후 | |
| "늦어도 몇 시 출발" 계산 | ✅ v1 | 핵심 pain point |
| "목표 도착시간 → 역산 출발시각" | ✅ v1.1 | 같은 역산 엔진 재사용 — 마지막 구간의 "제약 없음(막차)"을 "목표 도착시간이 데드라인"으로 바꾸기만 하면 됨 ([이슈 #6](https://github.com/Jihyun0909/whentoleave/issues/6)) |
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
2. **분기 노선의 막차 다중 존재**: 4호선처럼 노선이 갈라지는 경우, 목적지(종착역)별로 `firstLastFlag:2`가 여러 개 나옵니다 (예: 오이도행/안산행/금정행/사당행/서울역행 막차가 전부 따로 존재). "내가 타야 할 방향으로 가는 마지막 열차"를 목적지 기준으로 골라야 하며, 단순히 배열의 마지막 값을 쓰면 틀릴 수 있습니다.
3. **API 키의 `/`를 인코딩 안 하면 인증 실패**: `UriComponentsBuilder.queryParam()`은 URI 스펙상 쿼리 문자열에서 허용되는 `/`를 인코딩하지 않고 그대로 보내는데, ODsay는 그 상태로는 키 인증에 실패합니다 (실사용 중 발견 — 브라우저로 직접 호출하면 되는데 앱에서는 계속 안 됐던 원인). `OdsayClient`는 `URLEncoder`로 직접 완전히 인코딩한 뒤 `URI.create()`로 조립해서 이 문제를 피합니다.
4. **역 이름에 괄호가 붙는 경우**: `searchStation`은 "수유(강북구청)"처럼 부가 정보를 괄호로 붙인 이름을 반환하는 역이 있습니다. 사용자는 "수유"만 입력하지 괄호까지는 모르므로, 비교 전에 양쪽 다 trailing 괄호를 제거하고 정규화해서 매칭합니다 (`StationCandidateResolver`). 다만 "강남"으로 검색했을 때 "강남구청"/"강남대"까지 걸리면 안 되므로, 괄호가 없는 이름끼리는 여전히 완전 일치만 인정합니다.
5. **`firstLastFlag`가 붙지 않은 열차도 특정 환승 마감엔 여전히 유효함** ([이슈 #5](https://github.com/Jihyun0909/whentoleave/issues/5) — 실사용 검증 중 발견): 처음엔 "공식 막차(`firstLastFlag:2/3`)"만 캐싱했는데, 공식 막차가 다음 환승 마감을 놓치더라도 그 앞의 비공식 열차(`firstLastFlag:0`)가 마감을 맞추는 경우가 실제로 있었습니다 (수유→압구정 케이스: 사당행 공식 막차 00:21은 마감을 7분 차로 놓치지만, 그 앞 사당행 23:59는 `firstLastFlag:0`인데도 맞음 — 실제로 카카오맵/네이버지도가 안내하는 값도 이쪽). 그래서 지금은 응답에 있는 항목을 전부 캐싱하고, "마감을 만족하는 가장 늦은 열차"를 계산 시점(`LastDepartureCalculator`)에서 `firstLastFlag`와 무관하게 찾습니다.

## 핵심 알고리즘 — 역산 로직

여러 번 환승하는 경로에서, 가장 마지막 지하철 구간부터 거꾸로 계산해 최종 출발 마감 시각을 구합니다. `LastDepartureCalculator`로 구현되어 있습니다 ([이슈 #1](https://github.com/Jihyun0909/whentoleave/issues/1)).

```
legs = 길찾기 API에서 추출한 지하철 구간 리스트 (정순)
requiredArrival = null  // 마지막 구간은 다음 제약이 없음

for leg in reversed(legs):
    candidates = (leg.stationId, leg.wayCode)의 오늘 요일유형 막차 후보 조회
    lastTrain = candidates 중 가장 늦은 departure_time

    if requiredArrival != null:
        deadline = requiredArrival - leg.rideMinutes
        if lastTrain > deadline:
            return Infeasible("이 막차로는 다음 환승을 놓침")  // 가짜 deadline을 답인 척 반환하지 않는다
        사용할 시각 = lastTrain
    else:
        사용할 시각 = lastTrain

    if leg가 첫 구간이면:
        최종 답 = 사용할 시각  // 첫 승차역에서 몇 시까지 타야 하는지
    else:
        requiredArrival = 사용할 시각 - 환승/도보 버퍼
```

**자정 넘는 시각 비교**: `LocalTime` 값끼리 직접 빼고 비교하면 자정을 넘나들 때 wrap-around 버그가 생기므로, 내부 계산은 전부 "서비스일 기준 분"(0시=0, 24시 이후는 1440 이상)의 정수로 변환해서 처리합니다.

**테스트 전략**: `SubwayScheduleCacheService`에서 `LastTrainLookup` 인터페이스를 분리해서, Mockito 없이 람다 페이크로 `LastDepartureCalculator`를 단위 테스트합니다.

**목표 도착시간 역산(v1.1, 이슈 #6)**: 위 의사코드의 `requiredArrival = null`("마지막 구간은 다음 제약이 없음") 대신, 사용자가 입력한 목표 도착시간을 그 자리에 넣으면 그대로 동작합니다. 마지막 구간도 다른 구간과 똑같이 "데드라인을 만족하는 가장 늦은 후보"를 찾는 로직을 타게 되므로, 알고리즘 자체는 바꿀 게 없고 `LastDepartureCalculator.calculate(legs, targetArrivalMinutes)` 오버로드만 추가했습니다.

## 알려진 한계 — 계획된 시간표 vs 실시간 운행정보

이 계산은 ODsay의 **계획된(정적) 시간표** 기준입니다. 실제 열차는 신호 대기·지연 등으로 계획과 달라질 수 있는데, 이건 두 상황을 구분해서 봐야 합니다.

- **몇 시간 전 미리 계획할 때**(이 서비스의 핵심 사용 시점): 그 시점엔 실시간 정보 자체가 존재하지 않습니다. 열차가 아직 운행을 시작 안 했으니 카카오맵/네이버지도도 이 시점엔 계획된 시간표로만 답합니다 — 미리 계획하는 도구의 근본적 한계이지 이 서비스만의 문제가 아닙니다.
- **출발 직전/이동 중**: 이때는 실시간 정보가 실제로 의미가 있습니다. 카카오맵/네이버지도는 이 구간에서 실시간 도착정보를 보여줍니다.

**MVP 대응(가벼운 보완)**: 화면(6단계 구현 시)에 "이 시각은 계획된 시간표 기준입니다. 출발 전 실시간 도착정보로 한 번 더 확인하세요" 문구를 넣습니다. 계산된 마감 시각보다 살짝 여유를 두고 안내하는 것도 고려합니다.

**향후 대응**: 로드맵 향후 항목 참고(실시간 열차위치정보 연동 — ODsay와는 별도 데이터 소스라 새 연동 작업 필요, 출발이 임박했을 때만 의미 있는 기능이라 조건부로 UI에 끼워 넣어야 함).

## DB 스키마

외부 API 호출 횟수를 아끼고 응답 속도를 높이기 위해 역×방향×요일유형의 시간표를 캐싱합니다. 다만 **매일 전체 역을 배치로 미리 긁는 방식은 쓰지 않습니다** — 수도권 역이 700개가 넘어서 그것만으로 무료 호출 한도(일 1,000회)를 소진합니다. 대신 **실제 요청이 들어온 역만, 그날 처음 조회될 때 캐싱**하는 lazy cache-aside 방식을 씁니다 (`SubwayScheduleCacheService`). 지하철 시간표는 사실상 고정값이라, 같은 요일유형에 대해 한 번 캐싱하면 그 값을 계속 재사용합니다 (자정 넘어 다음 날짜가 돼도 같은 요일유형이면 재조회하지 않음 — TTL/무효화 정책은 이후 확장 기능에서 다룸).

```sql
CREATE TABLE subway_schedule (
    id BIGSERIAL PRIMARY KEY,
    station_id INT NOT NULL,
    way_code SMALLINT NOT NULL,            -- 1:상행, 2:하행
    day_type VARCHAR(10) NOT NULL,         -- WEEKDAY, SATURDAY, HOLIDAY
    end_station_name VARCHAR(50) NOT NULL, -- 목적지 (분기 노선 대응)
    departure_time TIME NOT NULL,          -- 24시 초과분은 24를 빼고 저장 (next_day로 구분)
    next_day BOOLEAN NOT NULL DEFAULT false, -- departure_time이 "다음날 이 시각"인지 여부 (예: 24:35 -> 00:35 + next_day=true)
    first_last_flag INT,                   -- ODsay 원본 값 (0:일반, 1:첫차, 2:막차, 3:첫차&막차) 참고용 — 필터링엔 안 씀
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

`next_day`가 필요한 이유: `TIME` 컬럼 하나만으로는 "0시 35분"이 오늘인지 다음날인지 구분이 안 되는데, 자정을 넘나드는 막차 역산 계산에서는 이 구분이 정확도에 직결됩니다.

원래는 이름이 `subway_last_train`이었고 `firstLastFlag:2/3`인 항목만 저장했는데, 그러면 공식 막차로 태그되진 않았지만 특정 환승 마감엔 여전히 맞는 열차를 놓치는 문제가 있었습니다 ([이슈 #5](https://github.com/Jihyun0909/whentoleave/issues/5), 위 ODsay 연동 주의사항 5번 참고). 그래서 응답 전체를 저장하는 걸로 바뀌면서 `subway_schedule`로 이름도 바뀌었고, 목적지별로 여러 시각이 저장되므로 `(station_id, way_code, day_type, end_station_name)` 유니크 제약도 제거했습니다.

## 배포 전략 (무료 우선)

| 항목 | 선택 | 비고 |
|---|---|---|
| 서버 | Oracle Cloud Always Free (ARM 4코어/24GB) | 영구 무료, Render/Railway 무료 티어보다 상시 운영에 안정적 |
| DB | 서버와 같은 VM에 PostgreSQL 컨테이너로 실행 | 별도 DB 호스팅 비용 없음 |
| 리버스 프록시 / HTTPS | Caddy | Let's Encrypt 인증서 발급·갱신을 자동으로 처리해줘서 별도 certbot 설정이 필요 없음 |
| 도메인 | DuckDNS 무료 서브도메인 | 필요시 로마자 표기 도메인 별도 구매 검토 |
| CI | GitHub Actions | public repo 무제한 무료. `main` 브랜치 push/PR마다 `./gradlew test` 실행 (`.github/workflows/ci.yml`) |
| 비용이 발생할 수 있는 지점 | ODsay 무료 호출 한도(일 1,000회 이하) 초과 시 | 시간표 캐싱으로 호출량을 최소화해 방어 |

### 배포 구성

- `Dockerfile`: Gradle로 빌드한 뒤 JRE 이미지에 jar만 담는 멀티스테이지 빌드
- `docker-compose.prod.yml`: `app` + `postgres` + `caddy` 3개 컨테이너. 로컬 개발용 `docker-compose.yml`(포스트그레스만 띄우는 용도)과는 별개 파일로 분리
- `Caddyfile`: `{$DOMAIN}` 하나로 자동 HTTPS 리버스 프록시 설정
- `.env.example`: 서버에서 채워야 할 값(`DB_PASSWORD`, `ODSAY_API_KEY`, `VWORLD_API_KEY`, `SEOUL_SUBWAY_API_KEY`, `DOMAIN`) 목록. 실제 값을 채운 `.env`는 절대 커밋하지 않음(`.gitignore`)
- `application.yml`의 datasource는 `${DB_URL}`/`${DB_USERNAME}`/`${DB_PASSWORD}` 환경변수로 오버라이드 가능하며, 값이 없으면 로컬 개발 기본값(`localhost:5433`)을 그대로 씀

⚠️ **로컬에서 `docker-compose.prod.yml`을 테스트할 때 주의**: 두 compose 파일이 같은 디렉터리에 있으면 Docker Compose가 프로젝트명을 디렉터리 이름으로 같게 잡아서, `postgres` 서비스가 컨테이너 이름/포트 매핑이 다른데도 "같은 서비스"로 취급해 로컬 개발용 컨테이너를 지우고 재생성해버릴 수 있다(실제로 한 번 겪음 — 볼륨은 이름이 같아 데이터는 안 남았지만 포트 매핑이 사라짐). 로컬에서 프로덕션 compose를 검증할 땐 반드시 `docker compose -p <다른-프로젝트명> -f docker-compose.prod.yml ...`처럼 프로젝트명을 분리해야 한다.

### VM에 배포하는 순서 (요약)

1. Oracle Cloud 계정 생성 + Always Free VM 인스턴스 생성 (Ubuntu)
2. VM에 Docker + Docker Compose 설치, 80/443 포트 방화벽(Security List) 오픈
3. DuckDNS 가입 후 서브도메인을 VM의 공인 IP로 연결
4. 이 저장소를 VM에 clone, `.env.example`을 참고해 `.env` 작성 (`DB_PASSWORD`, `ODSAY_API_KEY`, `VWORLD_API_KEY`, `SEOUL_SUBWAY_API_KEY`, `DOMAIN`)
5. `docker compose -f docker-compose.prod.yml up -d --build`
6. `https://<DOMAIN>`으로 접속 확인

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
  - **적용 기준**: 로드맵 4~6단계(DB 스키마, 알고리즘, API/화면)처럼 이미 이 README에 설계가 끝나 있는 MVP 순차 구현은 이슈 없이 바로 브랜치만 파도 됨. v1.1 이후의 독립적인 기능 단위(버스 지원, 동시성/안정성 확장 기능 3종 등)부터 이슈를 먼저 만듦
- 커밋 메시지는 [Conventional Commits](https://www.conventionalcommits.org/) 스타일 유지 (`feat:`, `fix:`, `chore:`, `docs:`, `test:`)

## 로드맵

1. ~~기획 (MVP 범위 확정)~~
2. ~~API 리서치 — ODsay 길찾기/시간표 API 스파이크 완료~~
3. ~~개발 환경 세팅~~
4. ~~DB 스키마 확정 + 시간표 캐싱 서비스 구현~~ (`SubwaySchedule`, `SubwayScheduleCacheService`, ODsay 클라이언트)
5. ~~핵심 서비스 로직(역산 알고리즘) 구현 + 단위 테스트~~ (`LastDepartureCalculator`, [이슈 #1](https://github.com/Jihyun0909/whentoleave/issues/1) — 환승 연결 실패 시 Infeasible을 명시적으로 반환하도록 README 의사코드 대비 설계 보완)
6. ~~API/화면 구현~~ ([이슈 #2](https://github.com/Jihyun0909/whentoleave/issues/2) — `GET /api/v1/last-departure`, `GET /`)
6-1. ~~역 이름 검색으로 출발지/도착지 선택~~ ([이슈 #3](https://github.com/Jihyun0909/whentoleave/issues/3) — `searchStation` 연동, 동명역 여러 개(환승역 등)일 때 노선명과 함께 후보를 보여주고 선택하게 함. 자동완성(타이핑 중 후보)은 제외, 검색 버튼 클릭 방식)
7. 본인 실제 출퇴근 경로로 수동 검증 — 진행 중. 카카오맵/네이버지도와 대조하며 실제 버그 2건 발견 후 수정: 단축운행으로 목적지 전에 끊기는 막차 후보 오선택([이슈 #4](https://github.com/Jihyun0909/whentoleave/issues/4)), 공식 막차만 캐싱해서 마감을 만족하는 비공식 열차를 놓치는 문제([이슈 #5](https://github.com/Jihyun0909/whentoleave/issues/5)). 이 두 건 수정 이후로도 남는 사소한 시간 차이는 "알려진 한계"(계획된 시간표 vs 실시간 운행정보)로 문서화하고 더 파고들지 않기로 함.
8. ~~배포~~ — **https://whentoleave.jihyun-dev.shop 에서 실제로 서비스 중.** Oracle Cloud 가입이 계속 막혀서(계정 생성 오류) AWS EC2 학생 계정(서울 리전, t3.micro)으로 전환, 도메인은 기존에 갖고 있던 `jihyun-dev.shop`(AWS Route53으로 네임서버 위임된 상태)의 서브도메인 사용. 컨테이너화(`Dockerfile`, `docker-compose.prod.yml`, `Caddyfile`) + CI(`.github/workflows/ci.yml`) + 실배포 + HTTPS까지 전부 완료.
   - 삽질 기록: ODsay API 키가 `[ApiKeyAuthFailed]`로 계속 실패해서 한참 헤맸는데, 알고 보니 원인은 앱이 아니라 **진단용으로 직접 날린 curl 테스트 명령어들**이었음 — 키에 `+`가 있는데 URL 인코딩 없이 그대로 넣어서 서버가 `+`를 공백으로 해석해 키 자체가 깨진 채로 전달됨. 실제 앱 코드(`OdsayClient.java`)는 애초부터 `URLEncoder.encode()`로 정상 인코딩해서 보내고 있었어서, 앱 자체는 처음부터 문제없었음. IP 화이트리스트 등록 등은 결과적으로 불필요했지만 유지해도 무방
9. ~~(v1.1) 목표 도착시간 역산 기능~~ ([이슈 #6](https://github.com/Jihyun0909/whentoleave/issues/6) — "출발 시간 계산" 탭. `LastDepartureCalculator`의 마지막 구간을 "제약 없음(막차 기준)" 대신 "목표 도착시간이 데드라인"으로 취급하도록 확장, 기존 역산 로직/캐시를 그대로 재사용)
10. (v1.1) 주소 입력 → 최인접역 매핑 + 도보시간 반영 ([이슈 #7](https://github.com/Jihyun0909/whentoleave/issues/7))
11. (향후) 버스 포함 경로, PWA
12. (향후) 동시성/안정성 강화 기능 3종 — 상세 설계는 [docs/future-features-concurrency.md](docs/future-features-concurrency.md) 참고
    - 경로 탐색 캐시 + 캐시 스탬피드 방어
    - 경로 저장 + 막차 임박 알림 (멱등성 처리, 인증 도입)
    - 외부 API(ODsay) 장애 대응 — Resilience4j Circuit Breaker
12. (향후) 실시간 열차위치정보 연동 — 계획된 시간표 대신 실시간 도착정보로 크로스체크 (서울교통공사/코레일 등 ODsay와는 별도 데이터 소스, 출발이 임박했을 때만 의미 있어서 조건부 적용 필요). 배경은 위 "알려진 한계" 참고
