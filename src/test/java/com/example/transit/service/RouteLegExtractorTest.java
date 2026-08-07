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
     */
    @Test
    void 실제_ODsay_응답_형태에서_지하철_구간과_환승_버퍼를_추출한다() {
        OdsayPathResponse response = new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(3, 1, null, null, null),
                                new OdsayPathResponse.SubPath(1, 17, 414, 2, null),
                                new OdsayPathResponse.SubPath(3, 3, null, null, null),
                                new OdsayPathResponse.SubPath(1, 5, 205, 2, null),
                                new OdsayPathResponse.SubPath(3, 1, null, null, null)
                        ))
                ))
        );

        List<SubwayLeg> legs = extractor.extract(response);

        assertEquals(2, legs.size());
        assertEquals(new SubwayLeg(414, 2, 17, 1, Set.of()), legs.get(0)); // 출발 전 도보 1분이 버퍼로 잡힘(계산에선 안 쓰임)
        assertEquals(new SubwayLeg(205, 2, 5, 3, Set.of()), legs.get(1));  // 환승 도보 3분이 버퍼로 들어감
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
                                        )))
                        ))
                ))
        );

        List<SubwayLeg> legs = extractor.extract(response);

        Set<String> earlierStops = legs.get(0).earlierStopNames();
        assertTrue(earlierStops.contains("약수")); // 압구정보다 앞이라 단축운행 후보에서 제외 대상
        assertTrue(earlierStops.contains("충무로"));
        assertEquals(false, earlierStops.contains("압구정")); // 도착역 자체는 제외 목록에 없어야 함
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
                                new OdsayPathResponse.SubPath(2, 10, null, null, null)
                        ))
                ))
        );

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }
}
