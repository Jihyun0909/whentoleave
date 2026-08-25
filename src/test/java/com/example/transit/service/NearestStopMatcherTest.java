package com.example.transit.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearestStopMatcherTest {

    private static final List<String> ID_FIELDS = List.of("nodeid");
    private static final List<String> LON_FIELDS = List.of("gpslong");
    private static final List<String> LAT_FIELDS = List.of("gpslati");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 여러_후보_중_가장_가까운_정류소_행을_고른다() throws Exception {
        JsonNode rows = mapper.readValue("""
                [
                  { "nodeid": "GGB123", "nodenm": "가까운정류장", "gpslong": "127.0277", "gpslati": "37.4980" },
                  { "nodeid": "GGB999", "nodenm": "먼정류장", "gpslong": "127.2000", "gpslati": "37.7000" }
                ]
                """, JsonNode.class);

        Optional<NearestStopMatcher.Match> result = NearestStopMatcher.findNearest(
                NearestStopMatcher.asList(rows), 127.0276, 37.4979, LON_FIELDS, LAT_FIELDS);

        assertTrue(result.isPresent());
        assertEquals("GGB123", NearestStopMatcher.text(result.get().row(), ID_FIELDS));
    }

    @Test
    void 단일_객체도_리스트로_정규화된다() throws Exception {
        JsonNode single = mapper.readValue("""
                { "nodeid": "GGB123", "nodenm": "정류장", "gpslong": "127.0277", "gpslati": "37.4980" }
                """, JsonNode.class);

        List<JsonNode> rows = NearestStopMatcher.asList(single);

        assertEquals(1, rows.size());
    }

    @Test
    void 좌표_필드를_못_찾으면_빈_값을_준다() throws Exception {
        JsonNode rows = mapper.readValue("""
                [ { "unknownField": "x" } ]
                """, JsonNode.class);

        Optional<NearestStopMatcher.Match> result = NearestStopMatcher.findNearest(
                NearestStopMatcher.asList(rows), 127.0276, 37.4979, LON_FIELDS, LAT_FIELDS);

        assertTrue(result.isEmpty());
    }

    /**
     * 실사용 검증 중 발견한 버그의 회귀 테스트. 지역별 API는 관할 밖 좌표로 물어도 자기
     * 관할에서 제일 가까운 정류소를 돌려주는데, 거리 제한이 없어서 수 km 떨어진 엉뚱한
     * 정류소가 매칭됐다 - 서울 정류장(TAGO 미커버) 조회 시 경기 API가 한참 떨어진 광역버스
     * 정류소를 물어와 전혀 다른 노선들의 도착정보가 뜨던 원인이다.
     */
    @Test
    void 너무_멀리_떨어진_정류소만_있으면_못_찾은_것으로_본다() throws Exception {
        JsonNode rows = mapper.readValue("""
                [ { "nodeid": "GGB999", "nodenm": "수km떨어진정류장", "gpslong": "127.2000", "gpslati": "37.7000" } ]
                """, JsonNode.class);

        Optional<NearestStopMatcher.Match> result = NearestStopMatcher.findNearest(
                NearestStopMatcher.asList(rows), 127.0276, 37.4979, LON_FIELDS, LAT_FIELDS);

        assertTrue(result.isEmpty());
    }

    /** 같은 정류장의 반대편 승강장 정도(수십 m)는 여전히 같은 정류장으로 인정해야 한다. */
    @Test
    void 수십미터_거리는_같은_정류장으로_인정한다() throws Exception {
        JsonNode rows = mapper.readValue("""
                [ { "nodeid": "GGB123", "nodenm": "건너편승강장", "gpslong": "127.02805", "gpslati": "37.49795" } ]
                """, JsonNode.class);

        Optional<NearestStopMatcher.Match> result = NearestStopMatcher.findNearest(
                NearestStopMatcher.asList(rows), 127.0276, 37.4979, LON_FIELDS, LAT_FIELDS);

        assertTrue(result.isPresent());
    }

    @Test
    void null이거나_빈_노드는_빈_리스트로_정규화된다() {
        assertTrue(NearestStopMatcher.asList(null).isEmpty());
    }

    @Test
    void row가_null이면_text는_null을_준다() {
        assertNull(NearestStopMatcher.text(null, ID_FIELDS));
    }
}
