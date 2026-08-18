package com.example.transit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxiFareEstimatorTest {

    private final TaxiFareEstimator estimator = new TaxiFareEstimator();

    /** 기본요금(1.6km) 이내 거리는 기본요금 그대로 나와야 한다. */
    @Test
    void 기본거리_이내면_기본요금만_나온다() {
        // 두 좌표를 아주 가깝게 잡아서(약 100m) 도로거리 보정을 해도 1.6km를 넘지 않게 한다.
        TaxiFareEstimator.TaxiFareEstimate estimate = estimator.estimate(127.0276, 37.4979, 127.0286, 37.4979);

        int expectedBase = estimate.lateNightSurcharge() ? 4_800 * 12 / 10 : 4_800;
        // 반올림(100원 단위) 오차를 허용한다.
        assertTrue(Math.abs(estimate.fareWon() - expectedBase) <= 100);
    }

    /** 거리가 늘어나면 요금도 늘어나야 한다(기본요금보다 커야 함). */
    @Test
    void 기본거리를_넘으면_요금이_기본요금보다_커진다() {
        // 서울역 근처 -> 강남역 근처, 약 9km
        TaxiFareEstimator.TaxiFareEstimate estimate = estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979);

        assertTrue(estimate.fareWon() > 4_800);
    }

    /** 100원 단위로 반올림된 값이어야 한다. */
    @Test
    void 요금은_백원_단위로_반올림된다() {
        TaxiFareEstimator.TaxiFareEstimate estimate = estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979);

        assertEquals(0, estimate.fareWon() % 100);
    }
}
