package com.example.transit.service.client;

import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldReverseGeocodeResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** VWorld(국토교통부) 지오코더 API를 호출한다. 인증키는 쿼리 파라미터로 받는다. */
@Component
public class VWorldGeocoderClient {

    private static final String GEOCODE_URL = "https://api.vworld.kr/req/address";
    private static final String SEARCH_URL = "https://api.vworld.kr/req/search";

    private final RestClient restClient;
    private final String apiKey;

    public VWorldGeocoderClient(@Value("${vworld.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.apiKey = apiKey;
    }

    /**
     * @param type "road"(도로명) 또는 "parcel"(지번). 어느 형태의 주소인지 미리 알 수 없으므로
     *             호출하는 쪽(AddressSearchService)에서 하나 실패하면 다른 타입으로 재시도한다.
     */
    public VWorldGeocoderResponse geocode(String address, String type) {
        URI uri = URI.create(GEOCODE_URL
                + "?service=address&request=GetCoord&version=2.0&crs=epsg:4326"
                + "&type=" + type
                + "&address=" + encode(address)
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldGeocoderResponse.class);
    }

    /**
     * 주소 검색. 지오코더와 달리 완전한 주소가 아니어도 찾아준다("번동 463-68"처럼
     * 시/도/구가 빠진 입력). 여러 건이 나올 수 있어 호출하는 쪽에서 첫 번째를 쓴다.
     *
     * @param category "PARCEL"(지번) 또는 "ROAD"(도로명)
     */
    public VWorldSearchResponse searchAddress(String query, String category) {
        URI uri = URI.create(SEARCH_URL
                + "?service=search&request=search&version=2.0&crs=EPSG:4326&format=json&errorformat=json"
                + "&size=5&type=ADDRESS"
                + "&category=" + category
                + "&query=" + encode(query)
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldSearchResponse.class);
    }

    /**
     * 좌표 -> 주소(역지오코딩). type=parcel(지번)이 road(도로명)보다 안정적으로 응답을 준다
     * (2026-08-20 라이브 테스트: 도로에서 살짝만 벗어나도 road는 NOT_FOUND가 나옴).
     * TAGO 버스 API에 필요한 cityCode를 얻기 위해 시/군/구 이름을 알아내는 용도로 쓴다
     * ({@code TagoCityCodeResolver} 참고).
     *
     * @param x 경도, @param y 위도.
     */
    public VWorldReverseGeocodeResponse reverseGeocode(double x, double y) {
        URI uri = URI.create(GEOCODE_URL
                + "?service=address&request=getAddress&version=2.0&crs=epsg:4326"
                + "&type=parcel"
                + "&point=" + x + "," + y
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldReverseGeocodeResponse.class);
    }

    /**
     * 장소(POI) 검색. 은행 지점·아파트 단지·학교처럼 주소가 아니라 상호/건물명으로 입력해도
     * 찾아준다("신한은행 본점", "수원SK스카이뷰아파트" 등). 주소 검색과 응답 스키마가 같아서
     * 같은 DTO({@link VWorldSearchResponse})를 그대로 쓴다. type=ADDRESS와 달리 category
     * 파라미터가 없다(라이브 테스트로 확인 - 넣으면 오히려 결과가 안 나옴).
     */
    public VWorldSearchResponse searchPlace(String query) {
        return searchPlace(query, 5);
    }

    /**
     * @param size 결과 개수. 역 이름 검색(StationSuggestionService)처럼 원하는 카테고리(지하철역)로
     *             다시 걸러내야 하는 경우, VWorld 검색 자체는 이름이 비슷한 아무 장소나 다 주기 때문에
     *             (예: "수유" 검색 시 기상관측소/출판사 등이 섞여 나옴 - 2026-08-20 라이브 확인)
     *             5건보다 넉넉하게 받아야 그 안에서 지하철역이 걸릴 확률이 올라간다.
     */
    public VWorldSearchResponse searchPlace(String query, int size) {
        URI uri = URI.create(SEARCH_URL
                + "?service=search&request=search&version=2.0&crs=EPSG:4326&format=json&errorformat=json"
                + "&size=" + size + "&type=PLACE"
                + "&query=" + encode(query)
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldSearchResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
