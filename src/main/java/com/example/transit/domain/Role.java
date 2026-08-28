package com.example.transit.domain;

/**
 * 회원 권한. 기획안의 "가상 B2B/B2C 권한 분리"를 실제 제휴 심사·사업자 등록 없이
 * 이 enum 하나로만 가른다.
 *
 * <ul>
 *   <li>{@link #USER} — B2C 일반 사용자. 포인트 적립/차감의 주체.</li>
 *   <li>{@link #PARTNER_ADMIN} — B2B 제휴사 관리자. {@code app_user.partner_id}가 채워져 있어야
 *       하며, 자기 제휴사의 정산 내역만 조회할 수 있다. (partner 도메인은 PR B에서 추가)</li>
 *   <li>{@link #ADMIN} — 운영자. 정산 배치 실행·감사 로그 조회.</li>
 * </ul>
 */
public enum Role {
    USER,
    PARTNER_ADMIN,
    ADMIN;

    /** Spring Security 권한 문자열. {@code hasRole("ADMIN")}는 내부적으로 이 접두어를 붙여 비교한다. */
    public String authority() {
        return "ROLE_" + name();
    }
}
