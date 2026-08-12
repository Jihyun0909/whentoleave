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

    /**
     * 키는 전부 "수도권"/공백/마침표를 뗀 짧은 형태로 통일한다 (colorOf에서 입력도 똑같이
     * 정규화해서 비교함). ODsay 노선명 표기가 일정하지 않아서다 - 실측해보니
     * "수도권 4호선"(공백)처럼 나오기도 하고 "수도권 수인.분당선"(마침표까지)처럼 나오기도 함.
     */
    private static final Map<String, String> LINE_COLORS = Map.ofEntries(
            Map.entry("1호선", "#0D3692"),
            Map.entry("2호선", "#33A23D"),
            Map.entry("3호선", "#EF7C31"),
            Map.entry("4호선", "#4C9BD6"),
            Map.entry("5호선", "#77468C"),
            Map.entry("6호선", "#C5730F"),
            Map.entry("7호선", "#767C1B"),
            Map.entry("8호선", "#EA4893"),
            Map.entry("9호선", "#AD9C5C"),
            Map.entry("신분당선", "#C9252B"),
            Map.entry("공항철도", "#359DE0"),
            Map.entry("경의중앙선", "#6FBE97"),
            Map.entry("경춘선", "#0C8E72"),
            Map.entry("수인분당선", "#FBB715"),
            Map.entry("의정부경전철", "#F06A00"),
            Map.entry("인천1호선", "#6193C6"),
            Map.entry("인천2호선", "#5EC2E6"),
            Map.entry("김포골드라인", "#B4A16B"),
            Map.entry("우이신설선", "#B5D334"),
            Map.entry("서해선", "#8FC31F"),
            Map.entry("에버라인", "#4C9F70"),
            Map.entry("신림선", "#6789CA"),
            Map.entry("GTX-A", "#9A6292")
    );

    public String colorOf(String laneName) {
        if (laneName == null) {
            return FALLBACK_COLOR;
        }
        return LINE_COLORS.getOrDefault(normalize(laneName), FALLBACK_COLOR);
    }

    /** 화면 표시용 짧은 노선명. "수도권" 접두어와 마침표를 떼서 "수도권 4호선" -> "4호선"으로 보여준다. */
    public String shortNameOf(String laneName) {
        if (laneName == null) {
            return "";
        }
        return laneName.replace("수도권", "").replace(".", "").trim();
    }

    private String normalize(String laneName) {
        return laneName.replace("수도권", "").replace(" ", "").replace(".", "");
    }
}
