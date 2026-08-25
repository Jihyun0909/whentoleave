package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 서울 심야버스(N버스) 노선의 정류장 하나 (기점부터의 순번 포함).
 * <p>
 * ODsay 시절엔 {@code searchBusLane}/{@code busLaneDetail}로 노선·정류장 목록을 실시간으로
 * 받아왔는데, TAGO는 서울 시내버스를 커버하지 않아(2026-08 확인: 도시코드 목록에 서울 없음)
 * 이 방법을 못 쓴다. 대신 노선 수가 적고(~14개) 자주 안 바뀐다는 점을 이용해, 정류장 순서·좌표를
 * 정적으로 한 번 수집해 이 테이블에 시드해둔다({@code night-bus-stops.csv}).
 * <p>
 * <b>주의:</b> 이 테이블이 비어 있으면 {@code NightBusRouteFinder}는 심야버스 후보를 하나도
 * 못 찾고 조용히 빈 목록을 반환한다(기능이 아예 없는 것처럼 동작) - 실제 정류장 데이터를
 * 채워 넣기 전까지는 이게 맞는 동작이다. 잘못된 좌표를 대충 채워 넣는 것보다, 기능이
 * 빠지는 게 사용자에게 덜 위험하다.
 */
@Entity
@Table(name = "night_bus_stop",
        uniqueConstraints = @UniqueConstraint(columnNames = {"bus_no", "sequence"}))
public class NightBusStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bus_no", nullable = false, length = 20)
    private String busNo;

    /** 기점부터의 순번 (1부터 시작). */
    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    /** TAGO 자체 정류소 ID. 못 채워도 좌표 매칭 알고리즘 자체엔 영향 없다(실시간 도착정보 조회에만 필요). */
    @Column(name = "station_id", length = 20)
    private String stationId;

    @Column(name = "station_name", nullable = false, length = 60)
    private String stationName;

    @Column(name = "x", nullable = false)
    private double x;

    @Column(name = "y", nullable = false)
    private double y;

    protected NightBusStop() {
        // JPA
    }

    public NightBusStop(String busNo, Integer sequence, String stationId, String stationName,
                         double x, double y) {
        this.busNo = busNo;
        this.sequence = sequence;
        this.stationId = stationId;
        this.stationName = stationName;
        this.x = x;
        this.y = y;
    }

    public Long getId() {
        return id;
    }

    public String getBusNo() {
        return busNo;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getStationId() {
        return stationId;
    }

    public String getStationName() {
        return stationName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }
}
