package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldReverseGeocodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 좌표를 TAGO 자체 지역코드(cityCode)로 변환한다. TAGO 버스노선정보 API
 * (getRouteNoList/getRouteInfoIem/getRouteAcctoThrghSttnList)는 cityCode 없이는 검색 결과가
 * 항상 0건이라(2026-08-20 라이브 확인 - 에러가 아니라 조용히 빈 결과), 이 변환이 꼭 필요하다.
 * <p>
 * <b>절차:</b> VWorld 역지오코딩(좌표->주소, {@code type=parcel}이 도로명보다 안정적으로 응답을
 * 준다)으로 시/군/구 이름을 얻은 뒤, TAGO의 {@code getCtyCodeList} 오퍼레이션으로 미리 받아둔
 * 정적 매핑표({@link #CITY_CODES_BY_NAME})에서 찾는다. 이 매핑표를 API 호출로 매번 새로 받지
 * 않고 하드코딩한 이유는, TAGO 지역코드 체계 자체가 행정구역 개편이 없는 한 안 바뀌는 정적
 * 데이터이기 때문이다(2026-08-20 실제 응답 기준, 총 161개 지역).
 * <p>
 * <b>서울은 TAGO 커버리지 밖이라 항상 못 찾는다(의도된 동작).</b> TAGO가 서울 시내버스를 아예
 * 취급하지 않아서(도시코드 목록에 서울 자체가 없음), VWorld가 서울의 구 이름(예: "강남구")을
 * 반환해도 이 매핑표엔 없어서 빈 값을 준다 - 그러면 호출하는 쪽({@code GoogleRouteLegExtractor})은
 * 그 버스 구간을 "TAGO로 배차정보를 못 구하는 구간"으로 처리한다(NightBusRouteFinder와 같은
 * 방식 - 경로 자체는 보여주되 시각 추정은 비운다).
 */
@Service
public class TagoCityCodeResolver {

    private static final Logger log = LoggerFactory.getLogger(TagoCityCodeResolver.class);

    /** TAGO getCtyCodeList 응답(2026-08-20 라이브 확인) 그대로 하드코딩. 광역시/도 이름과 시/군/구 이름을 함께 키로 둔다. */
    private static final Map<String, String> CITY_CODES_BY_NAME = Map.ofEntries(
            Map.entry("세종특별자치시", "12"), Map.entry("부산광역시", "21"), Map.entry("대구광역시", "22"),
            Map.entry("인천광역시", "23"), Map.entry("광주광역시", "24"), Map.entry("대전광역시", "25"),
            Map.entry("계룡시", "34070"), Map.entry("울산광역시", "26"), Map.entry("제주특별자치도", "39"),
            Map.entry("수원시", "31010"), Map.entry("성남시", "31020"), Map.entry("의정부시", "31030"),
            Map.entry("안양시", "31040"), Map.entry("부천시", "31050"), Map.entry("광명시", "31060"),
            Map.entry("평택시", "31070"), Map.entry("동두천시", "31080"), Map.entry("안산시", "31090"),
            Map.entry("고양시", "31100"), Map.entry("과천시", "31110"), Map.entry("구리시", "31120"),
            Map.entry("남양주시", "31130"), Map.entry("오산시", "31140"), Map.entry("시흥시", "31150"),
            Map.entry("군포시", "31160"), Map.entry("의왕시", "31170"), Map.entry("하남시", "31180"),
            Map.entry("용인시", "31190"), Map.entry("파주시", "31200"), Map.entry("이천시", "31210"),
            Map.entry("안성시", "31220"), Map.entry("김포시", "31230"), Map.entry("화성시", "31240"),
            Map.entry("광주시", "31250"), Map.entry("양주시", "31260"), Map.entry("포천시", "31270"),
            Map.entry("여주시", "31320"), Map.entry("연천군", "31350"), Map.entry("가평군", "31370"),
            Map.entry("양평군", "31380"), Map.entry("춘천시", "32010"), Map.entry("원주시", "32020"),
            Map.entry("횡성군", "32020"), Map.entry("태백시", "32050"), Map.entry("홍천군", "32310"),
            Map.entry("철원군", "32360"), Map.entry("양양군", "32410"), Map.entry("청주시", "33010"),
            Map.entry("충주시", "33020"), Map.entry("제천시", "33030"), Map.entry("보은군", "33320"),
            Map.entry("옥천군", "33330"), Map.entry("영동군", "33340"), Map.entry("진천군", "33350"),
            Map.entry("괴산군", "33360"), Map.entry("음성군", "33370"), Map.entry("단양군", "33380"),
            Map.entry("천안시", "34010"), Map.entry("공주시", "34020"), Map.entry("보령시", "34030"),
            Map.entry("아산시", "34040"), Map.entry("서산시", "34050"), Map.entry("논산시", "34060"),
            Map.entry("금산군", "34310"), Map.entry("부여군", "34330"), Map.entry("서천군", "34340"),
            Map.entry("청양군", "34350"), Map.entry("태안군", "34380"), Map.entry("당진시", "34390"),
            Map.entry("전주시", "35010"), Map.entry("군산시", "35020"), Map.entry("익산시", "35030"),
            Map.entry("정읍시", "35040"), Map.entry("남원시", "35050"), Map.entry("김제시", "35060"),
            Map.entry("진안군", "35320"), Map.entry("무주군", "35330"), Map.entry("장수군", "35340"),
            Map.entry("임실군", "35350"), Map.entry("순창군", "35360"), Map.entry("고창군", "35370"),
            Map.entry("부안군", "35380"), Map.entry("목포시", "36010"), Map.entry("여수시", "36020"),
            Map.entry("순천시", "36030"), Map.entry("나주시", "36040"), Map.entry("광양시", "36060"),
            Map.entry("곡성군", "36320"), Map.entry("구례군", "36330"), Map.entry("고흥군", "36350"),
            Map.entry("장흥군", "36380"), Map.entry("해남군", "36400"), Map.entry("영암군", "36410"),
            Map.entry("무안군", "36420"), Map.entry("함평군", "36430"), Map.entry("장성군", "36450"),
            Map.entry("완도군", "36460"), Map.entry("진도군", "36470"), Map.entry("신안군", "36480"),
            Map.entry("포항시", "37010"), Map.entry("경주시", "37020"), Map.entry("김천시", "37030"),
            Map.entry("안동시", "37040"), Map.entry("구미시", "37050"), Map.entry("영주시", "37060"),
            Map.entry("영천시", "37070"), Map.entry("상주시", "37080"), Map.entry("문경시", "37090"),
            Map.entry("경산시", "37100"), Map.entry("의성군", "37320"), Map.entry("청송군", "37330"),
            Map.entry("영양군", "37340"), Map.entry("영덕군", "37350"), Map.entry("청도군", "37360"),
            Map.entry("고령군", "37370"), Map.entry("성주군", "37380"), Map.entry("칠곡군", "37390"),
            Map.entry("예천군", "37400"), Map.entry("봉화군", "37410"), Map.entry("울진군", "37420"),
            Map.entry("울릉군", "37430"), Map.entry("창원시", "38010"), Map.entry("진주시", "38030"),
            Map.entry("통영시", "38050"), Map.entry("사천시", "38060"), Map.entry("김해시", "38070"),
            Map.entry("밀양시", "38080"), Map.entry("거제시", "38090"), Map.entry("양산시", "38100"),
            Map.entry("의령군", "38310"), Map.entry("함안군", "38320"), Map.entry("창녕군", "38330"),
            Map.entry("고성군", "38340"), Map.entry("남해군", "38350"), Map.entry("하동군", "38360"),
            Map.entry("산청군", "38370"), Map.entry("함양군", "38380"), Map.entry("거창군", "38390"),
            Map.entry("합천군", "38400")
    );

    private final VWorldGeocoderClient client;

    public TagoCityCodeResolver(VWorldGeocoderClient client) {
        this.client = client;
    }

    /** @param x 경도, @param y 위도. 서울처럼 TAGO가 커버하지 않는 지역이면 빈 값. */
    public Optional<String> resolve(double x, double y) {
        try {
            VWorldReverseGeocodeResponse response = client.reverseGeocode(x, y);
            return extractCityCode(response);
        } catch (RuntimeException e) {
            log.debug("좌표->cityCode 변환 실패 x={} y={}", x, y, e);
            return Optional.empty();
        }
    }

    private Optional<String> extractCityCode(VWorldReverseGeocodeResponse response) {
        if (response == null || response.response() == null
                || !"OK".equals(response.response().status())
                || response.response().result() == null
                || response.response().result().isEmpty()) {
            return Optional.empty();
        }
        VWorldReverseGeocodeResponse.Structure structure = response.response().result().get(0).structure();
        if (structure == null) {
            return Optional.empty();
        }
        // level2(시/군/구)를 먼저 보고, 없으면 level1(광역시/도)로 - 광역시는 level2가 구 단위라
        // 매핑표에 없고, 그 경우 level1(예: "부산광역시") 자체가 코드에 대응된다. 수원시처럼
        // 구가 있는 시는 level2가 "수원시 팔달구"로 합쳐서 오므로(2026-08-20 라이브 확인),
        // 앞 단어(첫 공백 전까지)만 떼어서 한 번 더 시도한다.
        for (String candidate : List.of(structure.level2(), firstWord(structure.level2()), structure.level1())) {
            String code = candidate == null ? null : CITY_CODES_BY_NAME.get(candidate);
            if (code != null) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }

    private String firstWord(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int spaceIndex = text.indexOf(' ');
        return spaceIndex < 0 ? text : text.substring(0, spaceIndex);
    }
}
