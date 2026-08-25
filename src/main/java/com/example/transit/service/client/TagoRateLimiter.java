package com.example.transit.service.client;

import org.springframework.stereotype.Component;

/**
 * TAGO(data.go.kr) 호출 전체가 공유하는 초당 요청 제한 스로틀.
 * <p>
 * 실사용 중 발견: 경로 하나에 환승이 여러 번 있고 Google이 후보 경로를 여러 개 주면, 구간마다
 * 승차역/도착역 조회 + 시간표 조회가 겹쳐서 짧은 시간에 TAGO 호출이 몰린다 - 이때
 * {@code LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR}(HTTP 429)가 난다.
 * TAGO의 여러 서비스(지하철/버스노선/버스도착)가 계정 단위로 같은 초당 한도를 공유하는 것으로
 * 보여, 서비스별이 아니라 이 클래스 하나를 모든 TAGO 클라이언트가 공유해서 막는다.
 */
@Component
public class TagoRateLimiter {

    /** 호출 사이 최소 간격(ms). 초당 약 5건으로 제한 - 개인 사용 규모에서 지연은 감내 가능한 수준. */
    private static final long MIN_INTERVAL_MILLIS = 200;

    private long lastRequestAtMillis = 0;

    /** 마지막 호출로부터 최소 간격이 지날 때까지 이 스레드를 재운다. */
    public synchronized void acquire() {
        long waitMillis = lastRequestAtMillis + MIN_INTERVAL_MILLIS - System.currentTimeMillis();
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAtMillis = System.currentTimeMillis();
    }
}
