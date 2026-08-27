package com.example.transit.service;

import com.example.transit.domain.SubwaySchedule;

import java.time.LocalDate;
import java.util.List;

/**
 * 역×방향의 시간표 후보 목록(그 역/방향/요일유형의 전체 시간표)을 조회한다.
 * SubwayScheduleCacheService의 실제 구현과 분리해두면, LastDepartureCalculator를
 * Mockito 같은 목킹 프레임워크 없이 람다 페이크로 테스트할 수 있다.
 *
 * @param date        어느 날짜 기준인지. "다른날 막차 보기"에서 고른 날짜가 여기까지 내려와
 *                    평일/토요일/공휴일 시간표를 갈라준다.
 * @param stationName 이 역의 표시용 이름(예: "사당"). TAGO/서울교통공사 API가 모두 비어 있는
 *                    역을 서울교통공사 전체 시간표 시드로 보완할 때, 시드가 stationId 체계
 *                    (TAGO 자체 ID)가 아니라 역명으로 찾아야 해서 필요하다.
 * @param laneName    이 구간의 노선 표시명(예: "수도권4호선"). 위와 같은 이유로 시드에서
 *                    호선을 좁히는 데 쓴다.
 */
@FunctionalInterface
public interface LastTrainLookup {
    List<SubwaySchedule> getLastTrains(String stationId, int wayCode, LocalDate date,
                                        String stationName, String laneName);
}
