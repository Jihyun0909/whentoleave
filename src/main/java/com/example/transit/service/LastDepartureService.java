package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 출발지/도착지 좌표를 받아 ODsay 경로탐색 -> 지하철 구간 추출 -> 막차 역산까지 잇는다.
 */
@Service
public class LastDepartureService {

    private final OdsayClient odsayClient;
    private final RouteLegExtractor routeLegExtractor;
    private final LastDepartureCalculator calculator;

    public LastDepartureService(OdsayClient odsayClient,
                                 RouteLegExtractor routeLegExtractor,
                                 LastDepartureCalculator calculator) {
        this.odsayClient = odsayClient;
        this.routeLegExtractor = routeLegExtractor;
        this.calculator = calculator;
    }

    public LastDepartureResult calculate(double sx, double sy, double ex, double ey) {
        try {
            OdsayPathResponse response = odsayClient.searchSubwayPath(sx, sy, ex, ey);
            List<SubwayLeg> legs = routeLegExtractor.extract(response);
            return calculator.calculate(legs);
        } catch (NoSubwayRouteFoundException e) {
            return new LastDepartureResult.Infeasible(e.getMessage());
        }
    }
}
