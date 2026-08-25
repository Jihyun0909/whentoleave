package com.example.transit.service;

import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Google Routes 응답(GoogleRoutesResponse)에서 대중교통 구간을 추출하는 로직 검증.
 * 역명→TAGO stationId 해석은 테스트마다 {@link #extractor}로 만든 고정 매핑 스텁을 쓴다 -
 * 방향(wayCode) 판별용 fetchSchedule은 항상 빈 응답을 줘서 wayCode는 항상 기본값(2, 하행)으로
 * 떨어진다(이 테스트들은 방향 판별 정확도를 다루지 않는다).
 */
class RouteLegExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 수유(4호선, id=414) -> 환승 -> 동대문역사문화공원(2호선, id=205) -> 왕십리.
     * startName/lane으로 화면 표시용 역이름/노선이름도 같이 뽑히는지, 도보시간이 버퍼로
     * 잘 누적되는지 확인한다.
     */
    @Test
    void 지하철_구간과_환승_버퍼를_추출한다() {
        RouteLegExtractor extractor = extractor(Map.of(
                "수유", new StationFixture("414", "수도권4호선"),
                "동대문역사문화공원", new StationFixture("205", "수도권2호선")
        ));
        GoogleRoutesResponse response = response(route(
                walkStep(1),
                subwayStep(17, "수유", "수도권4호선"),
                walkStep(3),
                subwayStep(5, "동대문역사문화공원", "수도권2호선"),
                walkStep(1)
        ));

        List<TransitLeg> legs = extractor.extract(response);

        assertEquals(2, legs.size());
        // 출발 전 도보 1분이 버퍼로 잡힘(계산에선 안 쓰임)
        assertEquals(TransitLeg.subway("414", 2, 17, 1, Set.of(), "수유", null, "수도권4호선"), legs.get(0));
        // 환승 도보 3분이 버퍼로 들어감
        assertEquals(TransitLeg.subway("205", 2, 5, 3, Set.of(), "동대문역사문화공원", null, "수도권2호선"), legs.get(1));
    }

    /**
     * Google Routes는 ODsay의 passStopList(구간별 정차역 순서)에 대응하는 정보를 안 준다 -
     * 그래서 earlierStopNames는 항상 빈 Set이다. 이건 회귀다: LastDepartureCalculator의
     * "단축운행으로 도착역 전에 끊기는 막차 후보 제외" 필터링이 실질적으로 항상 무력화된다
     * (빈 Set이면 아무것도 안 거른다). 후속 과제로 남겨두고, 여기서는 이 degraded 동작을
     * 명시적으로 고정해둔다.
     */
    @Test
    void earlierStopNames는_항상_비어있다_Google에는_대응_정보가_없음() {
        RouteLegExtractor extractor = extractor(Map.of("테스트역", new StationFixture("331", "1호선")));
        GoogleRoutesResponse response = response(route(subwayStep(12, "테스트역", "1호선")));

        List<TransitLeg> legs = extractor.extract(response);

        assertTrue(legs.get(0).earlierStopNames().isEmpty());
    }

    @Test
    void 경로가_없으면_예외를_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = new GoogleRoutesResponse(List.of());

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }

    @Test
    void 버스_구간이_섞여있으면_예외를_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = response(route(busStep(10, "정류장", "146")));

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }

    /**
     * 이슈 #8 회귀 테스트. Google도 경로 후보를 여러 개 줄 수 있는데(1순위가 항상 최선은
     * 아님 — 배차가 뜸한 구간을 타는 경로가 1순위로 나올 수 있다), extractAll은 지하철
     * 전용인 후보를 전부 뽑아야 한다. 버스가 섞인 후보는 조용히 걸러내고 나머지는 유지한다.
     */
    @Test
    void extractAll은_지하철_전용인_경로_후보를_전부_뽑고_버스_섞인_후보는_건너뛴다() {
        RouteLegExtractor extractor = extractor(Map.of(
                "역A", new StationFixture("414", "1호선"),
                "역B", new StationFixture("1824", "1호선"),
                "역C", new StationFixture("220", "1호선")
        ));
        GoogleRoutesResponse response = response(
                route(subwayStep(17, "역A", "1호선")),
                route(busStep(10, "정류장", "146")), // 버스 - 이 후보는 스킵
                route(subwayStep(59, "역B", "1호선"), subwayStep(20, "역C", "1호선"))
        );

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(2, candidates.size());
        assertEquals(1, candidates.get(0).legs().size());
        assertEquals("414", candidates.get(0).legs().get(0).stationId());
        assertEquals(2, candidates.get(1).legs().size());
        assertEquals("1824", candidates.get(1).legs().get(0).stationId());
        assertEquals("220", candidates.get(1).legs().get(1).stationId());
    }

    /**
     * 사당->신논현 실사용 발견 케이스: 마지막 지하철 구간(강남 하차) 뒤에 도보 9분이 남는데,
     * 다음 지하철 구간이 없어서 붙일 곳이 없다고 통째로 버려지면 안 된다. extractAll이 각
     * 후보의 finalWalkMinutes로 따로 들고 나오는지 확인한다.
     */
    @Test
    void 마지막_지하철_구간_뒤에_남은_도보는_finalWalkMinutes로_따로_들고나온다() {
        RouteLegExtractor extractor = extractor(Map.of("사당", new StationFixture("900", "수도권2호선")));
        GoogleRoutesResponse response = response(route(
                subwayStep(17, "사당", "수도권2호선"),
                walkStep(9)
        ));

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(1, candidates.size());
        RouteLegExtractor.ExtractedRoute route = candidates.get(0);
        assertEquals(1, route.legs().size());
        assertEquals(9, route.finalWalkMinutes());
    }

    /**
     * 출발지-목적지가 걸어갈 수 있을 만큼 가까우면 Google은 전 구간을 도보로만 준다 -
     * 대중교통 구간이 하나도 없다. 이 경우 "경로 없음"과 구분되는 {@link WalkOnlyRouteException}을
     * 던져서, 호출하는 쪽이 "대중교통 운행 종료" 같은 엉뚱한 안내 대신 "도보 N분이면
     * 충분해요"를 보여줄 수 있게 해야 한다.
     */
    @Test
    void 전_구간이_도보뿐이면_WalkOnlyRouteException을_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = response(route(walkStep(6)));

        WalkOnlyRouteException e = assertThrows(WalkOnlyRouteException.class, () -> extractor.extract(response));
        assertEquals(6, e.walkMinutes());
    }

    /** extractAll에서도 모든 후보가 도보뿐이면(대중교통 후보가 하나도 없으면), 가장 짧은 도보시간을 들고 예외를 던진다. */
    @Test
    void extractAll에서_모든_후보가_도보뿐이면_가장_짧은_도보시간으로_예외를_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = response(route(walkStep(9)), route(walkStep(5)));

        WalkOnlyRouteException e = assertThrows(WalkOnlyRouteException.class, () -> extractor.extractAll(response));
        assertEquals(5, e.walkMinutes());
    }

    /** 후보 중 하나라도 실제 대중교통 경로가 있으면, 도보 전용 후보는 조용히 버리고 그걸 쓴다. */
    @Test
    void 대중교통_후보가_하나라도_있으면_도보_전용_후보는_무시한다() {
        RouteLegExtractor extractor = extractor(Map.of("테스트역", new StationFixture("300", "1호선")));
        GoogleRoutesResponse response = response(
                route(walkStep(5)),
                route(subwayStep(10, "테스트역", "1호선"))
        );

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(1, candidates.size());
        assertEquals("300", candidates.get(0).legs().get(0).stationId());
    }

    @Test
    void 모든_경로_후보에_버스가_섞여있으면_extractAll도_예외를_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = response(route(busStep(10, "정류장", "146")));

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extractAll(response));
    }

    // ---------- 픽스처 빌더 ----------

    private record StationFixture(String stationId, String lineNameShort) {
    }

    private RouteLegExtractor extractor(Map<String, StationFixture> stationsByName) {
        TagoSubwayApiClient subwayClient = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse findStations(String stationName) {
                StationFixture fixture = stationsByName.get(stationName);
                return fixture == null ? emptyResponse()
                        : stationResponse(fixture.stationId(), fixture.lineNameShort());
            }

            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyResponse();
            }
        };
        TagoBusRouteDetailApiClient busClient = new TagoBusRouteDetailApiClient("http://dummy", "dummy");
        return new RouteLegExtractor(subwayClient, busClient, new TagoCityCodeResolver(null));
    }

    private GoogleRoutesResponse response(GoogleRoutesResponse.Route... routes) {
        return new GoogleRoutesResponse(List.of(routes));
    }

    private GoogleRoutesResponse.Route route(GoogleRoutesResponse.Step... steps) {
        return new GoogleRoutesResponse.Route(List.of(new GoogleRoutesResponse.Leg(List.of(steps), 0, "0s")));
    }

    private GoogleRoutesResponse.Step walkStep(int minutes) {
        return new GoogleRoutesResponse.Step("WALK", 0, (minutes * 60) + "s", null);
    }

    private GoogleRoutesResponse.Step subwayStep(int rideMinutes, String departureStopName, String lineNameShort) {
        return transitStep(rideMinutes, departureStopName, lineNameShort, "SUBWAY");
    }

    private GoogleRoutesResponse.Step busStep(int rideMinutes, String departureStopName, String lineNameShort) {
        return transitStep(rideMinutes, departureStopName, lineNameShort, "BUS");
    }

    private GoogleRoutesResponse.Step transitStep(int rideMinutes, String departureStopName, String lineNameShort,
                                                    String vehicleType) {
        GoogleRoutesResponse.TransitLine line =
                new GoogleRoutesResponse.TransitLine(lineNameShort, lineNameShort, new GoogleRoutesResponse.Vehicle(vehicleType));
        GoogleRoutesResponse.Stop departureStop =
                new GoogleRoutesResponse.Stop(departureStopName, new GoogleRoutesResponse.Location(
                        new GoogleRoutesResponse.LatLng(37.0, 127.0)));
        GoogleRoutesResponse.StopDetails stopDetails = new GoogleRoutesResponse.StopDetails(departureStop, null, null, null);
        GoogleRoutesResponse.TransitDetails details = new GoogleRoutesResponse.TransitDetails(stopDetails, null, null, line, null);
        return new GoogleRoutesResponse.Step("TRANSIT", 0, (rideMinutes * 60) + "s", details);
    }

    private TagoBusArrivalResponse stationResponse(String stationId, String lineName) {
        try {
            String json = """
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": { "item":
                        { "subwayStationId": "%s", "subwayStationName": "테스트역", "subwayRouteName": "%s" }
                      } } } }
                    """.formatted(stationId, lineName);
            return mapper.readValue(json, TagoBusArrivalResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TagoBusArrivalResponse emptyResponse() {
        try {
            return mapper.readValue("""
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": {} } } }
                    """, TagoBusArrivalResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
