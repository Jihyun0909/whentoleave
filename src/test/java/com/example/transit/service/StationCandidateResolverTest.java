package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StationCandidateResolverTest {

    private final StationCandidateResolver resolver = new StationCandidateResolver();

    /** 실제로 "강남"을 검색했을 때 캡처한 ODsay 응답 기반 (부분검색이라 강남/강남구청/강남대가 섞여 나옴). */
    private final List<OdsayStationSearchResponse.Station> gangnamSearchResult = List.of(
            new OdsayStationSearchResponse.Station("강남", 222, 127.027619, 37.497952, "수도권 2호선"),
            new OdsayStationSearchResponse.Station("강남", 1910, 127.028358, 37.496373, "수도권 신분당선"),
            new OdsayStationSearchResponse.Station("강남구청", 730, 127.041289, 37.517188, "수도권 7호선"),
            new OdsayStationSearchResponse.Station("강남구청", 1513, 127.041404, 37.516848, "수도권 수인.분당선"),
            new OdsayStationSearchResponse.Station("강남대", 1702, 127.126007, 37.270197, "수도권 에버라인")
    );

    @Test
    void 부분검색_결과에서_이름이_정확히_일치하는_것만_남긴다() {
        StationResolution result = resolver.resolve("강남", gangnamSearchResult);

        StationResolution.Ambiguous ambiguous = assertInstanceOf(StationResolution.Ambiguous.class, result);
        assertEquals(2, ambiguous.candidates().size()); // 강남구청, 강남대는 제외되어야 함
        assertEquals("강남", ambiguous.candidates().get(0).stationName());
    }

    @Test
    void 환승역처럼_같은_이름이_여러_노선에_있으면_Ambiguous를_반환한다() {
        StationResolution result = resolver.resolve("강남구청", gangnamSearchResult);

        StationResolution.Ambiguous ambiguous = assertInstanceOf(StationResolution.Ambiguous.class, result);
        assertEquals(2, ambiguous.candidates().size());
    }

    @Test
    void 정확히_하나만_일치하면_Resolved를_반환한다() {
        List<OdsayStationSearchResponse.Station> single = List.of(
                new OdsayStationSearchResponse.Station("합정", 622, 126.913546, 37.549235, "수도권 6호선")
        );

        StationResolution result = resolver.resolve("합정", single);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(126.913546, resolved.x());
        assertEquals(37.549235, resolved.y());
    }

    @Test
    void 일치하는_역이_없으면_NotFound를_반환한다() {
        StationResolution result = resolver.resolve("존재하지않는역이름", gangnamSearchResult);

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    /**
     * 실사용 중 실제로 발견된 버그: ODsay에는 "수유(강북구청)"으로 저장돼 있어서,
     * 괄호 없이 "수유"로 검색하면 예전 로직(완전 일치)으로는 못 찾았다.
     */
    @Test
    void 역명에_괄호가_붙어있어도_괄호_없는_검색어로_찾을_수_있다() {
        List<OdsayStationSearchResponse.Station> suyu = List.of(
                new OdsayStationSearchResponse.Station("수유(강북구청)", 414, 127.025473, 37.637828, "수도권 4호선")
        );

        StationResolution result = resolver.resolve("수유", suyu);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(127.025473, resolved.x());
        assertEquals(37.637828, resolved.y());
    }
}
