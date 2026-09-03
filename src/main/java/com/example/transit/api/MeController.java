package com.example.transit.api;

import com.example.transit.domain.Role;
import com.example.transit.service.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 토큰이 누구인지 되돌려준다. 클라이언트가 로그인 상태·권한을 확인하는 용도이자,
 * 인증 필터가 principal을 제대로 채우는지 확인하는 최소 보호 엔드포인트다.
 */
@RestController
public class MeController {

    public record MeResponse(long userId, Role role, Long partnerId) {
    }

    @GetMapping("/api/v1/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.id(), user.role(), user.partnerId());
    }
}
