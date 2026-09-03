# When To Leave — 금융 백엔드 확장 기획 (인증 · 포인트 · 정산 · 감사)

기존 "막차 안내" 서비스 위에, 금융권 백엔드 포트폴리오용으로 4개 영역을 얹는 설계 문서입니다.
기존 아키텍처 원칙(`web / api / service / domain / repository` 분리, `service`는 HTTP/Security 타입을
모름, API는 DTO로 응답, 테스트는 외부 인프라 없이)을 그대로 따릅니다.

## 스코프 원칙

- **전부 "가상"**: 실 PG 연동, 사업자등록번호 검증, 실 계좌이체, 제휴사 심사는 **제외**한다.
  제휴사·계좌·포인트·결제는 모두 내부 도메인으로 시뮬레이션한다.
- **경로조회 코드는 건드리지 않는다.** 포인트 차감/적립은 "택시 이용 완료" 시점에만 일어나며,
  경로조회 요청과는 무관하다.
- **동시성 락 기본 구현은 DB 비관적 락.** Redisson 분산 락은 인터페이스(`PointLockStrategy`)만
  열어두고 실제 구현/전환은 이후 과제로 둔다.
- **Refresh Token은 Redis에 저장**하되, `RefreshTokenStore` 인터페이스로 추상화해서 테스트는
  인메모리 페이크로 돈다 (기존 `LastTrainLookup` 페이크 패턴과 동일).
- 스키마는 전부 신규 테이블이라 `ddl-auto=update`를 유지한다. (Flyway 도입은 별도 시점)

---

## 도메인 모델

```
-- 인증
app_user          (id, email UNIQUE, password_hash, role[USER|PARTNER_ADMIN|ADMIN],
                   partner_id?, created_at, updated_at)
-- Refresh Token: Redis (key rt:{userId}:{jti}, value 해시, TTL 7d). RDB 테이블 없음.

-- B2B 제휴사 (가상)
partner           (id, name, commission_rate, active, created_at)

-- 택시 이용 · 결제 (가상 시뮬레이션)
taxi_ride         (id, user_id, partner_id, origin, dest, fare_amount,
                   status[REQUESTED|IN_PROGRESS|COMPLETED|CANCELLED], requested_at, completed_at)
payment           (id, ride_id UNIQUE, user_id, gross_amount, point_used, cash_amount,
                   point_earned, status[PAID|CANCELLED], settled_at?, paid_at)

-- 복식부기 원장 (append-only)
ledger_account    (id, owner_type[USER|PARTNER|SYSTEM], owner_id?,
                   kind[POINT|CASH|COMMISSION_INCOME|POINT_LIABILITY],
                   balance, version, UNIQUE(owner_type, owner_id, kind))
ledger_transaction(id, type[PAYBACK|SPEND|SETTLEMENT|REVERSE], ref_type, ref_id,
                   idempotency_key UNIQUE, memo, created_at)          -- 불변
ledger_entry      (id, transaction_id, account_id, direction[DEBIT|CREDIT], amount, created_at)
                                                                     -- 불변, 트랜잭션별 Σ(부호적용) = 0

-- 정산
settlement        (id, partner_id, period_start, period_end, gross_amount, commission_amount,
                   payout_amount, ride_count, status[PENDING|DONE|FAILED],
                   batch_execution_id, created_at, UNIQUE(partner_id, period_start, period_end))

-- 감사
audit_log         (id, actor_user_id?, event, ref_type, ref_id, detail_json, created_at)  -- 불변

+ Spring Batch 메타테이블 (자동 생성)
```

### 복식부기 규칙

모든 금융 이벤트는 하나의 `ledger_transaction` + 차변/대변 합이 0인 `ledger_entry` 집합으로 기록한다.
잔액의 정본은 원장이며, `ledger_account.balance`는 같은 트랜잭션 안에서 갱신되는 캐시(락 대상)다.

| 이벤트 | 분개 |
|---|---|
| 포인트 적립(5% 페이백) | DEBIT `POINT_LIABILITY`(SYSTEM) / CREDIT 유저 `POINT` |
| 결제 시 포인트 차감 | DEBIT 유저 `POINT` / CREDIT `POINT_LIABILITY`(SYSTEM) |
| 정산(수수료 수취) | DEBIT 파트너 `CASH` / CREDIT `COMMISSION_INCOME`(SYSTEM) |
| 역분개(정산 실패 등) | 원분개의 차·대변을 뒤집은 트랜잭션 (수정·삭제 금지) |

### 불변성 보장

- `ledger_transaction` / `ledger_entry` / `audit_log`: 엔티티에 setter 없음, Hibernate `@Immutable`,
  repository에서 `delete*` 미노출. 정정은 역분개로만.

---

## 영역 1. 인증 / 인가  (PR A)

| 구성요소 | 내용 |
|---|---|
| 의존성 | `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-{api,impl,jackson}:0.12.x`, `spring-boot-starter-data-redis`, `spring-security-test`(test) |
| `AppUser` + `Role` | `ROLE_USER`(B2C) / `ROLE_PARTNER_ADMIN`(B2B, `partner_id` 필수) / `ROLE_ADMIN` |
| `JwtProvider` | HS256, Access 15분 / Refresh 7일, 시크릿 `${JWT_SECRET}` (커밋 금지, `.env.example` 추가) |
| `JwtAuthenticationFilter` | `Authorization: Bearer` 파싱 → `SecurityContext` |
| `RefreshTokenStore` | 인터페이스. `RedisRefreshTokenStore`(운영) / `InMemoryRefreshTokenStore`(테스트). 회전 + 재사용 감지 시 유저 전체 폐기 |
| `SecurityConfig` | stateless. `/`, `/api/v1/last-departure`, `/api/v1/stations/**`, 정적, `/api/v1/auth/**` → `permitAll`. `/api/v1/admin/**` → ADMIN, `/api/v1/partner/**` → PARTNER_ADMIN, 나머지 인증 |
| `AuthController` | `POST /api/v1/auth/{signup,login,refresh,logout}` |
| 비밀번호 | `BCryptPasswordEncoder` |

컨트롤러가 `@AuthenticationPrincipal`에서 `userId`(Long)만 뽑아 서비스로 넘긴다. 서비스엔 Security import 금지.

---

## 영역 2. 선불 포인트 차감 / 적립 (B2C)  (PR B)

- **이용 완료**(`taxi_ride` → `COMPLETED`) → `payment` 생성 → `gross_amount * 5%`를 페이백 분개.
  idempotency_key `PAYBACK:ride:{rideId}` UNIQUE → 배치/재시도 중복 적립 차단.
- **결제 시 선불 포인트 차감** → 차감 분개. idempotency_key `SPEND:payment:{paymentId}`.
- **동시성 제어**: `LedgerAccountRepository.findForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) +
  `@Version`. `@Transactional` 안에서 락 → 잔액 검증 → `ledger_entry` INSERT → `balance` UPDATE.
  잔액 부족 시 `InsufficientPointException` + `audit_log`.
- `PointLockStrategy` 인터페이스로 감싸서 이후 Redisson 전환 지점 명시.
- **검증**: `ExecutorService` 50스레드 동시 차감 → ① 잔액 음수 불가 ② 최종 잔액 정확
  ③ 트랜잭션별 분개 균형(Σ=0). 락 전/후 수치를 이 문서와 테스트에 기록.

---

## 영역 3. 수수료 정산 및 원장 (B2B)  (PR C)

- Spring Batch `partnerSettlementJob`. chunk step:
  - **Reader**: 정산 기간 내 `settled_at IS NULL`인 `COMPLETED` 결제를 가진 파트너
  - **Processor**: 파트너별 합계 · 수수료(`partner.commission_rate`) · 지급액 계산
  - **Writer**: `settlement` 저장(`PENDING`→`DONE`) + 정산 분개 + `payment.settled_at` 마킹
- **부분 실패 처리**: 파트너 단위 트랜잭션. 한 파트너가 실패해도 나머지는 진행.
  실패 파트너는 `FAILED`로 기록(`REQUIRES_NEW`)하고, 그 파트너의 이번 회차 결제는
  취소(`payment` → `CANCELLED`) + 포인트 역분개까지 한 트랜잭션에서 롤백.
- **실패 트리거(가상)**: 파트너 `active=false`, `commission_rate` 미설정/범위 밖, 계좌(`CASH`) 원장 없음.
- 멱등: `UNIQUE(partner_id, period)`. 재실행 시 `DONE` 스킵.
- 실행: `POST /api/v1/admin/settlements/run?date=` (ADMIN) + `@Scheduled` 매일 03:00.
  `spring.batch.job.enabled=false`, `spring.batch.jdbc.initialize-schema=always`.
- 조회: `GET /api/v1/partner/settlements` (본인 파트너만).

---

## 영역 4. 금융 이력 및 감사 로그  (PR C 동반)

- `ledger_transaction` / `ledger_entry` 불변 설계 (위 "불변성 보장").
- `audit_log` 이벤트: `INSUFFICIENT_POINT`, `DUP_IDEMPOTENCY`, `UNAUTHORIZED`,
  `SETTLEMENT_FAIL`, `LEDGER_IMBALANCE` 등. 구조화 로깅 병행.
- 조회: `GET /api/v1/points/history` (본인), `GET /api/v1/admin/audit-logs` (ADMIN).

---

## PR 분할

1. **PR A — 인증 토대** ✅: 의존성, `app_user`, `RefreshTokenStore`(Redis + 인메모리), JWT 필터/프로바이더,
   `AuthController`, `SecurityConfig`. 기존 공개 엔드포인트 전부 `permitAll` 유지, 경로조회 무변경.
2. **PR B — 원장 + 포인트 페이백** ✅: `partner` · `taxi_ride` · `payment` · `ledger_*`, 복식부기 서비스,
   적립 · 차감, 비관적 락, 동시성 테스트, `audit_log` 기초.
3. **PR C — 정산 + 감사** ✅: `settlement`, Spring Batch Job, 부분 실패 · 롤백, admin/partner 엔드포인트,
   스케줄러, `audit-logs` 조회.

의존 순서 A → B → C.

## PR C 구현 메모 (설계에서 조정된 것)

- **정산 분개**: DEBIT `FARE_CLEARING`(시스템, 정산 자금원) / CREDIT 제휴사 `CASH` payout /
  CREDIT `COMMISSION_INCOME`(시스템) commission. 결제의 현금 흐름은 외부 PG로 처리했다고 가정해
  원장 밖. 제휴사 `CASH` 계정은 정산이 아니라 **첫 매출(결제) 시점**에 provisioning한다
  (배치는 "생성 후 같은 트랜잭션 재조회"가 곤란 - 아래).
- **JobRepository는 JDBC(영속)**: Spring Batch 6 / Boot 4 기본은 인메모리(resourceless)라
  `BATCH_*` 테이블도, 실행 이력도 없다. `@EnableBatchProcessing + @EnableJdbcJobRepository`
  (`BatchJdbcConfig`)로 JDBC JobRepository를 쓰고, `BatchSchemaInitializer`가 `BATCH_*` 테이블을
  없을 때 만든다(H2/Postgres). 이제 JobExecution·skip/write 카운트가 남고 재시작이 가능하다.
- **멱등성은 `payment.settled_at`으로**: `calculate`가 미정산 결제만 조회하므로, 마감된 기간을
  재실행하면 미정산 0건이라 아무것도 안 한다. `Settlement`는 유니크 없이 append-only 로그 -
  배치 실행마다 한 행이고, 뒤늦게 결제가 들어와 재실행하면 그 몫만큼 새 행이 하나 더 생긴다
  (그 제휴사·그 날 총합 = 행들의 합). `SettlementLauncher`는 미래 날짜를 거부한다.
- **부분 실패 처리**: chunk 크기 1 = 제휴사 하나가 트랜잭션 하나. `SettlementException`(비활성
  제휴사·수수료율 이상) → 그 청크만 롤백(정산행·분개·`settled_at`이 하나도 안 남음) →
  `SettlementSkipListener`가 `REQUIRES_NEW`로 `FAILED` 행 + `SETTLEMENT_FAILED` 감사 →
  다음 제휴사 진행. 잡 자체는 `COMPLETED`. 행이 실행 단위라 이전 실행의 `DONE` 행은 안 건드린다.
- **`partnerId` 잡 파라미터(선택)**: 지정하면 그 제휴사만 재정산한다. 관리자 API·테스트에서 사용.
- **enum은 전부 `@Converter`(varchar) 저장**: `@Enumerated`가 만드는 `check` 제약이
  `ddl-auto=update`로 갱신되지 않아 enum에 값을 추가하면 INSERT가 깨진다. 공통 베이스
  `EnumStringConverter` + enum별 `@Converter(autoApply=true)` 하위 클래스. 이미 만들어진 DB의
  옛 check 제약은 `LegacyConstraintCleanup`이 기동 시 `DROP ... IF EXISTS`로 떨어낸다(Postgres, 멱등).
- **계정 최초 생성**: `ledger_account` 유니크 위반을 무조건 삼키던 걸, 삼킨 뒤 재확인해서
  진짜 없으면(스키마 오류 등) 다시 던지도록 고침. 제휴사 `CASH` 계정은 정산이 아니라
  **첫 매출(결제) 시점**에 provisioning한다(배치는 "생성 후 같은 트랜잭션 재조회"가 곤란).
- **정산 분개**: DEBIT `FARE_CLEARING`(시스템, 정산 자금원) / CREDIT 제휴사 `CASH` payout /
  CREDIT `COMMISSION_INCOME`(시스템) commission. 결제의 현금 흐름은 외부 PG로 처리했다고 가정해 원장 밖.
- **엔드포인트**: `POST /api/v1/admin/settlements/run`(선택 `partnerId`), `GET /api/v1/admin/settlements`,
  `GET /api/v1/admin/audit-logs`, `GET /api/v1/partner/settlements`(본인 것만),
  `GET|POST /api/v1/admin/partners[/{id}/{activate,deactivate}]`.

## PR B 구현 메모 (설계에서 조정된 것)

- **CONTRA 계정 모델**: 포인트 시스템을 사용자별 `POINT`(대변 정상) + 시스템 단일 `POINT_CONTRA`
  (차변 정상) 두 계정으로 닫았다. 불변식 `POINT_CONTRA.balance == Σ(모든 POINT.balance)`를
  테스트로 검증한다. `POINT_LIABILITY`/`COMMISSION_INCOME` 등은 PR C에서 추가.
- **`PointLockStrategy` 구현함**: `executeGuarded(userId, op)` 인터페이스 + `DbLockPointLockStrategy`
  (`TransactionTemplate`로 트랜잭션 경계만 열고, 실제 락은 `LedgerAccountRepository.findForUpdate`).
  `app.point.lock-strategy=redisson`으로 교체 지점 확보.
- **CONTRA 경합 처리 (리뷰 반영)**: 공유 `POINT_CONTRA` 계정은 비관적 락 대신 `@Version`
  낙관적 락 + `RetryingTransactionRunner`(트랜잭션째 재시도, decorrelated jitter 백오프, 12회)로
  바꿨다. 느린 트랜잭션 하나가 전체를 막는 head-of-line blocking이 사라진다.
  - `RideService.complete`는 `@Transactional` 대신 `retryRunner.run`으로 경계를 잡는다
    (재시도가 트랜잭션 바깥에 있어야 매 시도가 새 트랜잭션).
  - 계정 최초 생성 경합은 `LedgerAccountCreator`(`REQUIRES_NEW`)로 격리 - 주 트랜잭션 세션이
    예외로 오염되는 `AssertionFailure: null identifier` 방지. `POINT_CONTRA`는 기동 시 선점 시드.
  - **남은 한계**: 낙관적 락도 결국 같은 행을 갱신하므로, 수십 개가 같은 ms에 몰리는 극단적
    경합에선 재시도 소진(→ 503 `CONCURRENCY_RETRY_EXHAUSTED`) 가능. 그 규모면 CONTRA를
    샤딩(N개 하위계정 합산)하거나 잔액을 비-materialized로 전환한다.
- **감사 로그와 락**: 잔액 부족 감사는 락/트랜잭션을 벗어난 뒤 기록한다 - 락을 쥔 채
  `REQUIRES_NEW`로 감사 테이블에 쓰면 커넥션 풀이 빠듯할 때 2번째 커넥션을 못 얻어 교착.
- **테스트 환경**: H2 `LOCK_TIMEOUT=15000`(기본 1초로는 동시성 테스트에서 락 대기 초과),
  Hikari `maximum-pool-size=40`. 운영 Postgres는 해당 없음.
- **엔드포인트**: `POST /api/v1/rides`, `/{id}/{start,complete,cancel}`, `GET /api/v1/rides[/{id}]`,
  `GET /api/v1/partners`, `GET /api/v1/points[/history]`.

## 완료 후 문서화

- 각 영역: "문제 → 설계 → 검증 수치" 순서로 README / `docs/development-report.md`에 반영.
- 특히 영역 2는 락 전/후 동시성 테스트 수치, 영역 3은 부분 실패 시나리오 결과를 표로.
