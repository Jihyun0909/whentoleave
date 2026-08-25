package com.example.transit.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 좌표 하나로 서울 -> TAGO -> 경기(GBIS) -> 인천(BIMS) 순서로 시도해 그 정류장의 실시간 버스
 * 도착정보를 찾는다.
 * <p>
 * 좌표만으로 "이 지점이 서울/경기/인천 중 어디 관할인가"를 미리 정확히 가릴 방법이 없어서, 지역을
 * 판별하는 대신 순서대로 시도한다. 서울을 맨 앞에 두는 이유는 TAGO가 서울 시내버스를 커버하지
 * 않기 때문이다 - 2026-08-18 라이브 확인 결과 TAGO 도시코드 목록(138개)에 서울이 없고, 서울
 * 좌표로 조회하면 0건이거나 그 자리를 지나는 경기/인천 소속 광역버스 정류소만 잡힌다. 서울을
 * 뒤에 두면 서울 시내버스 정류장에서 엉뚱한 광역버스 도착정보가 먼저 걸린다.
 * <p>
 * 인천은 아직 좌표기반 정류소 조회 API 자체가 없어(IncheonStationApiClient 클래스 주석 참고)
 * 사실상 항상 빈 값이지만, 나중에 붙이면 이 순서 그대로 자동으로 동작한다.
 */
@Service
public class RegionalBusArrivalLookup {

    private final RealtimeSeoulBusArrivalLookup seoulArrivalLookup;
    private final TagoBusStopLookup tagoStopLookup;
    private final RealtimeTagoBusArrivalLookup tagoArrivalLookup;
    private final GyeonggiBusStopLookup gyeonggiStopLookup;
    private final RealtimeGyeonggiBusArrivalLookup gyeonggiArrivalLookup;
    private final IncheonBusStopLookup incheonStopLookup;
    private final RealtimeIncheonBusArrivalLookup incheonArrivalLookup;

    public RegionalBusArrivalLookup(RealtimeSeoulBusArrivalLookup seoulArrivalLookup,
                                     TagoBusStopLookup tagoStopLookup,
                                     RealtimeTagoBusArrivalLookup tagoArrivalLookup,
                                     GyeonggiBusStopLookup gyeonggiStopLookup,
                                     RealtimeGyeonggiBusArrivalLookup gyeonggiArrivalLookup,
                                     IncheonBusStopLookup incheonStopLookup,
                                     RealtimeIncheonBusArrivalLookup incheonArrivalLookup) {
        this.seoulArrivalLookup = seoulArrivalLookup;
        this.tagoStopLookup = tagoStopLookup;
        this.tagoArrivalLookup = tagoArrivalLookup;
        this.gyeonggiStopLookup = gyeonggiStopLookup;
        this.gyeonggiArrivalLookup = gyeonggiArrivalLookup;
        this.incheonStopLookup = incheonStopLookup;
        this.incheonArrivalLookup = incheonArrivalLookup;
    }

    public List<RealtimeBusArrival> findArrivals(double stationX, double stationY) {
        List<RealtimeBusArrival> seoul = seoulArrivalLookup.findArrivals(stationX, stationY);
        if (!seoul.isEmpty()) {
            return seoul;
        }

        List<RealtimeBusArrival> tago = tagoStopLookup.findStop(stationX, stationY)
                .map(stop -> tagoArrivalLookup.findArrivals(stop.cityCode(), stop.nodeId()))
                .orElse(List.of());
        if (!tago.isEmpty()) {
            return tago;
        }

        List<RealtimeBusArrival> gyeonggi = gyeonggiStopLookup.findStationId(stationX, stationY)
                .map(gyeonggiArrivalLookup::findArrivals)
                .orElse(List.of());
        if (!gyeonggi.isEmpty()) {
            return gyeonggi;
        }

        return incheonStopLookup.findStationId(stationX, stationY)
                .map(incheonArrivalLookup::findArrivals)
                .orElse(List.of());
    }
}
