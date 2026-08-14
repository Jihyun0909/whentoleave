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

    @Test
    void null이거나_빈_노드는_빈_리스트로_정규화된다() {
        assertTrue(NearestStopMatcher.asList(null).isEmpty());
    }

    @Test
    void row가_null이면_text는_null을_준다() {
        assertNull(NearestStopMatcher.text(null, ID_FIELDS));
    }
}
