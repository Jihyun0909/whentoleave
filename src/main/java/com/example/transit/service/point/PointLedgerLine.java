package com.example.transit.service.point;

import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.domain.ledger.LedgerTransactionType;

import java.time.LocalDateTime;

/**
 * 포인트 이력 한 줄(분개 + 그 트랜잭션 메타). 감사/이력 조회용 읽기 모델.
 *
 * @param direction CREDIT = 적립, DEBIT = 차감 (사용자 POINT 계정 기준)
 */
public record PointLedgerLine(
        EntryDirection direction,
        long amount,
        LedgerTransactionType type,
        String refType,
        Long refId,
        LocalDateTime at
) {
}
