package com.example.transit.domain.ledger;

/** 원장 계정의 소유 주체. */
public enum AccountOwnerType {
    /** B2C 사용자 (owner_id = app_user.id) */
    USER,
    /** B2B 제휴사 (owner_id = partner.id) — PR C에서 사용 */
    PARTNER,
    /** 플랫폼 내부 계정 (owner_id = null) */
    SYSTEM
}
