package com.example.transit.service.client;

import com.example.transit.service.client.dto.IncheonBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 인천 버스정보시스템(BIMS) 버스도착정보 호출.
 * <p>
 * <b>2026-08-14 라이브 테스트로 확인.</b> 오퍼레이션 경로는 {@code busArrivalService/getAllRouteBusArrivalList},
 * 필수 파라미터는 {@code bstopId} + {@code pageNo}/{@code numOfRows} + {@code resultType}(값과 무관하게
 * 파라미터 자체가 없으면 무조건 {@code HTTP_ERROR}가 남 - 인천 BIMS의 버그성 동작으로 보인다). 이름과
 * 달리 {@code resultType=json}을 넣어도 응답은 항상 XML이라, Jackson이 아니라 DOM으로 직접 파싱한다.
 * 상세 필드는 {@link IncheonBusArrivalResponse} 참고. data.go.kr 발급키는 Decoding(원본) 키를 넣어야 한다.
 */
@Component
public class IncheonBusApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public IncheonBusApiClient(@Value("${incheon-bus.base-url}") String baseUrl,
                                @Value("${incheon-bus.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param bstopId 인천 BIMS 자체 정류소 ID */
    public IncheonBusArrivalResponse findArrivals(String bstopId) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + encode(apiKey)
                + "&bstopId=" + encode(bstopId) + "&pageNo=1&numOfRows=20&resultType=json");
        String xml = restClient.get().uri(uri).retrieve().body(String.class);
        return parse(xml);
    }

    private IncheonBusArrivalResponse parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return new IncheonBusArrivalResponse(List.of());
        }
        Document document = parseXml(xml);
        NodeList itemNodes = document.getElementsByTagName("itemList");
        List<IncheonBusArrivalResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < itemNodes.getLength(); i++) {
            items.add(toItem((Element) itemNodes.item(i)));
        }
        return new IncheonBusArrivalResponse(items);
    }

    private IncheonBusArrivalResponse.Item toItem(Element item) {
        return new IncheonBusArrivalResponse.Item(
                text(item, "BSTOPID"),
                text(item, "ROUTEID"),
                text(item, "BUS_NUM_PLATE"),
                integer(item, "REST_STOP_COUNT"),
                integer(item, "ARRIVALESTIMATETIME"),
                "1".equals(text(item, "LASTBUSYN")));
    }

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("인천 버스 도착정보 XML 파싱 실패", e);
        }
    }

    private String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return null;
        }
        String value = nodes.item(0).getTextContent().trim();
        return value.isEmpty() ? null : value;
    }

    private Integer integer(Element item, String tag) {
        String value = text(item, tag);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
