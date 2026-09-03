package com.example.transit.config;

import com.example.transit.domain.AppUser;
import com.example.transit.domain.Partner;
import com.example.transit.domain.Role;
import com.example.transit.repository.AppUserRepository;
import com.example.transit.repository.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 운영자(ADMIN)와 제휴사 관리자(PARTNER_ADMIN) 계정을 기동 시 시드한다. 공개 회원가입은 B2C
 * (ROLE_USER)만 가능하므로 이 두 역할은 여기서만 만들어진다.
 * <p>
 * 자격증명은 환경변수로 주입한다(운영). 기본값은 로컬/데모 전용이므로 로그로 경고한다 -
 * JWT_SECRET과 같은 방식.
 */
@Component
@Order(10)
public class StaffSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffSeedInitializer.class);

    private final AppUserRepository users;
    private final PartnerRepository partners;
    private final PasswordEncoder passwordEncoder;
    private final StaffSeedProperties props;

    public StaffSeedInitializer(AppUserRepository users, PartnerRepository partners,
                                PasswordEncoder passwordEncoder, StaffSeedProperties props) {
        this.users = users;
        this.partners = partners;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(props.adminEmail(), props.adminPassword(), Role.ADMIN, null);

        Long firstActivePartnerId = partners.findByActiveTrue().stream()
                .findFirst().map(Partner::getId).orElse(null);
        if (firstActivePartnerId != null) {
            seed(props.partnerAdminEmail(), props.partnerAdminPassword(), Role.PARTNER_ADMIN, firstActivePartnerId);
        }
    }

    private void seed(String email, String rawPassword, Role role, Long partnerId) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmail(normalized)) {
            return;
        }
        users.save(AppUser.newStaff(normalized, passwordEncoder.encode(rawPassword), role, partnerId));
        log.warn("{} 시드 계정 생성: {} (운영에서는 환경변수로 자격증명을 덮어쓰세요)", role, normalized);
    }
}
