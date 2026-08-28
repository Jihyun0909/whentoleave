package com.example.transit.config;

import com.example.transit.security.JwtAuthenticationFilter;
import com.example.transit.security.RestAccessDeniedHandler;
import com.example.transit.security.RestAuthenticationEntryPoint;
import com.example.transit.service.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT 인증. 세션·폼로그인·HTTP Basic·CSRF 토큰을 전부 끄고
 * ({@code Authorization: Bearer} 헤더만 쓴다), 기존 공개 화면/조회 API는 그대로 열어둔다.
 * <p>
 * 경로조회 관련 엔드포인트({@code /}, {@code /api/v1/last-departure}, {@code /api/v1/stations**})는
 * 인증 없이 접근 가능해야 한다 - 이 기능은 로그인과 무관하게 계속 동작한다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, StaffSeedProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 정적 리소스 + 막차 조회 화면
                        .requestMatchers("/", "/error", "/favicon.ico",
                                "/css/**", "/js/**", "/img/**").permitAll()
                        // 경로/막차/역 조회 API - 로그인 불필요
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/last-departure",
                                "/api/v1/stations",
                                "/api/v1/stations/**").permitAll()
                        // 인증 엔드포인트
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 권한별 보호 영역
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/partner/**").hasRole("PARTNER_ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
