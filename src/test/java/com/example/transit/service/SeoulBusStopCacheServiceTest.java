package com.example.transit.service;

import com.example.transit.domain.BusStopArsMapping;
import com.example.transit.repository.BusStopArsMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좌표 -> 정류소 ID 매칭 캐시 로직 검증. 실제 서울시 API 호출 없이,
 * {@link RealtimeStationFinder}를 람다 페이크로 대체한다 (Mockito 없이 가는 이 프로젝트 방식).
 */
class SeoulBusStopCacheServiceTest {

    private static final double X = 127.027648;
    private static final double Y = 37.497936;

    @Test
    void 매칭에_성공하면_정류소_ID를_반환하고_캐싱한다() {
        CountingFinder finder = new CountingFinder(
                Optional.of(new RealtimeStationFinder.StationMatch("23155", "강남역", 12)));
        InMemoryRepository repository = new InMemoryRepository();
        SeoulBusStopCacheService service = new SeoulBusStopCacheService(repository, finder);

        Optional<String> first = service.findStationId(X, Y);
        Optional<String> second = service.findStationId(X, Y);

        assertEquals(Optional.of("23155"), first);
        assertEquals(Optional.of("23155"), second);
        assertEquals(1, finder.calls); // 두 번째 조회는 캐시에서 읽어서 API를 다시 안 부름
        assertEquals(1, repository.count());
    }

    @Test
    void 매칭_실패도_캐싱해서_같은_좌표를_다시_조회하지_않는다() {
        CountingFinder finder = new CountingFinder(Optional.empty());
        InMemoryRepository repository = new InMemoryRepository();
        SeoulBusStopCacheService service = new SeoulBusStopCacheService(repository, finder);

        Optional<String> first = service.findStationId(X, Y);
        Optional<String> second = service.findStationId(X, Y);

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertEquals(1, finder.calls);
        assertEquals(1, repository.count());
    }

    @Test
    void 좌표가_다르면_별도로_조회한다() {
        CountingFinder finder = new CountingFinder(
                Optional.of(new RealtimeStationFinder.StationMatch("23155", "강남역", 12)));
        InMemoryRepository repository = new InMemoryRepository();
        SeoulBusStopCacheService service = new SeoulBusStopCacheService(repository, finder);

        service.findStationId(X, Y);
        service.findStationId(X + 1, Y + 1);

        assertEquals(2, finder.calls);
    }

    private static class CountingFinder implements RealtimeStationFinder {
        private final Optional<StationMatch> result;
        private int calls;

        CountingFinder(Optional<StationMatch> result) {
            this.result = result;
        }

        @Override
        public Optional<StationMatch> findNearestStation(double x, double y) {
            calls++;
            return result;
        }
    }

    /** 저장/조회만 쓰는 아주 작은 페이크 (Mockito 없이 가는 이 프로젝트 방식). */
    private static class InMemoryRepository implements BusStopArsMappingRepository {
        private final List<BusStopArsMapping> saved = new ArrayList<>();

        @Override
        public Optional<BusStopArsMapping> findByCoordKey(String coordKey) {
            return saved.stream().filter(m -> m.getCoordKey().equals(coordKey)).findFirst();
        }

        @Override
        public <S extends BusStopArsMapping> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        // --- 아래는 이 테스트에서 쓰지 않는 JpaRepository 메서드들 ---
        @Override public void flush() { }
        @Override public <S extends BusStopArsMapping> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends BusStopArsMapping> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<BusStopArsMapping> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public BusStopArsMapping getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public BusStopArsMapping getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public BusStopArsMapping getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<BusStopArsMapping> findAll() { return List.copyOf(saved); }
        @Override public List<BusStopArsMapping> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public List<BusStopArsMapping> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<BusStopArsMapping> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public Optional<BusStopArsMapping> findById(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public long count() { return saved.size(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void delete(BusStopArsMapping entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends BusStopArsMapping> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { saved.clear(); }
        @Override public <S extends BusStopArsMapping> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> long count(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends BusStopArsMapping, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
