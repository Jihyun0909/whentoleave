package com.example.transit.service;

import com.example.transit.service.client.SeoulSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실시간 지하철 도착정보 조회/변환 검증. 서울 열린데이터광장 응답의 정확한 필드명은 아직
 * 실제 키로 확인되지 않았으므로, SeoulSubwayArrivalResponse에 적어둔 문서 기준 필드명으로
 * 응답을 만들어 매핑 로직이 의도대로 동작하는지를 확인한다.
 */
class SeoulSubwayArrivalServiceTest {

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() {
        CountingClient client = new CountingClient("", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 역이름이_비어있으면_API를_부르지_않는다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals(" ");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 응답을_도착정보_목록으로_변환한다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());

        RealtimeSubwayArrivalLookup.SubwayArrival first = result.get(0);
        assertEquals("2호선", first.lineName());
        assertEquals("상행", first.direction());
        assertEquals("성수", first.destinationStationName());
        assertEquals("성수행 - 신설동방면", first.headsign());
        assertEquals("전역 도착", first.arrivalMessage());
        assertEquals(97, first.secondsUntilArrival());
        assertFalse(first.isLastTrain());

        RealtimeSubwayArrivalLookup.SubwayArrival second = result.get(1);
        assertTrue(second.isLastTrain());
    }

    @Test
    void 도착시간이_숫자가_아니면_null로_준다() {
        SeoulSubwayArrivalResponse response = new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1002", "상행", "성수행", "강남",
                        "전역 도착", "성수", "전역 도착", "신설동", "1", "0", "2026-08-14 10:00:00")));
        CountingClient client = new CountingClient("dummy-key", response);
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertNull(result.get(0).secondsUntilArrival());
    }

    /**
     * 인천/김포/용인 등 서울시 API가 커버하지 않는 노선의 subwayId나, 매핑표에 없는 코드가
     * 오면 lineName을 null로 준다 - 호출하는 쪽(컨트롤러)이 어느 노선인지 확신 못 하면
     * 걸러내야(제외) 엉뚱한 노선을 그 역의 도착정보로 잘못 보여주지 않는다.
     */
    @Test
    void 매핑표에_없는_subwayId면_lineName이_null이다() {
        SeoulSubwayArrivalResponse response = new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("9999", "상행", "알수없음행", "강남",
                        "97", "알수없음", "전역 도착", "신설동", "0", "0", "2026-08-14 10:00:00")));
        CountingClient client = new CountingClient("dummy-key", response);
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertNull(result.get(0).lineName());
    }

    /**
     * 실사용 중 발견한 버그의 회귀 테스트. "몇 번째 전역"이라고만 알려주고 실제 남은 시간은
     * 아직 계산되지 않은 열차는 barvlDt가 의미없는 "0"으로 온다("창동" 인천행 하행 중
     * "[6]번째 전역(의정부)"처럼 6개 역이나 남았는데도 barvlDt="0" - 도보로 못 따라잡는 차편을
     * 거르는 로직이 이걸 "지금 당장 도착"으로 잘못 알고 걸러내서, 탈 수 있는 열차가 있는데도
     * 실시간 정보 전체가 사라졌다). 이런 경우는 신뢰할 수 있는 초 단위 값이 없는 것으로 봐야 한다.
     * <p>
     * 처음엔 이걸 arvlCd "99"(운행중) 전체를 못 믿는 걸로 고쳤다가, arvlCd 99는 그냥 대부분의
     * 정상 운행 열차가 갖는 흔한 코드일 뿐이라는 걸 놓쳐서 지하철 실시간 정보가 통째로 사라지는
     * 회귀를 만들었다(아래 두 번째 테스트가 그 경우를 검증한다). 실제 구분 기준은 arvlCd가 아니라
     * 안내 메시지 형식("[N]번째 전역"인지 vs 실제 시간 문구인지)이다.
     */
    @Test
    void 번째_전역이라고만_나오면_barvlDt를_신뢰하지_않고_null로_준다() {
        SeoulSubwayArrivalResponse response = new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1001", "하행", "인천행 - 녹천방면", "창동",
                        "0", "인천", "[6]번째 전역 (의정부)", "의정부", "99", "0", "2026-08-27 13:16:28")));
        CountingClient client = new CountingClient("dummy-key", response);
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("창동");

        assertNull(result.get(0).secondsUntilArrival());
    }

    /**
     * arvlCd "99"(운행중)는 정상 운행 중인 대부분의 열차가 갖는 흔한 코드다 - 실제 시간 문구
     * ("4분 30초 후")와 함께 오면 barvlDt를 그대로 신뢰해야 한다(수유역 라이브 조회로 확인:
     * arvlCd 99에 barvlDt=270, 메시지 "4분 30초 후"가 정확히 일치).
     */
    @Test
    void arvlCd가_99여도_실제_시간_문구가_있으면_barvlDt를_신뢰한다() {
        SeoulSubwayArrivalResponse response = new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1004", "상행", "불암산행 - 쌍문방면", "수유",
                        "270", "불암산", "4분 30초 후 (미아사거리)", "미아사거리", "99", "0", "2026-08-27 13:20:00")));
        CountingClient client = new CountingClient("dummy-key", response);
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("수유");

        assertEquals(270, result.get(0).secondsUntilArrival());
    }

    /**
     * 실사용 검증 중 발견한 버그의 회귀 테스트. ODsay는 부역명을 괄호로 붙여 주는데
     * ("수유(강북구청)") 서울시 API는 순수 역명만 인식해서, 괄호째 넘기면 빈 응답이 와서
     * 실시간 정보가 조용히 사라졌다(실측: "수유(강북구청)" -> 데이터 없음, "수유" -> 정상).
     */
    @Test
    void 부역명_괄호를_떼고_조회한다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        service.findArrivals("수유(강북구청)");

        assertEquals("수유", client.lastStationName);
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        SeoulSubwayApiClient failing = new SeoulSubwayApiClient("http://dummy", "dummy-key") {
            @Override
            public SeoulSubwayArrivalResponse findArrivals(String stationName) {
                throw new IllegalStateException("API 실패");
            }
        };
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(failing);

        assertTrue(service.findArrivals("강남").isEmpty());
    }

    private SeoulSubwayArrivalResponse sampleResponse() {
        return new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1002", "상행", "성수행 - 신설동방면", "강남",
                        "97", "성수", "전역 도착", "신설동", "1", "0", "2026-08-14 10:00:00"),
                new SeoulSubwayArrivalResponse.Arrival("1002", "하행", "역삼행", "강남",
                        "180", "역삼", "3분 후 (당역 종료)", "역삼", "0", "1", "2026-08-14 10:00:00")));
    }

    private static class CountingClient extends SeoulSubwayApiClient {
        private final SeoulSubwayArrivalResponse response;
        private int calls;
        private String lastStationName;

        CountingClient(String apiKey, SeoulSubwayArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public SeoulSubwayArrivalResponse findArrivals(String stationName) {
            calls++;
            lastStationName = stationName;
            return response;
        }
    }
}
