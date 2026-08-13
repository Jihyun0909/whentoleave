package com.example.transit.service;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import com.example.transit.repository.BusStopDepartureRepository;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayBusLaneDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 버스 막차 "추정" 로직 검증. 실제 ODsay 응답에서 확인한 형태를 그대로 쓴다
 * (시간/배차간격이 문자열, station[]에 기점부터의 누적거리).
 */
class BusDepartureCacheServiceTest {

    private static final int BUS_ID = 1500;
    private static final int BOARDING_STOP_ID = 80821;

    @Test
    void 기점부터_승차정류장까지_걸리는_시간만큼_막차를_늦춰서_추정한다() {
        // 구간: 2000m를 10분에 감 -> 200m/분. 승차 정류장은 기점에서 4000m -> 20분 걸림.
        // 기점 막차 23:00 -> 승차 정류장 막차는 23:20으로 추정되어야 한다.
        BusDepartureCacheService service = service(detail("05:00", "23:00", "10", 4000));
        TransitLeg leg = busLeg(2000, 10);

        List<Integer> minutes = service.departureServiceMinutes(leg);

        assertEquals(23 * 60 + 20, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * 왕복 노선은 정류장 목록에 상·하행이 다 들어있어 복귀 구간 정류장의 누적거리가 수십 km까지
     * 나온다. 그대로 환산하면 "막차가 아침에 지나간다"는 값이 나와서(N61에서 실제 발생) 상한을 둔다.
     */
    @Test
    void 누적거리가_과하게_크면_오프셋을_상한으로_자른다() {
        // 60km를 200m/분으로 환산하면 300분이지만, 상한 60분으로 잘려야 한다.
        BusDepartureCacheService service = service(detail("05:00", "23:00", "10", 60000));
        TransitLeg leg = busLeg(2000, 10);

        List<Integer> minutes = service.departureServiceMinutes(leg);

        assertEquals(23 * 60 + 60, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void 막차부터_배차간격만큼_거슬러_올라가며_차편_후보를_만든다() {
        BusDepartureCacheService service = service(detail("22:00", "23:00", "20", 0));
        TransitLeg leg = busLeg(0, 0); // 거리 정보 없음 -> 기점 시각 그대로

        List<Integer> minutes = service.departureServiceMinutes(leg);

        // 23:00, 22:40, 22:20, 22:00
        assertEquals(List.of(23 * 60, 22 * 60 + 40, 22 * 60 + 20, 22 * 60), minutes);
    }

    @Test
    void 한_구간에_노선이_여러개면_모든_노선의_차편을_합쳐서_가장_늦은_막차까지_고려한다() {
        Map<Integer, OdsayBusLaneDetailResponse> details = new HashMap<>();
        details.put(1, detail("05:00", "22:30", "30", 0));
        details.put(2, detail("05:00", "23:40", "30", 0)); // 이 노선이 더 늦게까지 다님
        BusDepartureCacheService service = service(details);

        TransitLeg leg = TransitLeg.bus(BOARDING_STOP_ID, 0, 0, "정류장", "하차정류장", "간선", List.of(1, 2), "146", 0);
        List<Integer> minutes = service.departureServiceMinutes(leg);

        assertEquals(23 * 60 + 40, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    /**
     * 심야버스 회귀 테스트. N15는 첫차 24:00 / 막차 27:30이라 자정 이후에만 다니는데,
     * 첫차의 "다음날" 여부를 잃어버리면 첫차가 00:00으로 해석되어 막차부터 배차간격으로
     * 거슬러 내려가는 루프가 낮 시간대 차편까지 만들어냈다 (오후 4시에 N버스를 탈 수 있다고
     * 안내하던 버그). 후보는 전부 24:00 이후여야 한다.
     */
    @Test
    void 자정_이후에만_다니는_노선은_낮_시간대_차편을_만들지_않는다() {
        BusDepartureCacheService service = service(detail("24:00", "27:30", "35", 0));

        List<Integer> minutes = service.departureServiceMinutes(busLeg(0, 0));

        assertFalse(minutes.isEmpty());
        assertEquals(24 * 60, minutes.stream().mapToInt(Integer::intValue).min().orElseThrow());
        assertEquals(27 * 60 + 30, minutes.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void 배차간격이_숫자가_아니면_기본값으로_대체한다() {
        // ODsay는 "1회 -"처럼 숫자가 아닌 배차간격도 준다.
        BusDepartureCacheService service = service(detail("22:00", "23:00", "1회 -", 0));
        TransitLeg leg = busLeg(0, 0);

        List<Integer> minutes = service.departureServiceMinutes(leg);

        assertFalse(minutes.isEmpty());
        assertEquals(23 * 60, minutes.get(0));
    }

    @Test
    void 노선_상세_조회가_실패하면_그_노선은_후보에서_빠진다() {
        OdsayClient failing = new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayBusLaneDetailResponse fetchBusLaneDetail(int busId) {
                throw new IllegalStateException("API 실패");
            }
        };
        BusDepartureCacheService service = new BusDepartureCacheService(new InMemoryRepository(), failing);

        assertTrue(service.departureServiceMinutes(busLeg(0, 0)).isEmpty());
    }

    @Test
    void 같은_노선_정류장은_두번째부터_캐시에서_읽어_API를_다시_호출하지_않는다() {
        CountingClient client = new CountingClient(detail("05:00", "23:00", "10", 0));
        BusDepartureCacheService service = new BusDepartureCacheService(new InMemoryRepository(), client);

        service.departureServiceMinutes(busLeg(0, 0));
        service.departureServiceMinutes(busLeg(0, 0));

        assertEquals(1, client.calls);
    }

    private TransitLeg busLeg(int distanceMeters, int rideMinutes) {
        return TransitLeg.bus(BOARDING_STOP_ID, rideMinutes, 0, "정류장", "하차정류장", "간선", List.of(BUS_ID), "120", distanceMeters);
    }

    private OdsayBusLaneDetailResponse detail(String first, String last, String interval, int stopDistance) {
        return new OdsayBusLaneDetailResponse(new OdsayBusLaneDetailResponse.Result(
                "120", first, last, interval, interval, interval, interval, 30000,
                List.of(new OdsayBusLaneDetailResponse.Station(0, BOARDING_STOP_ID, "정류장", stopDistance))));
    }

    private BusDepartureCacheService service(OdsayBusLaneDetailResponse detail) {
        return service(Map.of(BUS_ID, detail));
    }

    private BusDepartureCacheService service(Map<Integer, OdsayBusLaneDetailResponse> details) {
        OdsayClient client = new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayBusLaneDetailResponse fetchBusLaneDetail(int busId) {
                return details.get(busId);
            }
        };
        return new BusDepartureCacheService(new InMemoryRepository(), client);
    }

    private class CountingClient extends OdsayClient {
        private final OdsayBusLaneDetailResponse detail;
        private int calls;

        CountingClient(OdsayBusLaneDetailResponse detail) {
            super("http://dummy", "dummy");
            this.detail = detail;
        }

        @Override
        public OdsayBusLaneDetailResponse fetchBusLaneDetail(int busId) {
            calls++;
            return detail;
        }
    }

    /** 저장/조회만 쓰는 아주 작은 페이크 (Mockito 없이 가는 이 프로젝트 방식). */
    private static class InMemoryRepository implements BusStopDepartureRepository {
        private final List<BusStopDeparture> saved = new ArrayList<>();

        @Override
        public Optional<BusStopDeparture> findByBusIdAndStationIdAndDayType(
                Integer busId, Integer stationId, DayType dayType) {
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
