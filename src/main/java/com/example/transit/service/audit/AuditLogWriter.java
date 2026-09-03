package com.example.transit.service.audit;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.AuditLog;
import com.example.transit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그를 쓴다. 본 흐름(포인트 차감·정산 등)이 실패해서 롤백되더라도 "시도했다"는 기록은
 * 남아야 하므로 {@link Propagation#REQUIRES_NEW}로 별도 커밋한다.
 * <p>
 * 주의: 테스트 메서드에 {@code @Transactional}을 걸면(롤백 목적) 이 로그는 그와 무관하게
 * 커밋되어 테스트 간에 남는다. 원장/포인트 테스트는 트랜잭션 롤백에 의존하지 않는다.
 */
@Service
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    public AuditLogWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event, Long actorUserId, String refType, Long refId, String detail) {
        auditLogRepository.save(new AuditLog(actorUserId, event, refType, refId, detail));
    }
}
