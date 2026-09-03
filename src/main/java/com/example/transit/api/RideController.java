package com.example.transit.api;

import com.example.transit.api.dto.ride.CompletionResponse;
import com.example.transit.api.dto.ride.RideCompleteRequest;
import com.example.transit.api.dto.ride.RideCreateRequest;
import com.example.transit.api.dto.ride.RideResponse;
import com.example.transit.service.auth.AuthenticatedUser;
import com.example.transit.service.ride.RideService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 가상 택시 이용. 전부 인증 필요이고, 항상 "내" 이용만 다룬다(컨트롤러가 토큰의 userId만
 * 서비스로 넘긴다 - 서비스는 Security 타입을 모른다).
 */
@RestController
@RequestMapping("/api/v1/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RideResponse request(@AuthenticationPrincipal AuthenticatedUser user,
                                @Valid @RequestBody RideCreateRequest request) {
        return RideResponse.from(rideService.request(
                user.id(), request.partnerId(), request.origin(), request.destination(), request.fareAmount()));
    }

    @GetMapping
    public List<RideResponse> listMine(@AuthenticationPrincipal AuthenticatedUser user) {
        return rideService.listMine(user.id()).stream().map(RideResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RideResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return RideResponse.from(rideService.getMine(user.id(), id));
    }

    @PostMapping("/{id}/start")
    public RideResponse start(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return RideResponse.from(rideService.start(user.id(), id));
    }

    @PostMapping("/{id}/complete")
    public CompletionResponse complete(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable long id,
                                       @Valid @RequestBody RideCompleteRequest request) {
        return CompletionResponse.from(rideService.complete(user.id(), id, request.pointToUse()));
    }

    @PostMapping("/{id}/cancel")
    public RideResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return RideResponse.from(rideService.cancel(user.id(), id));
    }
}
