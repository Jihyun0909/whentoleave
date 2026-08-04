package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 역 하나(stationId + wayCode)의 특정 요일유형(dayType)에 대해,
 * 목적지(endStationName)별 막차 시각을 저장한다.
 * 분기 노선(예: 4호선)은 같은 역/방향이라도 목적지별로 막차가 여러 개 존재할 수 있다.
 */
@Entity
@Table(
        name = "subway_last_train",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"station_id", "way_code", "day_type", "end_station_name"}
        )
)
public class SubwayLastTrain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false)
    private Integer stationId;

    @Column(name = "way_code", nullable = false)
    private Integer wayCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    @Column(name = "end_station_name", nullable = false, length = 50)
    private String endStationName;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    /**
     * ODsay의 24시 초과 표기(예: "24:35")를 정규화하며 발생하는 다음날 여부.
     * true면 departureTime은 "다음날 이 시각"을 의미한다 (예: 24:35 -> departureTime=00:35, nextDay=true).
     */
    @Column(name = "next_day", nullable = false)
    private boolean nextDay;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SubwayLastTrain() {
        // JPA
    }

    public SubwayLastTrain(Integer stationId, Integer wayCode, DayType dayType,
                            String endStationName, LocalTime departureTime, boolean nextDay) {
        this.stationId = stationId;
        this.wayCode = wayCode;
        this.dayType = dayType;
        this.endStationName = endStationName;
        this.departureTime = departureTime;
        this.nextDay = nextDay;
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Integer getStationId() {
        return stationId;
    }

    public Integer getWayCode() {
        return wayCode;
    }

    public DayType getDayType() {
        return dayType;
    }

    public String getEndStationName() {
        return endStationName;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public boolean isNextDay() {
        return nextDay;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
