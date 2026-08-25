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

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 역 하나(stationId + wayCode)의 특정 요일유형(dayType)에 대한 시간표 항목 하나.
 * <p>
 * 처음엔 "공식 막차(firstLastFlag=2/3)"만 저장했었는데, 그러면 "막차로 태그되진
 * 않았지만 다음 환승 마감엔 여전히 맞는 열차"를 놓치는 문제가 있었다 (이슈 #5).
 * 그래서 지금은 그 역/방향/요일유형의 전체 시간표를 저장하고, 계산 시점에
 * "마감을 만족하는 가장 늦은 열차"를 그때그때 찾는다. API 호출 횟수는 그대로다
 * (이미 받는 응답에서 더 많은 항목을 저장하는 것뿐).
 */
@Entity
@Table(name = "subway_schedule")
public class SubwaySchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 20)
    private String stationId;

    @Column(name = "way_code", nullable = false)
    private Integer wayCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    /**
     * TAGO가 이 필드를 비워서 주는 행이 실제로 있다(2026-08-25 실사용 중 발견 - 이른 새벽
     * 첫차류로 보이는 일부 항목). ODsay는 항상 채워서 줬으므로 원래 not-null이었는데, 그
     * 가정이 TAGO에는 안 맞아 nullable로 바꿨다 - 표시용/earlierStopNames 비교용일 뿐 막차
     * 시각 계산 자체에는 안 쓰이므로 비어 있어도 안전하다.
     */
    @Column(name = "end_station_name", length = 50)
    private String endStationName;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    /**
     * ODsay의 24시 초과 표기(예: "24:35")를 정규화하며 발생하는 다음날 여부.
     * true면 departureTime은 "다음날 이 시각"을 의미한다 (예: 24:35 -> departureTime=00:35, nextDay=true).
     */
    @Column(name = "next_day", nullable = false)
    private boolean nextDay;

    /** ODsay 원본 값: 0=일반, 1=첫차, 2=막차, 3=첫차·막차 동시. 참고용, 계산 로직에서는 안 씀. */
    @Column(name = "first_last_flag")
    private Integer firstLastFlag;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SubwaySchedule() {
        // JPA
    }

    public SubwaySchedule(String stationId, Integer wayCode, DayType dayType,
                           String endStationName, LocalTime departureTime, boolean nextDay,
                           Integer firstLastFlag) {
        this.stationId = stationId;
        this.wayCode = wayCode;
        this.dayType = dayType;
        this.endStationName = endStationName;
        this.departureTime = departureTime;
        this.nextDay = nextDay;
        this.firstLastFlag = firstLastFlag;
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getStationId() {
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

    public Integer getFirstLastFlag() {
        return firstLastFlag;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
