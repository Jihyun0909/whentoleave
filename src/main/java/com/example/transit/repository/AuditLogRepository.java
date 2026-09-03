package com.example.transit.repository;

import com.example.transit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** 불변 테이블. save와 조회만 노출한다. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog auditLog);

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);
}
