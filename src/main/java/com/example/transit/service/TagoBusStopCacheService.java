package com.example.transit.service;

import com.example.transit.domain.RegionalBusStopMapping;
import com.example.transit.repository.RegionalBusStopMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * 좌표 -> TAGO 정류소(cityCode+nodeId) 매칭을 캐싱한다. GyeonggiBusStopCacheService와 같은
 * lazy cache-aside 방식 - 매칭 실패도 캐싱해서 실시간 정보가 없는 정류장을 매번 헛되이 조회하지 않는다.
 */
@Service
public class TagoBusStopCacheService implements TagoBusStopLookup {

    private static final String PROVIDER = "TAGO";

    private final RegionalBusStopMappingRepository repository;
    private final TagoStationFinder stationFinder;

    public TagoBusStopCacheService(RegionalBusStopMappingRepository repository, TagoStationFinder stationFinder) {
        this.repository = repository;
        this.stationFinder = stationFinder;
    }

    @Override
    @Transactional
    public Optional<TagoStop> findStop(double stationX, double stationY) {
        String coordKey = coordKey(stationX, stationY);

        Optional<RegionalBusStopMapping> cached = repository.findByProviderAndCoordKey(PROVIDER, coordKey);
        if (cached.isPresent()) {
            return toStop(cached.get());
        }

        Optional<TagoStop> match = stationFinder.findNearestStop(stationX, stationY);
        RegionalBusStopMapping mapping = match
                .map(m -> new RegionalBusStopMapping(PROVIDER, coordKey, m.cityCode(), m.nodeId(), null, null))
                .orElseGet(() -> new RegionalBusStopMapping(PROVIDER, coordKey, null, null, null, null));
        repository.save(mapping);

        return match;
    }

    private Optional<TagoStop> toStop(RegionalBusStopMapping mapping) {
        if (mapping.getStopId() == null || mapping.getCityCode() == null) {
            return Optional.empty();
        }
        return Optional.of(new TagoStop(mapping.getCityCode(), mapping.getStopId()));
    }

    private String coordKey(double x, double y) {
        return String.format(Locale.ROOT, "%.6f,%.6f", x, y);
    }
}
