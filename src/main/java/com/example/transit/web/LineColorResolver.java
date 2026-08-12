package com.example.transit.web;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 노선 이름 -> 공식 노선색(hex). 경로 화면에서 지하철 구간 칩을 실제 노선 색으로 보여주기 위함.
 * 목록에 없는 노선(신설 노선, ODsay 표기가 다른 경우 등)은 fallback 색으로 처리한다.
 */
@Component
public class LineColorResolver {

    private static final String FALLBACK_COLOR = "#4E5968";

    private static final Map<String, String> LINE_COLORS = Map.ofEntries(
            Map.entry("수도권1호선", "#0052A4"),
            Map.entry("수도권2호선", "#00A84D"),
            Map.entry("수도권3호선", "#EF7C1C"),
            Map.entry("수도권4호선", "#00A5DE"),
            Map.entry("수도권5호선", "#996CAC"),
            Map.entry("수도권6호선", "#CD7C2F"),
            Map.entry("수도권7호선", "#747F00"),
            Map.entry("수도권8호선", "#E6186C"),
            Map.entry("수도권9호선", "#BDB092"),
            Map.entry("신분당선", "#D4003B"),
            Map.entry("수인분당선", "#F5A200"),
            Map.entry("경의중앙선", "#77C4A3"),
            Map.entry("공항철도", "#0090D2"),
            Map.entry("경춘선", "#0C8E72"),
            Map.entry("서해선", "#8FC31F"),
            Map.entry("우이신설선", "#B0CE18"),
            Map.entry("신림선", "#6789CA"),
            Map.entry("인천1호선", "#7CA8D5"),
            Map.entry("인천2호선", "#ED8B00"),
            Map.entry("김포골드라인", "#A17800"),
            Map.entry("의정부경전철", "#FDA600"),
            Map.entry("에버라인", "#74B466")
    );

    /** ODsay 응답의 노선명 띄어쓰기가 일정하지 않아("수도권4호선" vs "수도권 4호선") 공백을 무시하고 매칭한다. */
    public String colorOf(String laneName) {
        if (laneName == null) {
            return FALLBACK_COLOR;
        }
        return LINE_COLORS.getOrDefault(laneName.replace(" ", ""), FALLBACK_COLOR);
    }
}
