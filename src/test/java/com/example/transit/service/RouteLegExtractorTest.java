package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayPathResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                                new OdsayPathResponse.SubPath(3, 1, null, null),
                                new OdsayPathResponse.SubPath(1, 17, 414, 2),
                                new OdsayPathResponse.SubPath(3, 3, null, null),
                                new OdsayPathResponse.SubPath(1, 5, 205, 2),
                                new OdsayPathResponse.SubPath(3, 1, null, null)
                        ))
                ))
        );

        List<SubwayLeg> legs = extractor.extract(response);

        assertEquals(2, legs.size());
        assertEquals(new SubwayLeg(414, 2, 17, 1), legs.get(0)); // 출발 전 도보 1분이 버퍼로 잡힘(계산에선 안 쓰임)
        assertEquals(new SubwayLeg(205, 2, 5, 3), legs.get(1));  // 환승 도보 3분이 버퍼로 들어감
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
                                new OdsayPathResponse.SubPath(2, 10, null, null)
                        ))
                ))
        );

        assertThrows(NoSubwayRouteFoundException.class, () -> extractor.extract(response));
    }
}
