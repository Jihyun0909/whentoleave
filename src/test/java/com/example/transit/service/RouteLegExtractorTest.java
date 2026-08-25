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
     * 잘 누적되는지 확인한다. Google 원시 도보시간(1분/3분)에 WALK_BUFFER_MINUTES(2분)가
     * 더해져서 3분/5분으로 들어간다 - 실사용 피드백(2026-08-25)으로 추가된 여유시간.
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
        // 출발 전 도보 1분 + 버퍼 2분 = 3분(계산에선 안 쓰임)
        assertEquals(TransitLeg.subway("414", 2, 17, 3, Set.of(), "수유", null, "수도권4호선"), legs.get(0));
        // 환승 도보 3분 + 버퍼 2분 = 5분
        assertEquals(TransitLeg.subway("205", 2, 5, 5, Set.of(), "동대문역사문화공원", null, "수도권2호선"), legs.get(1));
    }

    /**
     * Google Routes는 ODsay의 passStopList(구간별 정차역 순서)에 대응하는 정보를 안 준다.
     * 대신 TAGO 시간표에서 승차역/도착역 각각의 "이 방향으로 실제 존재하는 종착역 이름 집합"을
     * 구해서 차집합으로 단축운행 종착역을 재구성한다({@link RouteLegExtractor#resolveEarlierStopNames}).
     * <p>
     * 승차역(가)에서는 "나"(단축운행, 도착역 전에 끊김)와 "라"(도착역을 지나 더 감) 두 종착역이
     * 있고, 도착역(다)에서 같은 방향으로 조회하면 "라"만 남는다(도착역을 지나야 갈 수 있는
     * 종착역만 도착역 자신의 시간표에도 나타나므로) - 그 차집합인 "나"만 걸러야 할 이름이다.
     */
    @Test
    void 단축운행_종착역을_승차역과_도착역의_종착역_집합_차이로_걸러낸다() {
        RouteLegExtractor extractor = extractor(
                Map.of("가", new StationFixture("100", "1호선"), "다", new StationFixture("300", "1호선")),
                Map.of("100:D", Set.of("나", "라"), "300:D", Set.of("라")));
        GoogleRoutesResponse response = response(route(subwayStep(12, "가", "다", "1호선")));

        List<TransitLeg> legs = extractor.extract(response);

        assertEquals(Set.of("나"), legs.get(0).earlierStopNames());
    }

    /**
     * 도착역 자체가 어떤 열차의 종착역이면(더 안 가고 딱 거기서 끝나는 열차), 그 열차는 도착역
     * "출발" 시간표에는 안 잡히지만(더 갈 데가 없으니) 도착역까지는 정상적으로 데려다준다.
     * 승차역 TERMINI에만 있고 도착역 TERMINI에는 없다는 이유로 잘못 걸러지면 안 된다.
     */
    @Test
    void 도착역_자신이_종착역인_열차는_걸러내지_않는다() {
        RouteLegExtractor extractor = extractor(
                Map.of("가", new StationFixture("100", "1호선"), "다", new StationFixture("300", "1호선", "다")),
                Map.of("100:D", Set.of("다")));
        GoogleRoutesResponse response = response(route(subwayStep(12, "가", "다", "1호선")));

        List<TransitLeg> legs = extractor.extract(response);

        assertTrue(legs.get(0).earlierStopNames().isEmpty());
    }

    /** 도착역의 TAGO 역을 못 찾으면(역명 불일치 등) 걸러내는 근거가 불확실하므로 안전하게 아무것도 안 거른다. */
    @Test
    void 도착역을_TAGO에서_못_찾으면_아무것도_거르지_않는다() {
        RouteLegExtractor extractor = extractor(
                Map.of("가", new StationFixture("100", "1호선")),
                Map.of("100:D", Set.of("나", "라")));
        GoogleRoutesResponse response = response(route(subwayStep(12, "가", "다", "1호선")));

        List<TransitLeg> legs = extractor.extract(response);

        assertTrue(legs.get(0).earlierStopNames().isEmpty());
    }

    /**
     * 2호선처럼 headsign이 종착역명이 아니라 "외선순환"/"내선순환" 순환 방향 텍스트인 노선은
     * 종착역명 매칭이 아예 성립하지 않는다. 2026-08-25 강남역(TAGO MTRS12222) 실제 시간표로
     * 확인한 규칙(외선=상행, 내선=하행)대로 직접 매핑되는지 확인한다.
     */
    @Test
    void 외선순환_headsign은_상행으로_내선순환은_하행으로_매핑한다() {
        RouteLegExtractor extractor = extractor(Map.of("강남", new StationFixture("MTRS12222", "2호선")));

        GoogleRoutesResponse outer = response(route(
                subwayStepWithHeadsign(5, "강남", "2호선", "외선순환 방면")));
        GoogleRoutesResponse inner = response(route(
                subwayStepWithHeadsign(5, "강남", "2호선", "내선순환 방면")));

        assertEquals(1, extractor.extract(outer).get(0).wayCode());
        assertEquals(2, extractor.extract(inner).get(0).wayCode());
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
        assertEquals(11, route.finalWalkMinutes()); // 원시 9분 + 버퍼 2분
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
        assertEquals(8, e.walkMinutes()); // 원시 6분 + 버퍼 2분
    }

    /** extractAll에서도 모든 후보가 도보뿐이면(대중교통 후보가 하나도 없으면), 가장 짧은 도보시간을 들고 예외를 던진다. */
    @Test
    void extractAll에서_모든_후보가_도보뿐이면_가장_짧은_도보시간으로_예외를_던진다() {
        RouteLegExtractor extractor = extractor(Map.of());
        GoogleRoutesResponse response = response(route(walkStep(9)), route(walkStep(5)));

        WalkOnlyRouteException e = assertThrows(WalkOnlyRouteException.class, () -> extractor.extractAll(response));
        assertEquals(7, e.walkMinutes()); // 원시 5분(더 짧은 쪽) + 버퍼 2분
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

    private record StationFixture(String stationId, String lineNameShort, String tagoName) {
        private StationFixture(String stationId, String lineNameShort) {
            this(stationId, lineNameShort, "테스트역");
        }
    }

    private RouteLegExtractor extractor(Map<String, StationFixture> stationsByName) {
        return extractor(stationsByName, Map.of());
    }

    /**
     * @param stationsByName                   Google 역명 -> TAGO 역 매칭 결과
     * @param terminiByStationIdAndDirection    "{stationId}:{U|D}" -> 그 역·방향의 실제 종착역 이름 집합
     *                                          (단축운행 필터링용 TERMINI 조회 스텁, {@link RouteLegExtractor#terminiOf})
     */
    private RouteLegExtractor extractor(Map<String, StationFixture> stationsByName,
                                         Map<String, Set<String>> terminiByStationIdAndDirection) {
        TagoSubwayApiClient subwayClient = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse findStations(String stationName) {
                StationFixture fixture = stationsByName.get(stationName);
                return fixture == null ? emptyResponse()
                        : stationResponse(fixture.stationId(), fixture.lineNameShort(), fixture.tagoName());
            }

            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                Set<String> termini = terminiByStationIdAndDirection.get(subwayStationId + ":" + upDownTypeCode);
                return termini == null ? emptyResponse() : terminiResponse(termini);
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
        return transitStep(rideMinutes, departureStopName, null, lineNameShort, "SUBWAY");
    }

    private GoogleRoutesResponse.Step subwayStep(int rideMinutes, String departureStopName, String arrivalStopName,
                                                   String lineNameShort) {
        return transitStep(rideMinutes, departureStopName, arrivalStopName, null, lineNameShort, "SUBWAY");
    }

    private GoogleRoutesResponse.Step subwayStepWithHeadsign(int rideMinutes, String departureStopName,
                                                               String lineNameShort, String headsign) {
        return transitStep(rideMinutes, departureStopName, null, headsign, lineNameShort, "SUBWAY");
    }

    private GoogleRoutesResponse.Step busStep(int rideMinutes, String departureStopName, String lineNameShort) {
        return transitStep(rideMinutes, departureStopName, null, null, lineNameShort, "BUS");
    }

    private GoogleRoutesResponse.Step transitStep(int rideMinutes, String departureStopName, String arrivalStopName,
                                                    String lineNameShort, String vehicleType) {
        return transitStep(rideMinutes, departureStopName, arrivalStopName, null, lineNameShort, vehicleType);
    }

    private GoogleRoutesResponse.Step transitStep(int rideMinutes, String departureStopName, String arrivalStopName,
                                                    String headsign, String lineNameShort, String vehicleType) {
        GoogleRoutesResponse.TransitLine line =
                new GoogleRoutesResponse.TransitLine(lineNameShort, lineNameShort, new GoogleRoutesResponse.Vehicle(vehicleType));
        GoogleRoutesResponse.Stop departureStop =
                new GoogleRoutesResponse.Stop(departureStopName, new GoogleRoutesResponse.Location(
                        new GoogleRoutesResponse.LatLng(37.0, 127.0)));
        GoogleRoutesResponse.Stop arrivalStop = arrivalStopName == null ? null
                : new GoogleRoutesResponse.Stop(arrivalStopName, new GoogleRoutesResponse.Location(
                        new GoogleRoutesResponse.LatLng(37.0, 127.0)));
        GoogleRoutesResponse.StopDetails stopDetails = new GoogleRoutesResponse.StopDetails(departureStop, arrivalStop, null, null);
        GoogleRoutesResponse.TransitDetails details = new GoogleRoutesResponse.TransitDetails(stopDetails, headsign, null, line, null);
        return new GoogleRoutesResponse.Step("TRANSIT", 0, (rideMinutes * 60) + "s", details);
    }

    private TagoBusArrivalResponse stationResponse(String stationId, String lineName, String tagoName) {
        try {
            String json = """
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": { "item":
                        { "subwayStationId": "%s", "subwayStationName": "%s", "subwayRouteName": "%s" }
                      } } } }
                    """.formatted(stationId, tagoName, lineName);
            return mapper.readValue(json, TagoBusArrivalResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@link RouteLegExtractor#terminiOf}가 읽는 endSubwayStationNm 목록 응답. */
    private TagoBusArrivalResponse terminiResponse(Set<String> endStationNames) {
        try {
            String items = endStationNames.stream()
                    .map(name -> "{ \"endSubwayStationNm\": \"%s\" }".formatted(name))
                    .collect(java.util.stream.Collectors.joining(","));
            String json = """
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": { "item": [ %s ] } } } }
                    """.formatted(items);
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
