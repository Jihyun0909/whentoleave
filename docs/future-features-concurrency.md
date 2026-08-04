# When To Leave — 추가 기능 기획 (동시성/안정성 강화)

MVP(핵심 막차 안내 기능) 완료 후 착수할 확장 기능 3종의 설계 문서입니다. 기존 아키텍처(`web / api / service / domain / repository` 패키지 분리, `service`는 HTTP 타입을 모름, API는 DTO로 응답)를 그대로 따릅니다.

3개 기능은 서로 독립적으로 구현 가능하지만, 기능 2가 기능 1의 캐시 인프라를 재사용하고 기능 3이 기능 1의 캐시를 fallback 대상으로 쓰므로 **1 → 2 → 3 순서로 진행**합니다.

---

## 기능 1. 경로 탐색 캐시 + 캐시 스탬피드 방어

### 문제 정의

`searchPubTransPathT`(경로 탐색)는 현재 요청마다 매번 ODsay API를 호출한다. 동일한 출발지/도착지 조합(예: 강남역 → 사당역 같은 흔한 통근 경로)에 여러 사용자가 비슷한 시각에 요청하면, 사실상 같은 응답을 위해 API를 중복 호출하게 된다. 무료 호출 한도(일 1,000회)가 있는 상황에서 이는 실질적인 장애 위험이다.

### 설계

**캐시 레이어**
- 키: `(출발지 좌표 or 역ID, 도착지 좌표 or 역ID)`를 정규화한 문자열
- 값: ODsay 경로 탐색 응답을 파싱한 결과(DTO), TTL 5분 (경로 자체는 잘 안 바뀌지만 실시간성 여지를 위해 짧게)
- 저장소: 별도 인프라(Redis) 없이 시작 — 단일 VM(Oracle Cloud) 배포이므로 `ConcurrentHashMap` 기반 인메모리 캐시로 충분. 인스턴스 확장 시 Redis로 교체 가능하도록 인터페이스(`RouteCache`)로 추상화해둔다.

**동시성 제어 (더블 체크 락킹)**
1. 캐시 조회 → 있으면 즉시 반환
2. 없으면 해당 캐시 키 전용 락 획득 (`ConcurrentHashMap<String, ReentrantLock>`으로 키별 락 관리 — 전체 캐시를 잠그지 않고 같은 경로를 찾는 요청끼리만 대기시킴)
3. 락 획득 후 캐시 재조회 (락 대기 중 다른 스레드가 이미 채워놨을 수 있음) → 있으면 반환
4. 없으면 그때 ODsay API 호출 → 캐시 저장 → 락 해제 → 반환

```java
class RouteCache {
    Map<String, CachedRoute> cache;          // 실제 캐시
    Map<String, ReentrantLock> keyLocks;     // 키별 락

    RouteResult getOrFetch(String key, Supplier<RouteResult> fetcher) {
        cached = cache.get(key);
        if (cached != null && !cached.expired()) return cached.value;

        lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = cache.get(key);          // 재확인
            if (cached != null && !cached.expired()) return cached.value;

            result = fetcher.get();           // 실제 ODsay 호출은 여기서 단 1번만
            cache.put(key, new CachedRoute(result, now + TTL));
            return result;
        } finally {
            lock.unlock();
        }
    }
}
```

### 검증 방법

- `ExecutorService`로 동일 출발지/도착지 요청을 스레드 50개로 동시에 발사
- 락 적용 전: ODsay 호출 카운터가 요청 수만큼 증가하는 것을 확인 (문제 재현)
- 락 적용 후: 호출 카운터가 1로 수렴하는 것을 확인 (해결 검증)
- 이 Before/After 수치를 README와 통합테스트 코드에 남긴다 — 이게 이 기능의 핵심 증거자료

### 패키지 배치

- `service.cache.RouteCache` — 순수 로직, 인터페이스로 분리
- `service.RouteSearchService`에서 `RouteCache`를 통해 ODsay 클라이언트 호출

---

## 기능 2. 경로 저장 + 막차 임박 알림 (멱등성 처리)

### 요구사항

- 로그인한 사용자가 자주 쓰는 경로를 저장 (예: "퇴근길")
- 배치 작업이 주기적으로 도는 저장된 경로에 대해 막차 임박 알림 발송 (예: 막차 30분 전)
- 배치가 재시도되거나 스케줄이 겹쳐도 같은 사용자에게 같은 알림이 중복 발송되지 않아야 함

### 선행 조건: 인증

- README에 "인증 필요해지면 JWT" 명시되어 있음 — 이 기능부터 인증이 실제로 필요해지므로 여기서 도입
- `User` 엔티티, `Spring Security` + JWT 필터, `/api/v1/auth/**` 엔드포인트
- 기존 원칙 유지: `service` 패키지는 여전히 HTTP/Security 타입을 몰라야 함 (인증된 사용자 ID만 파라미터로 전달받음)

### 도메인 설계

```sql
CREATE TABLE saved_route (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    label VARCHAR(50),                     -- "퇴근길" 등 사용자 지정 이름
    origin_station_id INT NOT NULL,
    destination_station_id INT NOT NULL,
    notify_minutes_before INT NOT NULL DEFAULT 30,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE notification_log (
    id BIGSERIAL PRIMARY KEY,
    saved_route_id BIGINT NOT NULL REFERENCES saved_route(id),
    notify_date DATE NOT NULL,             -- 알림 대상 날짜
    idempotency_key VARCHAR(100) NOT NULL, -- saved_route_id + notify_date 조합 해시
    sent_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (idempotency_key)               -- 이 제약이 멱등성의 핵심
);
```

### 멱등성 처리 흐름

1. 배치가 "오늘 알림 보내야 할 저장 경로 목록" 조회
2. 각 경로에 대해 `idempotency_key = hash(saved_route_id + notify_date)` 생성
3. 알림 발송 시도 전, `notification_log`에 해당 키로 INSERT를 먼저 시도
4. `UNIQUE` 제약 위반(이미 존재) → 이미 보낸 것으로 간주하고 스킵
5. INSERT 성공 → 그제서야 실제 알림 발송 (이메일/로그 등, 스코프상 간단히 로그 출력 또는 `JavaMailSender`로 실제 이메일 발송)

이 순서(INSERT 먼저, 발송 나중)가 핵심이다. 발송 먼저 하고 로그를 나중에 남기면, 발송 성공 후 로그 기록 직전에 서버가 죽는 경우 중복 발송을 막을 수 없다. **GachiSallim 웹훅 처리에서 겪었던 "재시도로 인한 중복 처리" 문제와 동일한 패턴**이므로, 그 경험을 README에 명시적으로 언급한다.

### 검증 방법

- 같은 `saved_route_id + notify_date`로 배치 로직을 2번 연속 실행 → `notification_log`에 1건만 남는지, 실제 발송도 1번만 되는지 테스트
- 배치 두 스레드가 동시에 같은 대상을 처리하는 경쟁 상황도 테스트 (동시 INSERT 시도 → 하나만 성공)

### 패키지 배치

- `domain.SavedRoute`, `domain.NotificationLog`
- `service.NotificationService` — 멱등 처리 로직
- 배치 실행: `@Scheduled` 또는 별도 배치 러너 (Spring Batch까지는 스코프 초과, 단순 스케줄러로 충분)

---

## 기능 3. 외부 API 장애 대응 (Resilience4j Circuit Breaker)

### 문제 정의

ODsay가 느려지거나 순간적으로 응답 불가 상태가 되면, 이 서비스의 모든 요청이 함께 느려지거나 타임아웃으로 실패한다. 외부 시스템에 의존하는 서비스에서 흔히 발생하는 연쇄 장애다.

### 설계

- `Resilience4j`의 `CircuitBreaker` + `Retry` + `Fallback` 조합
- 설정값 (예시, 실측 후 조정):
  - `failureRateThreshold: 50` — 최근 요청의 50% 이상 실패 시 회로 open
  - `waitDurationInOpenState: 30s` — open 상태 유지 시간
  - `slidingWindowSize: 10`
- Fallback 동작: 회로가 열리면 실시간 ODsay 호출 대신 가장 최근에 캐시된 결과(기능 1의 캐시, 또는 배치로 캐싱된 시간표 테이블)를 반환하고, 응답 DTO에 `stale: true` 플래그를 넣어 클라이언트가 "실시간 데이터가 아님"을 알 수 있게 한다.

```java
@CircuitBreaker(name = "odsay", fallbackMethod = "fallbackToCache")
@Retry(name = "odsay")
public RouteResult searchRoute(...) { ... }

private RouteResult fallbackToCache(..., Throwable t) {
    return routeCache.getStaleIfAvailable(key)
        .orElseThrow(() -> new ServiceUnavailableException(...));
}
```

### 검증 방법

- ODsay 클라이언트를 테스트에서 강제로 예외 던지도록 목킹
- 연속 실패 시 회로가 open으로 전환되는지, open 상태에서 fallback이 정상 동작하는지 통합테스트로 확인

### 패키지 배치

- `service.client.OdsayClient`에 애노테이션 적용
- 설정: `application.yml`의 `resilience4j.circuitbreaker.instances.odsay`

---

## 전체 우선순위 및 이유

1. **기능 1 (캐시 스탬피드 방어)** — 가장 먼저. 무료 API 한도 방어라는 실질적 필요성이 있고, 독립적으로 완결된 기능이라 데모/설명이 쉬움
2. **기능 2 (알림 멱등성)** — 인증 도입까지 포함해 스코프가 제일 크지만, GachiSallim 경험과 직접 연결되는 스토리라 자기소개서/포트폴리오 가치가 높음
3. **기능 3 (회로차단기)** — 앞의 두 기능(특히 캐시)이 있어야 fallback 대상이 생기므로 마지막

## README에 추가할 섹션 (완료 후)

- 각 기능별 "문제 재현 → 원인 → 해결 → 검증 수치" 순서로 정리
- 특히 기능 1은 Before/After 호출 횟수 그래프나 표로 시각화하면 효과적
