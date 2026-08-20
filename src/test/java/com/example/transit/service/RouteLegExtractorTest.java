package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayPathResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteLegExtractorTest {

    private final RouteLegExtractor extractor = new RouteLegExtractor();

    /**
     * 실제로 캡처한 ODsay 응답 기반 (수유(강북구청) -[4호선]-> 동대문역사문화공원 -환승- -[2호선]-> 왕십리).
     * startName/lane으로 화면 표시용 역이름/노선이름도 같이 뽑히는지 확인한다.
     */
    @Test
    void 실제_ODsay_응답_형태에서_지하철_구간과_환승_버퍼를_추출한다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 1, null, null, null, null, null),
                                new OdsayPathResponse.SubPath(1, 17, 414, 2, null, "수유",
                                        List.of(new OdsayPathResponse.Lane("수도권4호선"))),
                                new OdsayPathResponse.SubPath(3, 3, null, null, null, null, null),
                                new OdsayPathResponse.SubPath(1, 5, 205, 2, null, "동대문역사문화공원",
                                        List.of(new OdsayPathResponse.Lane("수도권2호선"))),
                                new OdsayPathResponse.SubPath(3, 1, null, null, null, null, null)
                        ))
                ))
        );

        List<TransitLeg> legs = extractor.extract(response);

        assertEquals(2, legs.size());
        // 출발 전 도보 1분이 버퍼로 잡힘(계산에선 안 쓰임)
        assertEquals(TransitLeg.subway(414, 2, 17, 1, Set.of(), "수유", null, "수도권4호선"), legs.get(0));
        // 환승 도보 3분이 버퍼로 들어감
        assertEquals(TransitLeg.subway(205, 2, 5, 3, Set.of(), "동대문역사문화공원", null, "수도권2호선"), legs.get(1));
    }

    /**
     * 실사용 검증 중 발견한 케이스 (수유->압구정, 충무로 환승). passStopList로 도착역 이전
     * 정차역 이름을 뽑아내는지 확인 (이 이름들로 나중에 단축운행 막차 후보를 걸러낸다).
     */
    @Test
    void passStopList에서_도착역_이전_정차역_이름을_뽑아낸다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 12, 331, 2,
                                        new OdsayPathResponse.PassStopList(List.of(
                                                new OdsayPathResponse.Station("충무로"),
                                                new OdsayPathResponse.Station("동대입구"),
                                                new OdsayPathResponse.Station("약수"),
                                                new OdsayPathResponse.Station("금호"),
                                                new OdsayPathResponse.Station("옥수"),
                                                new OdsayPathResponse.Station("압구정")
                                        )), null, null)
                        ))
                ))
        );

        List<TransitLeg> legs = extractor.extract(response);

        Set<String> earlierStops = legs.get(0).earlierStopNames();
        assertTrue(earlierStops.contains("약수")); // 압구정보다 앞이라 단축운행 후보에서 제외 대상
        assertTrue(earlierStops.contains("충무로"));
        assertEquals(false, earlierStops.contains("압구정")); // 도착역 자체는 제외 목록에 없어야 함
    }

    /**
     * ODsay는 실패해도 HTTP 200에 error 필드만 담아 준다(예: 일일 호출 한도 초과 429).
     * 이걸 "경로 없음"과 같이 취급하면 API 한도 초과가 "대중교통 운행이 종료되어 안내가
     * 불가능합니다"로 안내되는데, 기다린다고 해결되는 상황이 아니라 오해를 준다(실사용 중 발생).
     */
    @Test
    void API가_오류를_주면_경로없음과_구분되는_예외를_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(null,
                List.of(new OdsayPathResponse.Error("429", "Daily quota exceeded")));

        RouteSearchUnavailableException e = assertThrows(RouteSearchUnavailableException.class,
                () -> extractor.extractAll(response));
        assertTrue(e.getMessage().contains("429"));
    }

    @Test
    void 경로가_없으면_예외를_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(new OdsayPathResponse.Result(List.of()));

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }

    @Test
    void 버스_구간이_섞여있으면_예외를_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(3, List.of(
                                new OdsayPathResponse.SubPath(2, 10, null, null, null, null, null)
                        ))
                ))
        );

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }

    /**
     * 이슈 #8 회귀 테스트. ODsay는 경로 후보를 여러 개 줄 수 있는데(1순위가 항상 최선은 아님 —
     * 배차가 뜸한 구간을 타는 경로가 1순위로 나올 수 있다), extractAll은 지하철 전용인
     * 후보를 전부 뽑아야 한다. 버스가 섞인 후보는 조용히 걸러내고 나머지는 유지한다.
     */
    @Test
    void extractAll은_지하철_전용인_경로_후보를_전부_뽑고_버스_섞인_후보는_건너뛴다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 17, 414, 2, null, null, null)
                        )),
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(2, 10, null, null, null, null, null) // 버스 - 이 후보는 스킵
                        )),
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 59, 1824, 1, null, null, null),
                                new OdsayPathResponse.SubPath(1, 20, 220, 2, null, null, null)
                        ))
                ))
        );

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(2, candidates.size());
        assertEquals(1, candidates.get(0).legs().size());
        assertEquals(414, candidates.get(0).legs().get(0).stationId());
        assertEquals(2, candidates.get(1).legs().size());
        assertEquals(1824, candidates.get(1).legs().get(0).stationId());
        assertEquals(220, candidates.get(1).legs().get(1).stationId());
    }

    /**
     * 사당->신논현 실사용 발견 케이스: 마지막 지하철 구간(강남 하차) 뒤에 도보 9분이 남는데,
     * 다음 지하철 구간이 없어서 붙일 곳이 없다고 통째로 버려지면 안 된다 (실제 목적지인
     * 신논현까지 걸어야 하는 시간이 화면/계산에서 사라지는 버그였음). extractAll이 각 후보의
     * finalWalkMinutes로 따로 들고 나오는지 확인한다.
     */
    @Test
    void 마지막_지하철_구간_뒤에_남은_도보는_finalWalkMinutes로_따로_들고나온다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 17, 900, 2, null, "사당",
                                        List.of(new OdsayPathResponse.Lane("수도권2호선"))),
                                new OdsayPathResponse.SubPath(3, 9, null, null, null, null, null)
                        ))
                ))
        );

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(1, candidates.size());
        RouteLegExtractor.ExtractedRoute route = candidates.get(0);
        assertEquals(1, route.legs().size());
        assertEquals(9, route.finalWalkMinutes());
    }

    /**
     * 출발지-목적지가 걸어갈 수 있을 만큼 가까우면 ODsay는 전 구간을 도보(trafficType=3)로만
     * 준다 - 대중교통 구간이 하나도 없다. 이 경우 "경로 없음"과 구분되는
     * {@link WalkOnlyRouteException}을 던져서, 호출하는 쪽이 "대중교통 운행 종료" 같은
     * 엉뚱한 안내 대신 "도보 N분이면 충분해요"를 보여줄 수 있게 해야 한다.
     */
    @Test
    void 전_구간이_도보뿐이면_WalkOnlyRouteException을_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 6, null, null, null, null, null)
                        ))
                ))
        );

        WalkOnlyRouteException e = assertThrows(WalkOnlyRouteException.class, () -> extractor.extract(response));
        assertEquals(6, e.walkMinutes());
    }

    /** extractAll에서도 모든 후보가 도보뿐이면(대중교통 후보가 하나도 없으면), 가장 짧은 도보시간을 들고 예외를 던진다. */
    @Test
    void extractAll에서_모든_후보가_도보뿐이면_가장_짧은_도보시간으로_예외를_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 9, null, null, null, null, null)
                        )),
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 5, null, null, null, null, null)
                        ))
                ))
        );

        WalkOnlyRouteException e = assertThrows(WalkOnlyRouteException.class, () -> extractor.extractAll(response));
        assertEquals(5, e.walkMinutes());
    }

    /** 후보 중 하나라도 실제 대중교통 경로가 있으면, 도보 전용 후보는 조용히 버리고 그걸 쓴다. */
    @Test
    void 대중교통_후보가_하나라도_있으면_도보_전용_후보는_무시한다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 5, null, null, null, null, null)
                        )),
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 10, 300, 1, null, "테스트역",
                                        List.of(new OdsayPathResponse.Lane("1호선")))
                        ))
                ))
        );

        List<RouteLegExtractor.ExtractedRoute> candidates = extractor.extractAll(response);

        assertEquals(1, candidates.size());
        assertEquals(300, candidates.get(0).legs().get(0).stationId());
    }

    @Test
    void 모든_경로_후보에_버스가_섞여있으면_extractAll도_예외를_던진다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(3, List.of(
                                new OdsayPathResponse.SubPath(2, 10, null, null, null, null, null)
                        ))
                ))
        );

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extractAll(response));
    }
}
