package com.example.transit.service;

import com.example.transit.domain.BusStopArsMapping;
import com.example.transit.repository.BusStopArsMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * 좌표 -> 실시간 도착정보용 정류소 ID 매칭을 캐싱한다.
 * <p>
 * SubwayScheduleCacheService/BusDepartureCacheService와 같은 lazy cache-aside 방식:
 * 실제 요청이 들어온 정류장만 그때 처음 조회해서 캐싱하고, 이후 같은 좌표는 DB에서 바로 읽는다.
 * 매칭 실패(정보 없음)도 캐싱한다 - 안 그러면 원래 실시간 정보가 없는 정류장(예: 수도권
 * 외곽, 서울시 밖)을 요청할 때마다 매번 외부 API를 헛되이 호출하게 된다.
 */
@Service
public class SeoulBusStopCacheService implements RealtimeBusStopLookup {

    private final BusStopArsMappingRepository repository;
    private final RealtimeStationFinder stationFinder;

    public SeoulBusStopCacheService(BusStopArsMappingRepository repository, RealtimeStationFinder stationFinder) {
        this.repository = repository;
        this.stationFinder = stationFinder;
    }

    @Override
    @Transactional
    public Optional<String> findStationId(double stationX, double stationY) {
        String coordKey = coordKey(stationX, stationY);

        Optional<BusStopArsMapping> cached = repository.findByCoordKey(coordKey);
        if (cached.isPresent()) {
            return Optional.ofNullable(cached.get().getArsId());
        }

        Optional<RealtimeStationFinder.StationMatch> match = stationFinder.findNearestStation(stationX, stationY);
        BusStopArsMapping mapping = match
                .map(m -> new BusStopArsMapping(coordKey, m.stationId(), m.stationName(), m.distanceMeters()))
                .orElseGet(() -> new BusStopArsMapping(coordKey, null, null, null));
        repository.save(mapping);

        return match.map(RealtimeStationFinder.StationMatch::stationId);
    }

    /** ODsay가 같은 정류장에는 항상 같은 좌표를 주므로, 반올림 없이 그대로 키로 쓴다. */
    private String coordKey(double x, double y) {
        return String.format(Locale.ROOT, "%.6f,%.6f", x, y);
    }
}
