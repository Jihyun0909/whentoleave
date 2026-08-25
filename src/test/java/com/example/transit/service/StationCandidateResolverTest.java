package com.example.transit.service;

import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StationCandidateResolverTest {

    private static final String SUBWAY_CATEGORY = "철도시설 > 철도/지하철 > 지하철역";
    private static final String NON_SUBWAY_CATEGORY = "음식점 > 한식 > 육류,고기요리";

    private final StationCandidateResolver resolver = new StationCandidateResolver();

    /**
     * 실제로 "강남"을 VWorld 장소검색했을 때의 형태를 흉내낸 픽스처 (부분검색이라 강남/강남구청/강남대가
     * 섞여 나오고, 환승역이라 같은 이름의 지하철역 POI가 여러 개 온다 - 2026-08-20 라이브 확인).
     */
    private final List<VWorldSearchResponse.Item> gangnamSearchResult = List.of(
            item("강남역", SUBWAY_CATEGORY, 127.027619, 37.497952),
            item("강남역", SUBWAY_CATEGORY, 127.028358, 37.496373),
            item("강남구청역", SUBWAY_CATEGORY, 127.041289, 37.517188),
            item("강남구청역", SUBWAY_CATEGORY, 127.041404, 37.516848),
            item("강남대역", SUBWAY_CATEGORY, 127.126007, 37.270197)
    );

    @Test
    void 부분검색_결과에서_이름이_정확히_일치하는_것만_남긴다() {
        StationResolution result = resolver.resolve("강남", gangnamSearchResult);

        StationResolution.Ambiguous ambiguous = assertInstanceOf(StationResolution.Ambiguous.class, result);
        assertEquals(2, ambiguous.candidates().size()); // 강남구청역, 강남대역은 제외되어야 함
        assertEquals("강남역", ambiguous.candidates().get(0).stationName());
    }

    @Test
    void 환승역처럼_같은_이름의_POI가_여러_개면_Ambiguous를_반환한다() {
        StationResolution result = resolver.resolve("강남구청", gangnamSearchResult);

        StationResolution.Ambiguous ambiguous = assertInstanceOf(StationResolution.Ambiguous.class, result);
        assertEquals(2, ambiguous.candidates().size());
    }

    /**
     * 실사용 중 발견: VWorld가 같은 역을 좌표만 소수점 7자리류로 미세하게 다른 두 항목으로
     * 중복해서 준다("압구정로데오역", 2026-08-25 확인) - 진짜 서로 다른 위치(강남역 같은 여러
     * 출입구)까지 합치면 안 되므로 부동소수점 수준의 차이만 걸러야 한다.
     */
    @Test
    void 좌표만_소수점_수준으로_다른_중복_POI는_하나로_합쳐진다() {
        List<VWorldSearchResponse.Item> nearDuplicate = List.of(
                item("압구정로데오역", SUBWAY_CATEGORY, 127.04051289947552, 37.52729370032126),
                item("압구정로데오역", SUBWAY_CATEGORY, 127.04051288378069, 37.52729367592067)
        );

        StationResolution result = resolver.resolve("압구정로데오", nearDuplicate);

        assertInstanceOf(StationResolution.Resolved.class, result);
    }

    @Test
    void 정확히_하나만_일치하면_Resolved를_반환한다() {
        List<VWorldSearchResponse.Item> single = List.of(
                item("합정역", SUBWAY_CATEGORY, 126.913546, 37.549235)
        );

        StationResolution result = resolver.resolve("합정", single);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(126.913546, resolved.x());
        assertEquals(37.549235, resolved.y());
    }

    /**
     * VWorld는 지하철역과 무관한 장소도 이름이 비슷하면 같이 준다(일반 장소검색이라서,
     * 2026-08-20 라이브 확인 - "수유" 검색 시 기상관측소/출판사 등이 섞여 옴). category가
     * "지하철역"이 아닌 항목은 이름이 일치해도 후보에서 빠져야 한다.
     */
    @Test
    void 지하철역_카테고리가_아니면_이름이_일치해도_제외한다() {
        List<VWorldSearchResponse.Item> mixed = List.of(
                item("합정역", NON_SUBWAY_CATEGORY, 126.9, 37.5), // 이름은 같은데 지하철역 아님
                item("합정역", SUBWAY_CATEGORY, 126.913546, 37.549235)
        );

        StationResolution result = resolver.resolve("합정", mixed);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(126.913546, resolved.x());
    }

    /** "역"을 붙여 검색해도("수유역") VWorld가 이미 "역"이 붙은 이름("수유(강북구청)역")으로 찾아져야 한다. */
    @Test
    void 검색어_끝에_역을_붙여도_찾을_수_있다() {
        List<VWorldSearchResponse.Item> suyu = List.of(
                item("수유(강북구청)역", SUBWAY_CATEGORY, 127.025473, 37.637828)
        );

        StationResolution result = resolver.resolve("수유역", suyu);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(127.025473, resolved.x());
        assertEquals(37.637828, resolved.y());
    }

    /** "서울역"처럼 "역"이 실제 역명의 일부인 경우도 정규화 후 그대로 일치해야 한다. */
    @Test
    void 역이_이름의_일부인_경우는_그대로_찾는다() {
        List<VWorldSearchResponse.Item> seoulStation = List.of(
                item("서울역", SUBWAY_CATEGORY, 126.972559, 37.554648)
        );

        StationResolution result = resolver.resolve("서울역", seoulStation);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(126.972559, resolved.x());
        assertEquals(37.554648, resolved.y());
    }

    @Test
    void 일치하는_역이_없으면_NotFound를_반환한다() {
        StationResolution result = resolver.resolve("존재하지않는역이름", gangnamSearchResult);

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    /**
     * 실사용 중 실제로 발견된 버그(ODsay 시절): 괄호가 붙은 이름("수유(강북구청)역")을 괄호 없는
     * 검색어("수유")로도 찾을 수 있어야 한다. VWorld로 바뀐 뒤에도 "역" 접미사를 먼저 뗀 다음
     * 괄호를 떼는 순서가 맞아야 이 케이스가 계속 통과한다.
     */
    @Test
    void 역명에_괄호가_붙어있어도_괄호_없는_검색어로_찾을_수_있다() {
        List<VWorldSearchResponse.Item> suyu = List.of(
                item("수유(강북구청)역", SUBWAY_CATEGORY, 127.025473, 37.637828)
        );

        StationResolution result = resolver.resolve("수유", suyu);

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(127.025473, resolved.x());
        assertEquals(37.637828, resolved.y());
    }

    private VWorldSearchResponse.Item item(String title, String category, double x, double y) {
        return new VWorldSearchResponse.Item("id-" + title + "-" + x, title, category, null,
                new VWorldSearchResponse.Point(String.valueOf(x), String.valueOf(y)));
    }
}
