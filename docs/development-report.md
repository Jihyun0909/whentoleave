# 개발 보고서 — When To Leave

> 지하철 막차 시간을 역산해서 "몇 시까지 출발해야 하는지" 알려주는 개인 프로젝트.
> 기획부터 실배포까지의 기술 스택, 구현 과정, 트러블슈팅 기록.
>
> 실서비스: https://whentoleave.jihyun-dev.shop
> 저장소: https://github.com/Jihyun0909/whentoleave

## 1. 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어/프레임워크 | Java 21, Spring Boot 4.1.0 | Spring Initializr 기준 최신 버전 |
| 뷰 | Thymeleaf (서버 사이드 렌더링) | SPA 대신 선택 — 백엔드 포트폴리오 목적에 집중 |
| DB | PostgreSQL 16 | JPA/Hibernate |
| 외부 API | ODsay Lab (경로탐색, 시간표, 역 검색) | 무료 티어(일 1,000회) |
| 빌드 | Gradle (Kotlin DSL 아님, Groovy) | |
| 컨테이너 | Docker, Docker Compose | 로컬 개발용/배포용 compose 파일 분리 |
| 리버스 프록시 / HTTPS | Caddy | Let's Encrypt 자동 인증서 발급 |
| 배포 서버 | AWS EC2 (t3.micro, 서울 리전) | 원래 Oracle Cloud Always Free 계획했으나 가입 오류로 전환 |
| 도메인 | `jihyun-dev.shop` 서브도메인 (AWS Route53 위임) | 기존 보유 도메인 재사용 |
| CI | GitHub Actions | main push/PR마다 테스트 실행 |
| 배포 방식 | 수동 (SSH + `git pull` + `docker compose up --build`) | 자동배포 시도했다가 인스턴스 스펙 문제로 되돌림 (6절 참고) |

## 2. 구현 과정

로드맵 순서대로 진행했고, MVP 순차 구현(4~6단계)은 이슈 없이 바로 브랜치만 파서 진행, 그 외 단위는 GitHub 이슈를 먼저 만들고 진행했다.

1. **기획 + API 리서치** — "막차 시간 검색하기 불편함"(기존 지도 앱에서는 출발 시간을 분 단위로 바꿔가며 언제 교통편이 끊기는지 직접 확인해야 함)이라는 페인포인트에서 출발. ODsay Lab API(경로탐색/시간표/역검색) 스파이크.
2. **개발 환경 세팅** — JDK 21, IntelliJ, Docker(PostgreSQL 컨테이너). 프로젝트 경로를 한글/공백 없는 ASCII 경로로 강제(Windows 로케일 문제, 5절 참고).
3. **핵심 역산 알고리즘** ([이슈 #1](https://github.com/Jihyun0909/whentoleave/issues/1)) — 여러 지하철 구간(환승 포함)에 대해 마지막 구간부터 거꾸로 계산해서 첫 구간 승차역 최종 출발 시각을 구하는 `LastDepartureCalculator`. 자정 넘는 시각 비교 버그를 피하려고 "서비스일 기준 분"(0~1440+) 정수로 전부 환산해서 계산.
4. **API/화면 구현** ([이슈 #2](https://github.com/Jihyun0909/whentoleave/issues/2)) — ODsay `searchPubTransPathT`로 경로의 지하철 구간을 추출(`RouteLegExtractor`)하고, `SubwayScheduleCacheService`로 역×방향×요일유형 시간표를 lazy cache-aside 방식으로 캐싱. REST 엔드포인트(`/api/v1/last-departure`)와 최소 Thymeleaf 화면(`GET /`) 구현.
5. **역 이름 검색** ([이슈 #3](https://github.com/Jihyun0909/whentoleave/issues/3)) — 좌표 대신 역 이름으로 입력받도록 `StationCandidateResolver` 추가. 동명역(환승역 등)이 여러 개면 노선명과 함께 후보를 보여주고 선택하게 함.
6. **본인 실제 출퇴근 경로로 수동 검증** — 카카오맵/네이버지도와 대조하며 진행. 이 과정에서 실사용 버그 2건 발견·수정 ([이슈 #4](https://github.com/Jihyun0909/whentoleave/issues/4), [이슈 #5](https://github.com/Jihyun0909/whentoleave/issues/5) — 4절 참고).
7. **배포** — Docker 컨테이너화 → AWS EC2 배포 → HTTPS 연결까지 (5절 참고).

## 3. 핵심 알고리즘 — 역산 로직

여러 번 환승하는 경로에서, 가장 마지막 지하철 구간부터 거꾸로 계산해 최종 출발 마감 시각을 구한다.

```
legs = 길찾기 API에서 추출한 지하철 구간 리스트 (정순)
requiredArrival = null

for leg in reversed(legs):
    candidates = (leg.stationId, leg.wayCode)의 오늘 요일유형 전체 시간표 조회
    reachable = candidates 중 leg 도착역보다 앞에서 끊기는(단축운행) 후보 제외

    if requiredArrival != null:
        deadline = requiredArrival - leg.rideMinutes
        usable = reachable 중 deadline 이내인 후보 중 가장 늦은 시각
        (없으면 Infeasible 반환 — 가짜 답을 내지 않는다)
    else:
        usable = reachable 중 가장 늦은 시각  # 마지막 구간은 다음 제약이 없음

    if leg가 첫 구간이면 최종 답 = usable
    else requiredArrival = usable - leg.transferBufferMinutes
```

자정 넘는 시각은 전부 "서비스일 기준 분"으로 정규화해서 비교하고, 테스트는 `LastTrainLookup` 인터페이스를 분리해서 Mockito 없이 람다 페이크로 작성했다 (`LastDepartureCalculatorTest`, 9개 테스트 케이스 — 정상 케이스, 자정 wrap-around, 단축운행 제외, 마감 미달 시 이른 후보 탐색, 비공식 막차 탐색 등 실사용 회귀 케이스 포함).

## 4. 트러블슈팅 — 실사용 검증 중 발견한 버그

로드맵 7단계(본인 실제 경로 수동 검증)에서 카카오맵/네이버지도와 값을 대조하며 진행했는데, 최초 결과가 32분이나 차이 나서 아래와 같이 버그를 해결했다.

### 이슈 #4 — 단축운행(중간종착) 열차를 막차 후보로 잘못 선택

충무로→압구정 구간에서 알고리즘이 "약수행 00:57"을 최종 후보로 골랐는데, 노선 순서상 약수는 압구정보다 **앞**이라 그 열차로는 압구정에 못 간다. ODsay 경로탐색 응답의 `passStopList`(그 구간에서 지나는 역 목록)를 캡처해서, 도착역보다 앞서는 역들을 `SubwayLeg.earlierStopNames`로 들고 다니다가, 후보 목적지가 그 목록에 있으면 제외하도록 수정했다. 동시에 발견한 두 번째 버그 — 가장 늦은 후보 하나가 다음 환승 마감을 못 맞추면 그냥 "Infeasible"로 포기하던 로직도, 그보다 이른 다른 후보들 중 마감을 만족하는 가장 늦은 걸 다시 탐색하도록 고쳤다.

### 이슈 #5 — 공식 막차만 캐싱해서, 마감 안에 드는 비공식 열차를 놓침

이슈 #4를 고친 뒤에도 여전히 7분 차이가 남았다. 원인은 `SubwayScheduleCacheService`가 ODsay 응답 중 `firstLastFlag`가 2/3(공식 막차)인 항목만 골라서 캐싱하고 있었던 것 — 공식 막차(사당행 00:21)가 환승 마감을 7분 차로 못 맞추는데, 그 앞의 비공식 열차(사당행 23:59, `firstLastFlag:0`)가 실제로는 마감을 맞췄다. 카카오맵이 안내하는 값도 이쪽이었다. 해결은 필터링을 없애고 **응답 전체를 저장**한 뒤, 계산 시점(`LastDepartureCalculator`)에서 `firstLastFlag`와 무관하게 마감을 만족하는 가장 늦은 열차를 찾도록 바꾸는 것 — 엔티티도 `SubwayLastTrain` → `SubwaySchedule`로 이름을 바꿔서 "막차만 담는 게 아님"을 명확히 했다.

두 버그 모두 실제 값(정확한 역명·시각)을 그대로 재현하는 회귀 테스트로 남겨뒀다.

## 5. 트러블슈팅 — 배포

### 5-1. Windows 로케일 + Gradle 경로 문제

프로젝트 경로에 한글/공백이 섞여 있으면(`sun.jnu.encoding`이 UTF-8이 아닌 MS949인 Windows 환경) Gradle 테스트 워커가 클래스패스를 잘못 해석해서 `ClassNotFoundException`을 냈다. JVM 옵션으로 못 고치는 문제라, 프로젝트 자체를 ASCII 경로(`C:\Users\quswl\projects\whentoleave`)로 옮겨서 해결했다.

### 5-2. ODsay API 호출 시 URL 인코딩 버그

`UriComponentsBuilder.queryParam()`은 URI 스펙상 쿼리 문자열에서 허용되는 `/`를 인코딩하지 않고 그대로 보내는데, ODsay 서버는 그 상태로는 인증에 실패했다. `OdsayClient`를 `URLEncoder.encode()` + `URI.create()`로 직접 조립하도록 재작성해서 해결했다.

### 5-3. Oracle Cloud 가입 실패 → AWS EC2로 전환

원래 계획은 Oracle Cloud Always Free(ARM 4코어/24GB, 영구 무료)였는데, 계정 생성 단계에서 "계정을 생성하는 중 오류가 발생했습니다"가 반복되며 가입 자체가 안 됐다(카드 인증은 통과). 며칠 기다려도 동일해서, 학생 계정으로 보유하고 있던 **AWS EC2**(t3.micro, 서울 리전)로 전환했다. 컨테이너화(Dockerfile, docker-compose.prod.yml, Caddyfile)를 클라우드에 종속되지 않게 미리 만들어둔 덕에 전환 자체는 코드 변경 없이 가능했다.

### 5-4. 도메인 DNS가 반영이 안 됨 — 사실은 다른 네임서버를 보고 있었음

기존에 갖고 있던 `jihyun-dev.shop`(가비아에서 구매)의 DNS 관리 화면에서 서브도메인 A레코드를 추가했는데, 아무리 기다려도 반영이 안 됐다. 확인해보니 이 도메인은 예전 다른 프로젝트에서 AWS ACM 인증서 발급용으로 **AWS Route53에 네임서버가 위임**되어 있었다 — 즉 가비아 DNS 설정 화면 자체가 실제로 쓰이지 않는 상태였다. Route53 콘솔에서 직접 A레코드를 추가하고 나서야 해결됐다.

### 5-5. ODsay `[ApiKeyAuthFailed]` — 반나절 헤맨 진단 미스

배포 후 실제 검색이 계속 인증 실패로 막혔다. 순서대로 의심하고 배제한 것들:
- API 키 인코딩(`%2B`/`%2F` vs `+`/`/`) — 재확인 결과 문제없음
- ODsay에 등록된 Server IP가 예전 집 IP로 되어 있던 것 — AWS 서버 IP로 재등록, 그래도 실패
- 등록 반영 지연 — 하루 넘게 기다려도 실패

결국 **진단용으로 직접 날리던 `curl` 테스트 명령어 자체가 범인**이었다. 키에 포함된 `+` 문자를 URL 인코딩 없이 쿼리스트링에 그대로 넣었는데, `+`는 쿼리스트링에서 공백으로 해석되는 관례가 있어 curl 테스트가 매번 깨진 키를 보내고 있었던 것. 정작 앱 코드(`OdsayClient`)는 처음부터 `URLEncoder.encode()`로 정상 인코딩해서 보내고 있었고, 실제 웹 화면으로 직접 검색해보니 **애초부터 정상 동작**하고 있었다. IP 화이트리스트 재등록 등 그 사이 했던 조치들은 결과적으로 불필요했지만, 최종적으로 남겨둬도 무방한 상태.

### 5-6. 자동배포 시도 → 인스턴스 스펙 문제로 되돌림

GitHub Actions에서 main push 시 SSH로 접속해 자동으로 `git pull` + `docker compose up --build`를 하는 CD를 붙였는데, 기존 컨테이너(app/postgres/caddy)가 이미 떠서 메모리를 쓰고 있는 상태에서 새 빌드까지 동시에 돌리다 보니 RAM 1GB인 t3.micro가 감당을 못 하고 빌드가 멈춰(9분 넘게 진행 없음) 결국 타임아웃났다. 다행히 기존 컨테이너는 안 죽고 이전 버전으로 계속 떠 있어서 서비스 장애는 없었다. 근본 원인(스왑 없음 + 루트 디스크 6.7GB로 여유 없음)을 다음 순서로 고쳤다:

1. **EBS 볼륨 확장**: 6.7GB → 20GB (프리티어 30GB 한도 내, 추가 비용 없음). `growpart` + `resize2fs`로 파티션/파일시스템도 온라인 확장.
2. **스왑 2GB 추가**: `fallocate` + `mkswap` + `swapon`, `/etc/fstab`에 등록해서 재부팅 후에도 유지되게 함.

이후 재배포는 정상적으로 완료됐다. 다만 자동배포(GitHub Actions → SSH)는 SSH 포트를 인터넷 전체(0.0.0.0/0)에 열어야 한다는 트레이드오프가 있어, 인스턴스 스펙 문제를 고치는 김에 일단 되돌리고 지금은 수동 배포(SSH + git pull + docker compose up)로 운영 중이다. 자동배포 CI 잡 자체는 코드로는 이미 검증됐으니, 필요해지면 다시 붙이면 된다.

## 6. 배포 아키텍처

```
사용자 → HTTPS → Caddy(자동 인증서, 리버스 프록시)
                    → app 컨테이너 (Spring Boot, java -jar app.jar)
                        → postgres 컨테이너 (시간표 캐시)
                        → ODsay Lab API (경로탐색/시간표/역검색)
```

- `Dockerfile`: Gradle 빌드 → JRE 런타임 멀티스테이지
- `docker-compose.prod.yml`: app + postgres + caddy 3개 컨테이너, `.env`로 시크릿 주입 (`DB_PASSWORD`, `ODSAY_API_KEY`, `DOMAIN`)
- `.env`는 서버에만 존재, 저장소에는 `.env.example`만 커밋
- GitHub Actions(`ci.yml`): main push/PR마다 postgres 서비스 컨테이너 띄워서 `./gradlew test` 실행 (배포는 수동)

## 7. 현재 상태

- 로드맵 1~8단계 완료, 실서비스 운영 중
- 알려진 한계: 계획된(정적) 시간표 기준 계산 — 실시간 열차 지연/신호대기는 반영 안 됨 (화면에 안내 문구로 명시)
- 다음 단계 후보: v1.1 목표 도착시간 역산 기능("출발 시간 계산"), "다른날 막차 보기", 역 이름 키워드 매핑(예: "건대"만 입력해도 "건대입구"를 추천/자동입력), 동시성/안정성 강화 3종([docs/future-features-concurrency.md](future-features-concurrency.md))
