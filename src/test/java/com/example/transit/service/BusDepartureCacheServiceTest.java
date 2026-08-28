package com.example.transit.service;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import com.example.transit.repository.BusStopDepartureRepository;
import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 버스 막차 "추정" 로직 검증. 실제 TAGO 응답에서 확인한 필드명을 그대로 쓴다
 * (getRouteInfoIem: startvehicletime/endvehicletime/intervaltime류, getRouteAcctoThrghSttnList:
 * nodeord/nodeid/gpslati/gpslong).
 */
class BusDepartureCacheServiceTest {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final String ROUTE_ID = "R1500";
    private static final String BOARDING_NODE_ID = "N80821";
    private static final String CITY_CODE = "31010";
    /** 요일유형을 고정해야 배차간격 선택이 실행 요일에 따라 흔들리지 않는다 (2026-08-13은 목요일). */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 기점부터_승차정류장까지_걸리는_시간만큼_막차를_늦춰서_추정한다() throws Exception {
        // 구간: 2000m를 10분에 감 -> 200m/분. 승차 정류장은 기점에서 4000m -> 20분 걸림.
        // 기점 막차 23:00 -> 승차 정류장 막차는 23:20으로 추정되어야 한다.
        BusDepartureCacheService service = service(routeDetailJson("0500", "2300", 10, 10, 10),
                routeStopsJson(4000));
        TransitLeg leg = busLeg(2000, 10);

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(23 * 60 + 20, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * 왕복 노선은 정류장 목록에 상·하행이 다 들어있어 복귀 구간 정류장의 누적거리가 수십 km까지
     * 나온다. 그대로 환산하면 "막차가 아침에 지나간다"는 값이 나와서(N61에서 실제 발생) 상한을 둔다.
     */
    @Test
    void 누적거리가_과하게_크면_오프셋을_상한으로_자른다() throws Exception {
        // 60km를 200m/분으로 환산하면 300분이지만, 상한 60분으로 잘려야 한다.
        BusDepartureCacheService service = service(routeDetailJson("0500", "2300", 10, 10, 10),
                routeStopsJson(60000));
        TransitLeg leg = busLeg(2000, 10);

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(23 * 60 + 60, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void 막차부터_배차간격만큼_거슬러_올라가며_차편_후보를_만든다() throws Exception {
        BusDepartureCacheService service = service(routeDetailJson("2200", "2300", 20, 20, 20), routeStopsJson(0));
        TransitLeg leg = busLeg(0, 0); // 거리 정보 없음 -> 기점 시각 그대로

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        // 23:00, 22:40, 22:20, 22:00
        assertEquals(List.of(23 * 60, 22 * 60 + 40, 22 * 60 + 20, 22 * 60), minutes);
    }

    @Test
    void 한_구간에_노선이_여러개면_모든_노선의_차편을_합쳐서_가장_늦은_막차까지_고려한다() throws Exception {
        Map<String, TagoBusArrivalResponse> details = new HashMap<>();
        details.put("R1", routeDetailJson("0500", "2230", 30, 30, 30));
        details.put("R2", routeDetailJson("0500", "2340", 30, 30, 30)); // 이 노선이 더 늦게까지 다님
        Map<String, TagoBusArrivalResponse> stops = new HashMap<>();
        stops.put("R1", routeStopsJson(0));
        stops.put("R2", routeStopsJson(0));
        BusDepartureCacheService service = service(details, stops);

        TransitLeg leg = TransitLeg.bus(BOARDING_NODE_ID, 0, 0, "정류장", "하차정류장", "간선",
                List.of("R1", "R2"), "146", 0, null, null, CITY_CODE, null);
        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(23 * 60 + 40, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * 심야버스류 회귀 테스트. 첫차 "0000"(자정) / 막차 "0330"(익일 03:30)처럼 이른 시각은
     * TagoTimeParser가 "다음날"로 해석해야 한다 - 안 그러면 막차부터 배차간격으로 거슬러
     * 내려가는 루프가 낮 시간대 차편까지 만들어낸다 (오후 4시에 N버스를 탈 수 있다고 안내하던 버그).
     * 후보는 전부 24:00 이후(=자정 이후)여야 한다.
     */
    @Test
    void 자정_이후에만_다니는_노선은_낮_시간대_차편을_만들지_않는다() throws Exception {
        BusDepartureCacheService service = service(routeDetailJson("0000", "0330", 35, 35, 35), routeStopsJson(0));

        List<Integer> minutes = service.departureServiceMinutes(busLeg(0, 0), TODAY);

        assertFalse(minutes.isEmpty());
        assertEquals(24 * 60, minutes.stream().mapToInt(Integer::intValue).min().orElseThrow());
        assertEquals(27 * 60 + 30, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void 배차간격이_숫자가_아니면_기본값으로_대체한다() throws Exception {
        // TAGO도 이따금 "1회 -"처럼 숫자가 아닌 배차간격을 준다(ODsay와 동일한 quirk).
        TagoBusArrivalResponse detail = mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                  "body": { "items": { "item":
                    { "routeno": "120", "startvehicletime": "2200", "endvehicletime": "2300",
                      "intervaltime": "1회 -", "intervalsattime": "1회 -", "intervalsuntime": "1회 -" }
                  } } } }
                """, TagoBusArrivalResponse.class);
        BusDepartureCacheService service = service(detail, routeStopsJson(0));
        TransitLeg leg = busLeg(0, 0);

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertFalse(minutes.isEmpty());
        assertEquals(23 * 60, minutes.get(0));
    }

    @Test
    void 노선_상세_조회가_실패하면_그_노선은_후보에서_빠진다() {
        TagoBusRouteDetailApiClient failing = new TagoBusRouteDetailApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchRouteDetail(String routeId, String cityCode) {
                throw new IllegalStateException("API 실패");
            }
        };
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(), failing, new SeoulBusRouteScheduleCatalog());

        assertTrue(service.departureServiceMinutes(busLeg(0, 0), TODAY).isEmpty());
    }

    @Test
    void 같은_노선_정류장은_두번째부터_캐시에서_읽어_API를_다시_호출하지_않는다() throws Exception {
        CountingClient client = new CountingClient(routeDetailJson("0500", "2300", 10, 10, 10), routeStopsJson(0));
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(), client, new SeoulBusRouteScheduleCatalog());

        service.departureServiceMinutes(busLeg(0, 0), TODAY);
        service.departureServiceMinutes(busLeg(0, 0), TODAY);

        assertEquals(1, client.detailCalls);
    }

    /**
     * 캐시 미스 경합 회귀 테스트. 동시 요청이 같은 키(bus_id, station_id, day_type)로 먼저
     * 커밋해버리면 우리의 저장은 유니크 제약 위반(DataIntegrityViolationException)을 받는데,
     * 이때 그 예외로 요청을 실패시키는 대신 이긴 쪽이 이미 저장한 행을 재조회해 써야 한다.
     * 이긴 쪽의 막차(22:50)가 우리가 계산했을 값(23:00)과 다르게 반환되는 것으로
     * "재조회한 행을 실제로 썼는지" 검증한다.
     */
    @Test
    void 캐시_미스_경합에서_저장이_유니크_제약에_걸리면_먼저_커밋된_행으로_복구한다() throws Exception {
        BusStopDeparture winner = new BusStopDeparture(
                ROUTE_ID, BOARDING_NODE_ID, DayType.from(TODAY),
                LocalTime.of(5, 0), false, LocalTime.of(22, 50), false, 10, "120");
        RaceLosingRepository repository = new RaceLosingRepository(winner);
        TagoBusArrivalResponse detail = routeDetailJson("0500", "2300", 10, 10, 10);
        TagoBusArrivalResponse stops = routeStopsJson(0);
        TagoBusRouteDetailApiClient client = new TagoBusRouteDetailApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchRouteDetail(String routeId, String cityCode) {
                return detail;
            }

            @Override
            public TagoBusArrivalResponse fetchRouteStops(String routeId, String cityCode) {
                return stops;
            }
        };
        BusDepartureCacheService service =
                new BusDepartureCacheService(repository, client, new SeoulBusRouteScheduleCatalog());

        List<Integer> minutes = service.departureServiceMinutes(busLeg(0, 0), TODAY);

        assertFalse(minutes.isEmpty());
        assertEquals(22 * 60 + 50, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * TAGO가 서울 버스를 못 찾으면(busIds가 빔) RouteLegExtractor가 cityCode도 null로 남기고
     * busNo만 채워서 넘긴다 - 이 경우 SeoulBusRouteScheduleCatalog 정적 시드로 대체돼야 한다.
     * "146"은 실제 시드 파일 기준 04:05~23:00, 배차간격 12분이다.
     */
    @Test
    void TAGO에서_못_찾은_서울_버스는_정적_시드로_대체한다() {
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(),
                        new TagoBusRouteDetailApiClient("http://dummy", "dummy"),
                        new SeoulBusRouteScheduleCatalog());
        TransitLeg leg = TransitLeg.bus("COORD:127.0,37.0", 10, 0, "정류장", "하차정류장", "146",
                List.of(), "146", 0, null, null, null, null);

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertFalse(minutes.isEmpty());
        assertEquals(23 * 60, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * 사용자 피드백(2026-08-30): 번동->압구정로데오 검색에서 구글맵은 23:37 출발도 가능하다는데
     * 이 앱은 22:24까지밖에 못 갔다. 원인 확인: TOPIS 시드의 "막차"는 노선 기점 기준이라, 기점에서
     * 승차 정류장(수유3동우체국)까지 오는 시간을 못 빼서 실제보다 훨씬 이르게(23:00) 나온다 -
     * Google이 이 구간에 준 자체 예정 출발 시각(23:30, RFC3339 "...T14:30:00Z")을 후보로 추가하면
     * 그 시각까지 후보에 잡혀야 한다.
     */
    @Test
    void google이_준_예정_출발_시각을_기점_막차_시각보다_늦은_후보로_추가한다() {
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(),
                        new TagoBusRouteDetailApiClient("http://dummy", "dummy"),
                        new SeoulBusRouteScheduleCatalog());
        // "146" 시드 막차는 23:00(1380분) - google이 그보다 늦은 23:30(1410분, UTC 14:30 = KST 23:30)을 줌.
        TransitLeg leg = TransitLeg.bus("COORD:127.0,37.0", 10, 0, "정류장", "하차정류장", "146",
                List.of(), "146", 0, null, null, null, "2026-08-13T14:30:00Z");

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(23 * 60 + 30, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /** 자정을 넘는 google 예정 시각(다음날 새벽)은 1440을 더한 값으로 들어와야 한다. */
    @Test
    void google_예정_출발_시각이_자정을_넘으면_1440을_더한다() {
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(),
                        new TagoBusRouteDetailApiClient("http://dummy", "dummy"),
                        new SeoulBusRouteScheduleCatalog());
        // UTC 16:05 = KST 2026-08-14 01:05 (TODAY 다음날 새벽) -> 65 + 1440 = 1505.
        TransitLeg leg = TransitLeg.bus("COORD:127.0,37.0", 10, 0, "정류장", "하차정류장", "146",
                List.of(), "146", 0, null, null, null, "2026-08-13T16:05:00Z");

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(24 * 60 + 65, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /** googleDepartureTime이 없거나(null) 깨진 형식이면 기존 후보만으로 조용히 폴백한다. */
    @Test
    void google_예정_출발_시각이_깨진_형식이면_그_후보만_무시한다() {
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(),
                        new TagoBusRouteDetailApiClient("http://dummy", "dummy"),
                        new SeoulBusRouteScheduleCatalog());
        TransitLeg leg = TransitLeg.bus("COORD:127.0,37.0", 10, 0, "정류장", "하차정류장", "146",
                List.of(), "146", 0, null, null, null, "이거-rfc3339-아님");

        List<Integer> minutes = service.departureServiceMinutes(leg, TODAY);

        assertEquals(23 * 60, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void 서울_시드에도_없는_노선번호는_빈_목록을_반환한다() {
        BusDepartureCacheService service =
                new BusDepartureCacheService(new InMemoryRepository(),
                        new TagoBusRouteDetailApiClient("http://dummy", "dummy"),
                        new SeoulBusRouteScheduleCatalog());
        TransitLeg leg = TransitLeg.bus("COORD:127.0,37.0", 10, 0, "정류장", "하차정류장", "존재안함",
                List.of(), "존재안함", 0, null, null, null, null);

        assertTrue(service.departureServiceMinutes(leg, TODAY).isEmpty());
    }

    private TransitLeg busLeg(int distanceMeters, int rideMinutes) {
        return TransitLeg.bus(BOARDING_NODE_ID, rideMinutes, 0, "정류장", "하차정류장", "간선",
                List.of(ROUTE_ID), "120", distanceMeters, null, null, CITY_CODE, null);
    }

    /** getRouteInfoIem 응답. 시간은 "HHMM" 4자리, 배차간격은 분 단위 정수. */
    private TagoBusArrivalResponse routeDetailJson(String first, String last,
                                                    int intervalWeek, int intervalSat, int intervalSun)
            throws Exception {
        String json = """
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                  "body": { "items": { "item":
                    { "routeno": "120", "startvehicletime": "%s", "endvehicletime": "%s",
                      "intervaltime": %d, "intervalsattime": %d, "intervalsuntime": %d }
                  } } } }
                """.formatted(first, last, intervalWeek, intervalSat, intervalSun);
        return mapper.readValue(json, TagoBusArrivalResponse.class);
    }

    /**
     * getRouteAcctoThrghSttnList 응답. 기점(nodeord=1, 좌표 37.0/127.0)에서 정확히
     * cumulativeMeters만큼 북쪽으로 떨어진 지점에 승차 정류장(nodeord=2, {@link #BOARDING_NODE_ID})을 둔다
     * (경도가 같으면 Haversine 거리가 위도차×지구반지름과 정확히 일치한다).
     */
    private TagoBusArrivalResponse routeStopsJson(int cumulativeMeters) throws Exception {
        double deltaLatDegrees = Math.toDegrees(cumulativeMeters / EARTH_RADIUS_METERS);
        String boardingLat = String.format(Locale.ROOT, "%.8f", 37.0 + deltaLatDegrees);
        String json = """
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                  "body": { "items": { "item": [
                    {"nodeord":1,"nodeid":"ORIGIN","gpslati":37.0,"gpslong":127.0},
                    {"nodeord":2,"nodeid":"%s","gpslati":%s,"gpslong":127.0}
                  ] } } } }
                """.formatted(BOARDING_NODE_ID, boardingLat);
        return mapper.readValue(json, TagoBusArrivalResponse.class);
    }

    private BusDepartureCacheService service(TagoBusArrivalResponse detail, TagoBusArrivalResponse stops) {
        return service(Map.of(ROUTE_ID, detail), Map.of(ROUTE_ID, stops));
    }

    private BusDepartureCacheService service(Map<String, TagoBusArrivalResponse> details,
                                              Map<String, TagoBusArrivalResponse> stops) {
        TagoBusRouteDetailApiClient client = new TagoBusRouteDetailApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchRouteDetail(String routeId, String cityCode) {
                return details.get(routeId);
            }

            @Override
            public TagoBusArrivalResponse fetchRouteStops(String routeId, String cityCode) {
                return stops.get(routeId);
            }
        };
        return new BusDepartureCacheService(new InMemoryRepository(), client, new SeoulBusRouteScheduleCatalog());
    }

    private static class CountingClient extends TagoBusRouteDetailApiClient {
        private final TagoBusArrivalResponse detail;
        private final TagoBusArrivalResponse stops;
        private int detailCalls;

        CountingClient(TagoBusArrivalResponse detail, TagoBusArrivalResponse stops) {
            super("http://dummy", "dummy");
            this.detail = detail;
            this.stops = stops;
        }

        @Override
        public TagoBusArrivalResponse fetchRouteDetail(String routeId, String cityCode) {
            detailCalls++;
            return detail;
        }

        @Override
        public TagoBusArrivalResponse fetchRouteStops(String routeId, String cityCode) {
            return stops;
        }
    }

    /**
     * 첫 저장 시도는 "동시 요청이 먼저 커밋한 행"만 실제로 넣고 유니크 제약 위반을 흉내내
     * DataIntegrityViolationException을 던진다. 이후 저장은 정상 동작한다(다른 키 재사용 대비).
     */
    private static class RaceLosingRepository extends InMemoryRepository {
        private final BusStopDeparture winner;
        private boolean firstAttemptDone = false;

        RaceLosingRepository(BusStopDeparture winner) {
            this.winner = winner;
        }

        @Override
        public <S extends BusStopDeparture> S save(S entity) {
            if (!firstAttemptDone) {
                firstAttemptDone = true;
                super.save(winner);
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
            }
            return super.save(entity);
        }
    }

    /** 저장/조회만 쓰는 아주 작은 페이크 (Mockito 없이 가는 이 프로젝트 방식). */
    private static class InMemoryRepository implements BusStopDepartureRepository {
        private final List<BusStopDeparture> saved = new ArrayList<>();

        @Override
        public Optional<BusStopDeparture> findFirstByBusIdAndStationIdAndDayTypeOrderByIdDesc(
                String busId, String stationId, DayType dayType) {
            return saved.stream()
                    .filter(d -> d.getBusId().equals(busId) && d.getStationId().equals(stationId)
                            && d.getDayType() == dayType)
                    .findFirst();
        }

        @Override
        public <S extends BusStopDeparture> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        // --- 아래는 이 테스트에서 쓰지 않는 JpaRepository 메서드들 ---
        @Override public void flush() { }
        @Override public <S extends BusStopDeparture> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends BusStopDeparture> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<BusStopDeparture> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public BusStopDeparture getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public BusStopDeparture getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public BusStopDeparture getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<BusStopDeparture> findAll() { return List.copyOf(saved); }
        @Override public List<BusStopDeparture> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public List<BusStopDeparture> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<BusStopDeparture> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public Optional<BusStopDeparture> findById(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public long count() { return saved.size(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void delete(BusStopDeparture entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends BusStopDeparture> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { saved.clear(); }
        @Override public <S extends BusStopDeparture> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> long count(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopDeparture, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
