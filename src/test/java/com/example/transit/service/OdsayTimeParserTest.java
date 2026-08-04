package com.example.transit.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OdsayTimeParserTest {

    @Test
    void 정상_시각은_그대로_파싱하고_다음날_플래그는_false다() {
        OdsayTimeParser.ParsedTime result = OdsayTimeParser.parse("23:47");

        assertEquals(LocalTime.of(23, 47), result.time());
        assertFalse(result.nextDay());
    }

    @Test
    void 시각이_24시를_초과하면_24를_빼고_다음날_플래그를_true로_반환한다() {
        OdsayTimeParser.ParsedTime result = OdsayTimeParser.parse("24:35");

        assertEquals(LocalTime.of(0, 35), result.time());
        assertTrue(result.nextDay());
    }

    @Test
    void 자정_직전_시각도_정상_파싱된다() {
        OdsayTimeParser.ParsedTime result = OdsayTimeParser.parse("00:05");

        assertEquals(LocalTime.of(0, 5), result.time());
        assertFalse(result.nextDay());
    }
}
