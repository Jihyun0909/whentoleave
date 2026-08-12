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
 * "이 버스 노선이 이 정류장을 지나는 첫차/막차 시각"의 <b>추정값</b>.
 * <p>
 * 지하철(SubwaySchedule)은 ODsay가 역별 실제 시간표를 주지만, 버스는 정류장별 시간표 API가
 * 아예 없다. 노선 단위의 "기점 기준 첫차/막차"와 정류장별 누적거리만 주기 때문에,
 * <pre>
 *   정류장 통과 시각 ≈ 기점 출발 시각 + (기점~정류장 누적거리 / 그 구간 평균 속도)
 * </pre>
 * 로 추정한 값을 저장한다. 그래서 이 테이블의 값은 시간표가 아니라 추정치이며,
 * 화면에서도 버스가 낀 경로는 "추정" 표시를 함께 보여준다.
 */
@Entity
@Table(name = "bus_stop_departure")
public class BusStopDeparture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bus_id", nullable = false)
    private Integer busId;

    @Column(name = "station_id", nullable = false)
    private Integer stationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    @Column(name = "first_time", nullable = false)
    private LocalTime firstTime;

    @Column(name = "last_time", nullable = false)
    private LocalTime lastTime;

    /** lastTime이 "다음날 이 시각"인지 (ODsay의 24시 초과 표기 정규화 결과). */
    @Column(name = "last_time_next_day", nullable = false)
    private boolean lastTimeNextDay;

    /** 배차간격(분). 요일유형에 맞는 값. */
    @Column(name = "interval_minutes", nullable = false)
    private Integer intervalMinutes;

    @Column(name = "bus_no", length = 40)
    private String busNo;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BusStopDeparture() {
        // JPA
    }

    public BusStopDeparture(Integer busId, Integer stationId, DayType dayType, LocalTime firstTime,
                             LocalTime lastTime, boolean lastTimeNextDay, Integer intervalMinutes, String busNo) {
        this.busId = busId;
        this.stationId = stationId;
        this.dayType = dayType;
        this.firstTime = firstTime;
        this.lastTime = lastTime;
        this.lastTimeNextDay = lastTimeNextDay;
        this.intervalMinutes = intervalMinutes;
        this.busNo = busNo;
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Integer getBusId() {
        return busId;
    }

    public Integer getStationId() {
        return stationId;
    }

    public DayType getDayType() {
        return dayType;
    }

    public LocalTime getFirstTime() {
        return firstTime;
    }

    public LocalTime getLastTime() {
        return lastTime;
    }

    public boolean isLastTimeNextDay() {
        return lastTimeNextDay;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public String getBusNo() {
        return busNo;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
