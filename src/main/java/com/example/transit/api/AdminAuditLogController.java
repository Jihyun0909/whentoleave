package com.example.transit.api;

import com.example.transit.api.dto.AuditLogResponse;
import com.example.transit.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 운영자 전용 감사 로그 조회. */
@RestController
public class AdminAuditLogController {

    private final AuditLogRepository auditLogs;

    public AdminAuditLogController(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping("/api/v1/admin/audit-logs")
    public List<AuditLogResponse> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return auditLogs.findAllByOrderByIdDesc(PageRequest.of(page, size))
                .map(AuditLogResponse::from).getContent();
    }
}
