package com.example.transit.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 검색과 추천 경로 사이에 항상 보여줄 "택시로 가면" 참고 정보(예상 소요시간·요금)를 계산한다.
 * 실제 도로 경로나 실시간 요금/교통 API를 쓰는 게 아니라, 좌표 간 직선거리(Haversine)에 도로거리
 * 보정을 더해 서울 중형택시 요금 체계로, 소요시간은 시간대별 평균 주행속도 추정치로 어림잡은
 * 값이다 - "정확한 값"이 아니라 "대략 이 정도"라는 걸 화면에도 "예상"으로 명시해야 한다.
 * <p>
 * 요금·소요시간 모두 "언제 이 구간을 달리는지"에 따라 달라지므로(할증 시간대, 교통 혼잡도),
 * 현재 시각이 아니라 실제로 이 이동이 일어나는 시각(목표 도착시간 등)을 받아서 계산해야 한다 -
 * 호출하는 쪽(LastDepartureViewController)이 그 시각을 정해서 넘긴다.
 * <p>
 * 심야 할증은 서울시 2023년 개편 기준 3단계 구간(22~23시 20%, 23~02시 40%, 02~04시 20%)을
 * 쓴다 - 경기/인천은 시·군마다 조례가 달라 이 앱이 다루는 좌표만으로 정확한 지자체별 요율을
 * 가릴 수 없어서, 수도권 전체에 이 기준을 그대로 적용한다(관공서 확인 없이 지자체별로 다른
 * 숫자를 만들어내는 것보다, 검증된 서울 기준 하나로 통일하는 쪽이 낫다고 판단했다).
 */
@Service
public class TaxiFareEstimator {

    /** 기본요금(원)과 그 기본요금으로 갈 수 있는 거리(m). */
    private static final int BASE_FARE_WON = 4_800;
    private static final double BASE_DISTANCE_METERS = 1_600;
    /** 기본거리 초과 후 이 거리(m)마다 추가요금이 붙는다. */
    private static final double DISTANCE_UNIT_METERS = 131;
    private static final int DISTANCE_UNIT_FARE_WON = 100;
    /**
     * 직선거리는 실제로 달리는 도로거리보다 짧다. 카카오맵 실측값(아래 SPEED_BANDS 주석 참고)의
     * 소요시간과 앞뒤가 맞는 값으로 맞췄다 - 이 값을 바꾸면 거리요금과 소요시간이 같이 움직이므로
     * 둘 다 실측과 비교해서 조정해야 한다.
     */
    private static final double ROAD_DISTANCE_FACTOR = 1.30;
    /** 100원 단위로 반올림해서 보여준다 - 어차피 예상치라 원 단위까지 정확한 척할 필요가 없다. */
    private static final int ROUNDING_UNIT_WON = 100;

    private static final List<SurchargeBand> SURCHARGE_BANDS = List.of(
            new SurchargeBand(new TimeBand(LocalTime.of(22, 0), LocalTime.of(23, 0)), 0.2),
            new SurchargeBand(new TimeBand(LocalTime.of(23, 0), LocalTime.of(2, 0)), 0.4),
            new SurchargeBand(new TimeBand(LocalTime.of(2, 0), LocalTime.of(4, 0)), 0.2)
    );

    /**
     * 정체가 거의 없을 때의 주행속도(km/h). 심야 시간대 속도이자, 아래 시간요금 계산에서
     * "정체로 얼마나 지연됐는지"를 재는 기준선이기도 하다.
     */
    private static final double FREE_FLOW_SPEED_KMH = 50;
    /** 출퇴근 혼잡/심야 어느 쪽에도 안 걸리는 평시 평균 주행속도(km/h). */
    private static final double DEFAULT_SPEED_KMH = 26;

    /**
     * 시간대별 평균 주행속도. 카카오맵 실측 비교(신사역->강북구 번동, 직선 13.7km 구간)로 맞췄다:
     * 17:28 출발 42분, 다음날 01:00 출발 22분. 출퇴근 혼잡 시간대는 그보다 더 느려지는 것으로 둔다.
     */
    private static final List<SpeedBand> SPEED_BANDS = List.of(
            new SpeedBand(new TimeBand(LocalTime.of(0, 0), LocalTime.of(5, 0)), FREE_FLOW_SPEED_KMH),
            new SpeedBand(new TimeBand(LocalTime.of(7, 0), LocalTime.of(9, 0)), 18),
            new SpeedBand(new TimeBand(LocalTime.of(18, 0), LocalTime.of(20, 0)), 18)
    );

    /**
     * 택시 미터기는 거리요금과 별개로, 속도가 시속 15.33km 아래로 떨어지면 시간요금(100원/30초,
     * 즉 분당 200원)을 매긴다. 거리요금만 계산했을 때 실측(카카오맵 20,100원)보다 2,900원가량
     * 싸게 나온 게 이 몫이었다.
     * <p>
     * 정체로 지연된 시간(= 실제 소요시간 - 정체 없을 때 소요시간) 전부가 시간요금 대상은 아니라
     * (완전히 멈춰 있는 구간만 해당) 그중 일부만 매긴다. 비율은 위 실측값에 맞춘 값이다.
     */
    private static final int TIME_FARE_WON_PER_MINUTE = 200;
    private static final double CONGESTION_TIME_CHARGED_RATIO = 0.7;

    public TaxiFareEstimate estimate(double sx, double sy, double ex, double ey) {
        return estimate(sx, sy, ex, ey, LocalTime.now());
    }

    /**
     * @param referenceTime 이 이동이 실제로 일어나는 시각(예: 목표 도착시간). 할증 여부와
     *                      교통 혼잡도 추정에 쓰인다 - "지금"이 아니라 이 값 기준으로 계산해야
     *                      새벽 시간대를 목표로 검색했을 때 지금(예: 오후) 기준 할증/혼잡도가
     *                      잘못 적용되지 않는다.
     */
    public TaxiFareEstimate estimate(double sx, double sy, double ex, double ey, LocalTime referenceTime) {
        double roadKm = distanceMeters(sy, sx, ey, ex) * ROAD_DISTANCE_FACTOR / 1000;

        double speedKmh = SPEED_BANDS.stream()
                .filter(band -> band.timeBand().contains(referenceTime))
                .findFirst().map(SpeedBand::speedKmh).orElse(DEFAULT_SPEED_KMH);
        // 요금 계산엔 반올림하지 않은 소요시간을 쓴다 - 화면 표시용으로 올림/최소 1분 처리한
        // 값을 그대로 넣으면, 몇백 미터짜리 거리에서 있지도 않은 정체 지연이 잡혀 시간요금이
        // 붙어버린다.
        double exactMinutes = roadKm / speedKmh * 60;
        int minutes = Math.max(1, (int) Math.ceil(exactMinutes));

        int fare = distanceFare(roadKm) + congestionTimeFare(roadKm, exactMinutes);

        SurchargeBand surcharge = SURCHARGE_BANDS.stream()
                .filter(band -> band.timeBand().contains(referenceTime))
                .findFirst().orElse(null);
        if (surcharge != null) {
            fare = (int) Math.round(fare * (1 + surcharge.rate()));
        }
        fare = Math.round((float) fare / ROUNDING_UNIT_WON) * ROUNDING_UNIT_WON;

        return new TaxiFareEstimate(fare, minutes, surcharge == null ? null : surcharge.label());
    }

    /** 기본요금 + 기본거리 초과분에 대한 거리요금. */
    private int distanceFare(double roadKm) {
        double roadMeters = roadKm * 1000;
        if (roadMeters <= BASE_DISTANCE_METERS) {
            return BASE_FARE_WON;
        }
        long units = (long) Math.ceil((roadMeters - BASE_DISTANCE_METERS) / DISTANCE_UNIT_METERS);
        return BASE_FARE_WON + (int) (units * DISTANCE_UNIT_FARE_WON);
    }

    /**
     * 정체로 지연된 만큼 붙는 시간요금. 정체가 없는 심야 시간대는 지연이 0이라 자연히 안 붙는다.
     */
    private int congestionTimeFare(double roadKm, double exactMinutes) {
        double freeFlowMinutes = roadKm / FREE_FLOW_SPEED_KMH * 60;
        double delayMinutes = Math.max(0, exactMinutes - freeFlowMinutes);
        return (int) Math.round(delayMinutes * TIME_FARE_WON_PER_MINUTE * CONGESTION_TIME_CHARGED_RATIO);
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }

    /** 자정을 넘나드는 구간(예: 23시~02시)도 다루는 시간대. */
    private record TimeBand(LocalTime start, LocalTime end) {
        boolean contains(LocalTime time) {
            boolean wrapsMidnight = end.isBefore(start);
            return wrapsMidnight
                    ? !time.isBefore(start) || time.isBefore(end)
                    : !time.isBefore(start) && time.isBefore(end);
        }
    }

    private record SurchargeBand(TimeBand timeBand, double rate) {
        String label() {
            int ratePercent = (int) Math.round(rate * 100);
            return String.format("%02d시~%02d시 할증 %d%%", timeBand.start().getHour(), timeBand.end().getHour(), ratePercent);
        }
    }

    private record SpeedBand(TimeBand timeBand, double speedKmh) {
    }

    /**
     * @param fareWon          예상 요금(원), 100원 단위로 반올림.
     * @param estimatedMinutes 예상 소요시간(분). 시간대별 평균 주행속도 추정치 기반이라 실제
     *                         교통상황에 따라 달라질 수 있다.
     * @param surchargeLabel   기준 시각에 적용된 심야 할증 구간 설명(예: "22시~23시 할증 20%").
     *                         할증 시간대가 아니면 null.
     */
    public record TaxiFareEstimate(int fareWon, int estimatedMinutes, String surchargeLabel) {
        public boolean hasSurcharge() {
            return surchargeLabel != null;
        }
    }
}
