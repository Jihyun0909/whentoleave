package com.example.transit.security;

import com.example.transit.service.auth.AuthenticatedUser;
import com.example.transit.service.auth.InvalidTokenException;
import com.example.transit.service.auth.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * {@code Authorization: Bearer <access token>}를 검증해서 {@link SecurityContextHolder}에
 * 인증을 채운다. 주체(principal)로는 {@link AuthenticatedUser}를 넣어, 컨트롤러가
 * {@code @AuthenticationPrincipal AuthenticatedUser}로 바로 받게 한다.
 * <p>
 * 토큰이 없거나 유효하지 않으면 여기서 401을 내지 않는다 - 인증 없이 통과시키고,
 * 보호된 자원이면 Security의 EntryPoint가 401을 낸다(공개 엔드포인트는 그대로 동작).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtProvider.AccessTokenClaims claims = jwtProvider.parseAccessToken(token);
                AuthenticatedUser principal = new AuthenticatedUser(
                        claims.userId(), claims.role(), claims.partnerId());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(claims.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidTokenException e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
