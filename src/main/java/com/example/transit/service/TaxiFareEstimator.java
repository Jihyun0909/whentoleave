package com.example.transit.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 검색과 추천 경로 사이에 항상 보여줄 "택시로 가면" 참고 정보(예상 소요시간·요금)를 계산한다.
 * 실제 도로 경로나 실시간 요금 API를 쓰는 게 아니라, 좌표 간 직선거리(Haversine)에 도로거리
 * 보정을 더해 서울 중형택시 요금 체계로 어림잡은 값이다 - "정확한 요금"이 아니라 "대략 이 정도"
 * 라는 걸 화면에도 "예상"으로 명시해야 한다.
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
    /** 직선거리는 실제로 달리는 도로거리보다 짧으므로 20% 더 본다. */
    private static final double ROAD_DISTANCE_FACTOR = 1.2;
    /** 100원 단위로 반올림해서 보여준다 - 어차피 예상치라 원 단위까지 정확한 척할 필요가 없다. */
    private static final int ROUNDING_UNIT_WON = 100;
    /** 도심 교통 체증까지 감안한 평균 주행 속도 추정치(km/h). */
    private static final double AVERAGE_SPEED_KMH = 25;

    private static final List<SurchargeBand> SURCHARGE_BANDS = List.of(
            new SurchargeBand(LocalTime.of(22, 0), LocalTime.of(23, 0), 0.2),
            new SurchargeBand(LocalTime.of(23, 0), LocalTime.of(2, 0), 0.4),
            new SurchargeBand(LocalTime.of(2, 0), LocalTime.of(4, 0), 0.2)
    );

    public TaxiFareEstimate estimate(double sx, double sy, double ex, double ey) {
        return estimate(sx, sy, ex, ey, LocalTime.now());
    }

    /** 심야 할증 구간 계산을 결정적으로 테스트하기 위해 기준 시각을 직접 받는 오버로드. */
    TaxiFareEstimate estimate(double sx, double sy, double ex, double ey, LocalTime now) {
        double roadMeters = distanceMeters(sy, sx, ey, ex) * ROAD_DISTANCE_FACTOR;

        int baseFare = BASE_FARE_WON;
        if (roadMeters > BASE_DISTANCE_METERS) {
            double extraMeters = roadMeters - BASE_DISTANCE_METERS;
            long units = (long) Math.ceil(extraMeters / DISTANCE_UNIT_METERS);
            baseFare += (int) (units * DISTANCE_UNIT_FARE_WON);
        }

        SurchargeBand band = currentSurchargeBand(now);
        int fare = band == null ? baseFare : (int) Math.round(baseFare * (1 + band.rate()));
        fare = Math.round((float) fare / ROUNDING_UNIT_WON) * ROUNDING_UNIT_WON;

        int minutes = Math.max(1, (int) Math.ceil(roadMeters / 1000 / AVERAGE_SPEED_KMH * 60));

        return new TaxiFareEstimate(fare, minutes, band == null ? null : band.label());
    }

    /** 23~02시 구간은 자정을 넘나들어서 별도 처리한다. */
    private SurchargeBand currentSurchargeBand(LocalTime time) {
        for (SurchargeBand band : SURCHARGE_BANDS) {
            boolean wrapsMidnight = band.end().isBefore(band.start());
            boolean inBand = wrapsMidnight
                    ? !time.isBefore(band.start()) || time.isBefore(band.end())
                    : !time.isBefore(band.start()) && time.isBefore(band.end());
            if (inBand) {
                return band;
            }
        }
        return null;
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

    private record SurchargeBand(LocalTime start, LocalTime end, double rate) {
        String label() {
            int ratePercent = (int) Math.round(rate * 100);
            return String.format("%02d시~%02d시 할증 %d%%", start.getHour(), end.getHour(), ratePercent);
        }
    }

    /**
     * @param fareWon        예상 요금(원), 100원 단위로 반올림.
     * @param estimatedMinutes 예상 소요시간(분). 평균 주행속도 추정치 기반이라 실제 교통상황에
     *                          따라 달라질 수 있다.
     * @param surchargeLabel 지금 적용된 심야 할증 구간 설명(예: "22시~23시 할증 20%"). 할증
     *                       시간대가 아니면 null.
     */
    public record TaxiFareEstimate(int fareWon, int estimatedMinutes, String surchargeLabel) {
        public boolean hasSurcharge() {
            return surchargeLabel != null;
        }
    }
}
