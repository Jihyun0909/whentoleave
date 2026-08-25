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
 * "이 버스 노선이 이 정류장을 지나는 첫차/막차 시각"의 <b>추정값</b>.
 * <p>
 * 지하철(SubwaySchedule)은 ODsay가 역별 실제 시간표를 주지만, 버스는 정류장별 시간표 API가
 * 아예 없다. 노선 단위의 "기점 기준 첫차/막차"와 정류장별 누적거리만 주기 때문에,
 * <pre>
 *   정류장 통과 시각 ≈ 기점 출발 시각 + (기점~정류장 누적거리 / 그 구간 평균 속도)
 * </pre>
 * 로 추정한 값을 저장한다. 그래서 이 테이블의 값은 시간표가 아니라 추정치이며,
 * 화면에서도 버스가 낀 경로는 "추정" 표시를 함께 보여준다.
 * <p>
 * (bus_id, station_id, day_type)에 유니크 제약을 둔다. subway_schedule과 달리 이 테이블은
 * 키 하나당 딱 한 행(그 노선·정류장·요일유형의 첫차/막차 한 세트)만 있어야 하는 설계라서
 * ({@link com.example.transit.repository.BusStopDepartureRepository}가 {@code Optional}을
 * 반환하는 이유), 제약이 없으면 동시 요청이 캐시 미스에서 경합해 같은 키로 두 행을 넣을 수
 * 있고, 그러면 이후 조회가 전부 {@code NonUniqueResultException}으로 500을 낸다(실제 발생).
 */
@Entity
@Table(name = "bus_stop_departure",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bus_stop_departure_bus_station_day",
                columnNames = {"bus_id", "station_id", "day_type"}))
public class BusStopDeparture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bus_id", nullable = false, length = 20)
    private String busId;

    @Column(name = "station_id", nullable = false, length = 20)
    private String stationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    @Column(name = "first_time", nullable = false)
    private LocalTime firstTime;

    /**
     * firstTime이 "다음날 이 시각"인지. 심야버스는 첫차가 24:00처럼 자정 이후라서 이게 꼭 필요하다 —
     * 없으면 첫차 24:00이 00:00으로 해석되어, 막차부터 배차간격으로 거슬러 내려가는 루프가
     * 하루 종일치 유령 차편을 만들어낸다(오후 4시에 N버스를 탈 수 있다고 안내하던 버그).
     */
    // 기본값을 명시해야 이미 행이 있는 테이블에도 컬럼을 추가할 수 있다
    // (ddl-auto=update가 기본값 없는 NOT NULL 컬럼 추가에 실패하는 문제).
    @Column(name = "first_time_next_day", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean firstTimeNextDay;

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

    public BusStopDeparture(String busId, String stationId, DayType dayType,
                             LocalTime firstTime, boolean firstTimeNextDay,
                             LocalTime lastTime, boolean lastTimeNextDay, Integer intervalMinutes, String busNo) {
        this.busId = busId;
        this.stationId = stationId;
        this.dayType = dayType;
        this.firstTime = firstTime;
        this.firstTimeNextDay = firstTimeNextDay;
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

    public String getBusId() {
        return busId;
    }

    public String getStationId() {
        return stationId;
    }

    public DayType getDayType() {
        return dayType;
    }

    public LocalTime getFirstTime() {
        return firstTime;
    }

    public boolean isFirstTimeNextDay() {
        return firstTimeNextDay;
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
