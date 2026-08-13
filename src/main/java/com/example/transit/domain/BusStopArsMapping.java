package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * "이 좌표(ODsay 버스 정류장 승차 지점)가 실시간 도착정보 조회 API에서는 어느 정류소인지"
 * 매핑을 캐싱한다.
 * <p>
 * ODsay는 정류장을 자체 stationID로 주지만, 실시간 도착정보 API(예: 서울시)는 좌표로 가장
 * 가까운 정류소를 찾아야 하는 자체 정류소 ID(예: arsId) 체계를 쓴다. 이 매칭은 외부 API를
 * 호출해야 해서 비싸므로, 같은 좌표(=같은 승차 정류장)에 대해서는 결과를 캐싱해서 재사용한다
 * (SubwaySchedule/BusStopDeparture와 같은 lazy cache-aside 방식).
 */
@Entity
@Table(name = "bus_stop_ars_mapping")
public class BusStopArsMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 좌표를 그대로 캐시 키로 쓴다("경도,위도", 소수점 6자리 반올림). ODsay가 같은 정류장에는
     * 항상 같은 좌표를 주므로, 별도의 근접 매칭 없이 문자열 동등 비교로 캐시 히트를 노릴 수 있다.
     */
    @Column(name = "coord_key", nullable = false, unique = true, length = 40)
    private String coordKey;

    /** 매칭된 정류소 ID를 못 찾은 경우 null (그래도 재조회를 막기 위해 행 자체는 캐싱한다). */
    @Column(name = "ars_id", length = 20)
    private String arsId;

    @Column(name = "station_name", length = 50)
    private String stationName;

    /** 매칭에 쓴 좌표와 정류소 사이 거리(m). 매칭 신뢰도를 가늠하는 참고값. */
    @Column(name = "matched_distance_meters")
    private Integer matchedDistanceMeters;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BusStopArsMapping() {
        // JPA
    }

    public BusStopArsMapping(String coordKey, String arsId, String stationName, Integer matchedDistanceMeters) {
        this.coordKey = coordKey;
        this.arsId = arsId;
        this.stationName = stationName;
        this.matchedDistanceMeters = matchedDistanceMeters;
    }

    @PrePersist
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCoordKey() {
        return coordKey;
    }

    public String getArsId() {
        return arsId;
    }

    public String getStationName() {
        return stationName;
    }

    public Integer getMatchedDistanceMeters() {
        return matchedDistanceMeters;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
