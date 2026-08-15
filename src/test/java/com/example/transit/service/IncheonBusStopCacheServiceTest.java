package com.example.transit.service;

import com.example.transit.domain.RegionalBusStopMapping;
import com.example.transit.repository.RegionalBusStopMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncheonBusStopCacheServiceTest {

    @Test
    void 매칭에_성공하면_캐싱하고_재조회하지_않는다() {
        InMemoryRepository repository = new InMemoryRepository();
        CountingFinder finder = new CountingFinder(Optional.of("54321"));
        IncheonBusStopCacheService service = new IncheonBusStopCacheService(repository, finder);

        Optional<String> first = service.findStationId(126.7052, 37.4563);
        Optional<String> second = service.findStationId(126.7052, 37.4563);

        assertEquals(Optional.of("54321"), first);
        assertEquals(first, second);
        assertEquals(1, finder.calls);
    }

    @Test
    void 매칭_실패도_캐싱해서_재조회하지_않는다() {
        InMemoryRepository repository = new InMemoryRepository();
        CountingFinder finder = new CountingFinder(Optional.empty());
        IncheonBusStopCacheService service = new IncheonBusStopCacheService(repository, finder);

        service.findStationId(126.7052, 37.4563);
        Optional<String> second = service.findStationId(126.7052, 37.4563);

        assertTrue(second.isEmpty());
        assertEquals(1, finder.calls);
    }

    @Test
    void 다른_좌표는_따로_조회한다() {
        InMemoryRepository repository = new InMemoryRepository();
        CountingFinder finder = new CountingFinder(Optional.of("54321"));
        IncheonBusStopCacheService service = new IncheonBusStopCacheService(repository, finder);

        service.findStationId(126.7052, 37.4563);
        service.findStationId(126.9000, 37.6000);

        assertEquals(2, finder.calls);
    }

    private static class CountingFinder extends IncheonStationFinder {
        private final Optional<String> result;
        private int calls;

        CountingFinder(Optional<String> result) {
            super(null);
            this.result = result;
        }

        @Override
        public Optional<String> findNearestStationId(double x, double y) {
            calls++;
            return result;
        }
    }

    private static class InMemoryRepository implements RegionalBusStopMappingRepository {
        private final List<RegionalBusStopMapping> saved = new ArrayList<>();

        @Override
        public Optional<RegionalBusStopMapping> findByProviderAndCoordKey(String provider, String coordKey) {
            return saved.stream()
                    .filter(m -> m.getProvider().equals(provider) && m.getCoordKey().equals(coordKey))
                    .findFirst();
        }

        @Override
        public <S extends RegionalBusStopMapping> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public <S extends RegionalBusStopMapping> List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RegionalBusStopMapping> findById(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsById(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RegionalBusStopMapping> findAll() {
            return saved;
        }

        @Override
        public List<RegionalBusStopMapping> findAllById(Iterable<Long> longs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            return saved.size();
        }

        @Override
        public void deleteById(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(RegionalBusStopMapping entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllById(Iterable<? extends Long> longs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(Iterable<? extends RegionalBusStopMapping> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll() {
            saved.clear();
        }

        @Override
        public List<RegionalBusStopMapping> findAll(Sort sort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.data.domain.Page<RegionalBusStopMapping> findAll(
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flush() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> S saveAndFlush(S entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> List<S> saveAllAndFlush(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllInBatch(Iterable<RegionalBusStopMapping> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllByIdInBatch(Iterable<Long> longs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllInBatch() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegionalBusStopMapping getOne(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegionalBusStopMapping getById(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegionalBusStopMapping getReferenceById(Long aLong) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> List<S> findAll(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> List<S> findAll(Example<S> example, Sort sort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> Optional<S> findOne(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> org.springframework.data.domain.Page<S> findAll(
                Example<S> example, org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> long count(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping> boolean exists(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends RegionalBusStopMapping, R> R findBy(Example<S> example,
                Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }
}
