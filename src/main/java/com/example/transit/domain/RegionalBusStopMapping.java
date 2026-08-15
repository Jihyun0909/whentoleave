package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * "이 좌표(ODsay 버스 정류장 승차 지점)가 지역별 실시간 버스 도착정보 API에서는 어느 정류소인지"
 * 매핑을 캐싱한다. TAGO/경기(GBIS)/인천(BIMS) 세 지역 제공처를 {@code provider} 컬럼으로 구분해
 * 한 테이블에 같이 담는다 - 세 매핑 로직이 "좌표로 가장 가까운 정류소 하나 찾아서 캐싱"이라는
 * 같은 모양이라 굳이 테이블을 나눌 이유가 없다.
 * <p>
 * ODsay는 정류장을 자체 stationID로 주지만, 각 지역 실시간 API는 좌표로 가장 가까운 정류소를
 * 찾아야 하는 자체 정류소 ID 체계를 쓴다. 이 매칭은 외부 API를 호출해야 해서 비싸므로, 같은
 * 좌표(=같은 승차 정류장)에 대해서는 결과를 캐싱해서 재사용한다(SubwayScheduleCacheService와
 * 같은 lazy cache-aside 방식).
 */
@Entity
@Table(name = "regional_bus_stop_mapping",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "coord_key"}))
public class RegionalBusStopMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "TAGO" | "GYEONGGI" | "INCHEON". RegionalBusStopProvider 열거값의 name()을 그대로 쓴다. */
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    /** 좌표를 그대로 캐시 키로 쓴다("경도,위도", 소수점 6자리). */
    @Column(name = "coord_key", nullable = false, length = 40)
    private String coordKey;

    /** TAGO 전용 지역코드. 경기/인천은 stopId만으로 조회가 되므로 null. */
    @Column(name = "city_code", length = 10)
    private String cityCode;

    /** 매칭된 정류소 ID를 못 찾은 경우 null (그래도 재조회를 막기 위해 행 자체는 캐싱한다). */
    @Column(name = "stop_id", length = 20)
    private String stopId;

    @Column(name = "station_name", length = 50)
    private String stationName;

    /** 매칭에 쓴 좌표와 정류소 사이 거리(m). 매칭 신뢰도를 가늠하는 참고값. */
    @Column(name = "matched_distance_meters")
    private Integer matchedDistanceMeters;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected RegionalBusStopMapping() {
        // JPA
    }

    public RegionalBusStopMapping(String provider, String coordKey, String cityCode, String stopId,
                                   String stationName, Integer matchedDistanceMeters) {
        this.provider = provider;
        this.coordKey = coordKey;
        this.cityCode = cityCode;
        this.stopId = stopId;
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

    public String getProvider() {
        return provider;
    }

    public String getCoordKey() {
        return coordKey;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getStopId() {
        return stopId;
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
