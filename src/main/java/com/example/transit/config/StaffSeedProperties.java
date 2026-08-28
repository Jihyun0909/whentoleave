package com.example.transit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시드 스태프 계정 자격증명. 실제 값은 환경변수로 주입한다(application.yml의 기본값은 로컬/데모용).
 */
@ConfigurationProperties(prefix = "app.staff")
public record StaffSeedProperties(
        String adminEmail,
        String adminPassword,
        String partnerAdminEmail,
        String partnerAdminPassword
) {
}
