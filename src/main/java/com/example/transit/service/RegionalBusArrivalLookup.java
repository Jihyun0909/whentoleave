package com.example.transit.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 좌표 하나로 TAGO -> 경기(GBIS) -> 인천(BIMS) 순서로 시도해 그 정류장의 실시간 버스 도착정보를 찾는다.
 * <p>
 * 좌표만으로 "이 지점이 서울/경기/인천 중 어디 관할인가"를 미리 정확히 가릴 방법이 없어서, 지역을
 * 판별하는 대신 순서대로 시도한다 - TAGO가 사실상 전국(서울 포함)을 커버해 대부분 여기서 잡히고,
 * 경기/인천은 TAGO가 못 찾은 나머지를 보완하는 순서로 둔다. 인천은 아직 좌표기반 정류소 조회
 * API 자체가 없어(IncheonStationApiClient 클래스 주석 참고) 사실상 항상 빈 값이지만, 나중에
 * (정류소 파일데이터 기반 매칭 등으로) 붙이면 이 순서 그대로 자동으로 동작한다.
 */
@Service
public class RegionalBusArrivalLookup {

    private final TagoBusStopLookup tagoStopLookup;
    private final RealtimeTagoBusArrivalLookup tagoArrivalLookup;
    private final GyeonggiBusStopLookup gyeonggiStopLookup;
    private final RealtimeGyeonggiBusArrivalLookup gyeonggiArrivalLookup;
    private final IncheonBusStopLookup incheonStopLookup;
    private final RealtimeIncheonBusArrivalLookup incheonArrivalLookup;

    public RegionalBusArrivalLookup(TagoBusStopLookup tagoStopLookup,
                                     RealtimeTagoBusArrivalLookup tagoArrivalLookup,
                                     GyeonggiBusStopLookup gyeonggiStopLookup,
                                     RealtimeGyeonggiBusArrivalLookup gyeonggiArrivalLookup,
                                     IncheonBusStopLookup incheonStopLookup,
                                     RealtimeIncheonBusArrivalLookup incheonArrivalLookup) {
        this.tagoStopLookup = tagoStopLookup;
        this.tagoArrivalLookup = tagoArrivalLookup;
        this.gyeonggiStopLookup = gyeonggiStopLookup;
        this.gyeonggiArrivalLookup = gyeonggiArrivalLookup;
        this.incheonStopLookup = incheonStopLookup;
        this.incheonArrivalLookup = incheonArrivalLookup;
    }

    public List<RealtimeBusArrival> findArrivals(double stationX, double stationY) {
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
