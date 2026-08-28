package com.example.transit.api.dto.point;

import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.service.point.PointLedgerLine;

import java.time.LocalDateTime;

/**
 * @param kind EARN(적립) 또는 SPEND(차감) — 사용자 관점 표기
 */
public record PointHistoryItem(
        String kind,
        long amount,
        String type,
        String refType,
        Long refId,
        LocalDateTime at
) {
    public static PointHistoryItem from(PointLedgerLine line) {
        String kind = line.direction() == EntryDirection.CREDIT ? "EARN" : "SPEND";
        return new PointHistoryItem(kind, line.amount(), line.type().name(),
                line.refType(), line.refId(), line.at());
    }
}
