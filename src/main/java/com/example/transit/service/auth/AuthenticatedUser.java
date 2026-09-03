package com.example.transit.service.auth;

import com.example.transit.domain.Role;

/**
 * 검증된 Access Token에서 뽑아낸 요청 주체. 컨트롤러는 이 값만 서비스로 넘기고,
 * 서비스 계층은 {@code Authentication} 같은 Spring Security 타입을 몰라야 한다
 * (기존 원칙: service는 HTTP/보안 프레임워크에 종속되지 않는다).
 *
 * @param id       app_user.id
 * @param role     권한
 * @param partnerId PARTNER_ADMIN이면 소속 제휴사 id, 아니면 null
 */
public record AuthenticatedUser(long id, Role role, Long partnerId) {
}
