package com.example.transit.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxiFareEstimatorTest {

    private final TaxiFareEstimator estimator = new TaxiFareEstimator();
    private static final LocalTime DAYTIME = LocalTime.of(14, 0);

    /** 기본요금(1.6km) 이내 거리는 할증 없는 시간대엔 기본요금 그대로 나와야 한다. */
    @Test
    void 기본거리_이내면_기본요금만_나온다() {
        // 두 좌표를 아주 가깝게 잡아서(약 100m) 도로거리 보정을 해도 1.6km를 넘지 않게 한다.
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(127.0276, 37.4979, 127.0286, 37.4979, DAYTIME);

        assertEquals(4_800, estimate.fareWon());
        assertFalse(estimate.hasSurcharge());
    }

    /** 거리가 늘어나면 요금도 늘어나야 한다(기본요금보다 커야 함). */
    @Test
    void 기본거리를_넘으면_요금이_기본요금보다_커진다() {
        // 서울역 근처 -> 강남역 근처, 약 9km
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, DAYTIME);

        assertTrue(estimate.fareWon() > 4_800);
    }

    /** 100원 단위로 반올림된 값이어야 한다. */
    @Test
    void 요금은_백원_단위로_반올림된다() {
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, DAYTIME);

        assertEquals(0, estimate.fareWon() % 100);
    }

    /** 거리가 멀수록 예상 소요시간도 늘어나야 한다. */
    @Test
    void 거리가_멀수록_예상_소요시간도_늘어난다() {
        TaxiFareEstimator.TaxiFareEstimate near =
                estimator.estimate(127.0276, 37.4979, 127.0286, 37.4979, DAYTIME);
        TaxiFareEstimator.TaxiFareEstimate far =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, DAYTIME);

        assertTrue(far.estimatedMinutes() > near.estimatedMinutes());
    }

    /** 할증 시간대가 아니면(예: 낮 2시) 할증 안내가 없어야 한다. */
    @Test
    void 낮시간에는_할증이_없다() {
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, DAYTIME);

        assertNull(estimate.surchargeLabel());
    }

    /** 22시~23시는 20% 할증 구간이어야 한다. */
    @Test
    void 밤_10시대는_20퍼센트_할증이다() {
        TaxiFareEstimator.TaxiFareEstimate withSurcharge =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(22, 30));
        TaxiFareEstimator.TaxiFareEstimate base =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, DAYTIME);

        assertTrue(withSurcharge.hasSurcharge());
        assertTrue(withSurcharge.surchargeLabel().contains("20%"));
        assertTrue(withSurcharge.fareWon() > base.fareWon());
    }

    /**
     * 23시~다음날 02시는 자정을 넘나드는 구간이라 별도 로직이 필요하다 - 40% 할증으로,
     * 22~23시/02~04시 구간(20%)보다 더 높은 할증률이어야 한다.
     */
    @Test
    void 밤_11시부터_새벽_2시까지는_40퍼센트_할증이고_자정도_포함된다() {
        TaxiFareEstimator.TaxiFareEstimate at2330 =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(23, 30));
        TaxiFareEstimator.TaxiFareEstimate atMidnight =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(0, 0));
        TaxiFareEstimator.TaxiFareEstimate at0130 =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(1, 30));

        assertTrue(at2330.surchargeLabel().contains("40%"));
        assertTrue(atMidnight.surchargeLabel().contains("40%"));
        assertTrue(at0130.surchargeLabel().contains("40%"));
    }

    /** 02시~04시는 다시 20% 할증으로 낮아져야 한다. */
    @Test
    void 새벽_2시부터_4시까지는_다시_20퍼센트_할증이다() {
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(3, 0));

        assertTrue(estimate.surchargeLabel().contains("20%"));
    }

    /** 04시 정각부터는 할증이 끝나야 한다. */
    @Test
    void 새벽_4시부터는_할증이_끝난다() {
        TaxiFareEstimator.TaxiFareEstimate estimate =
                estimator.estimate(126.9707, 37.5547, 127.0276, 37.4979, LocalTime.of(4, 0));

        assertFalse(estimate.hasSurcharge());
    }
}
