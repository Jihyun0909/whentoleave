package com.example.transit.api.dto;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.AuditLog;

import java.time.LocalDateTime;

public record AuditLogResponse(
        long id,
        AuditEvent event,
        Long actorUserId,
        String refType,
        Long refId,
        String detail,
        LocalDateTime at
) {
    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getEvent(), a.getActorUserId(),
                a.getRefType(), a.getRefId(), a.getDetail(), a.getCreatedAt());
    }
}
