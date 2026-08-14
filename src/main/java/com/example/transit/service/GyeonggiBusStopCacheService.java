package com.example.transit.service;

import com.example.transit.domain.RegionalBusStopMapping;
import com.example.transit.repository.RegionalBusStopMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * 좌표 -> GBIS 정류소 ID 매칭을 캐싱한다. TagoBusStopCacheService와 같은 lazy cache-aside 방식.
 */
@Service
public class GyeonggiBusStopCacheService implements GyeonggiBusStopLookup {

    private static final String PROVIDER = "GYEONGGI";

    private final RegionalBusStopMappingRepository repository;
    private final GyeonggiStationFinder stationFinder;

    public GyeonggiBusStopCacheService(RegionalBusStopMappingRepository repository,
                                        GyeonggiStationFinder stationFinder) {
        this.repository = repository;
        this.stationFinder = stationFinder;
    }

    @Override
    @Transactional
    public Optional<String> findStationId(double stationX, double stationY) {
        String coordKey = coordKey(stationX, stationY);

        Optional<RegionalBusStopMapping> cached = repository.findByProviderAndCoordKey(PROVIDER, coordKey);
        if (cached.isPresent()) {
            return Optional.ofNullable(cached.get().getStopId());
        }

        Optional<String> match = stationFinder.findNearestStationId(stationX, stationY);
        RegionalBusStopMapping mapping = new RegionalBusStopMapping(PROVIDER, coordKey, null, match.orElse(null),
                null, null);
        repository.save(mapping);

        return match;
    }

    private String coordKey(double x, double y) {
        return String.format(Locale.ROOT, "%.6f,%.6f", x, y);
    }
}
