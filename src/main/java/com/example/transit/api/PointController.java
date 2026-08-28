package com.example.transit.api;

import com.example.transit.api.dto.point.PointBalanceResponse;
import com.example.transit.api.dto.point.PointHistoryItem;
import com.example.transit.service.auth.AuthenticatedUser;
import com.example.transit.service.point.PointService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 내 선불 포인트 잔액·이력. */
@RestController
@RequestMapping("/api/v1/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    @GetMapping
    public PointBalanceResponse balance(@AuthenticationPrincipal AuthenticatedUser user) {
        return new PointBalanceResponse(pointService.balanceOf(user.id()));
    }

    @GetMapping("/history")
    public List<PointHistoryItem> history(@AuthenticationPrincipal AuthenticatedUser user) {
        return pointService.historyOf(user.id()).stream().map(PointHistoryItem::from).toList();
    }
}
