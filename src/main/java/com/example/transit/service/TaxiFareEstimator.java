package com.example.transit.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;

/**
 * 막차/버스/심야버스 어느 것도 안 되는 경우의 마지막 대안으로 보여줄 예상 택시요금을 계산한다.
 * 실제 도로 경로나 실시간 요금 API를 쓰는 게 아니라, 좌표 간 직선거리(Haversine)에 도로거리
 * 보정을 더해 서울 중형택시 요금 체계(2023년 기준)로 어림잡은 값이다 - "정확한 요금"이 아니라
 * "대략 이 정도"라는 걸 화면에도 "예상"으로 명시해야 한다.
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
    /** 심야(22:00~04:00) 할증률. */
    private static final double LATE_NIGHT_SURCHARGE_RATE = 0.2;
    private static final int LATE_NIGHT_START_HOUR = 22;
    private static final int LATE_NIGHT_END_HOUR = 4;
    /** 100원 단위로 반올림해서 보여준다 - 어차피 예상치라 원 단위까지 정확한 척할 필요가 없다. */
    private static final int ROUNDING_UNIT_WON = 100;

    public TaxiFareEstimate estimate(double sx, double sy, double ex, double ey) {
        double roadMeters = distanceMeters(sy, sx, ey, ex) * ROAD_DISTANCE_FACTOR;
        int fare = BASE_FARE_WON;
        if (roadMeters > BASE_DISTANCE_METERS) {
            double extraMeters = roadMeters - BASE_DISTANCE_METERS;
            long units = (long) Math.ceil(extraMeters / DISTANCE_UNIT_METERS);
            fare += (int) (units * DISTANCE_UNIT_FARE_WON);
        }

        boolean lateNight = isLateNight(LocalTime.now());
        if (lateNight) {
            fare = (int) Math.round(fare * (1 + LATE_NIGHT_SURCHARGE_RATE));
        }
        fare = Math.round((float) fare / ROUNDING_UNIT_WON) * ROUNDING_UNIT_WON;

        return new TaxiFareEstimate(fare, lateNight);
    }

    private boolean isLateNight(LocalTime time) {
        return !time.isBefore(LocalTime.of(LATE_NIGHT_START_HOUR, 0))
                || time.isBefore(LocalTime.of(LATE_NIGHT_END_HOUR, 0));
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

    /**
     * @param fareWon         예상 요금(원), 100원 단위로 반올림.
     * @param lateNightSurcharge 심야(22:00~04:00) 할증이 적용됐는지 - 화면에 "심야 할증 포함" 표시용.
     */
    public record TaxiFareEstimate(int fareWon, boolean lateNightSurcharge) {
    }
}
