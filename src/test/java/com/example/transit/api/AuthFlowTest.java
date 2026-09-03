package com.example.transit.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원가입 → 로그인 → 보호 자원 접근 → Refresh 회전 → 재사용 감지 → 로그아웃까지
 * 실제 SecurityFilterChain을 통과시켜 검증한다. Redis는 안 띄우고 인메모리 저장소로 돈다
 * (test application.yml: app.refresh-token.store=memory).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    private String uniqueEmail() {
        return "user-" + System.nanoTime() + "@test.com";
    }

    /** @return 로그인 응답 본문(JSON 문자열). {@code JsonPath.read(body, "$.accessToken")}로 꺼내 쓴다. */
    private String signupAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated());

        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 같은_이메일로_동시_가입해도_하나만_성공하고_나머지는_409() throws Exception {
        String email = uniqueEmail();
        int threads = 8;
        var statusCounts = new ConcurrentHashMap<Integer, AtomicInteger>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/v1/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"email":"%s","password":"password123"}""".formatted(email)))
                            .andReturn().getResponse().getStatus();
                    statusCounts.computeIfAbsent(status, k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    statusCounts.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(1, statusCounts.getOrDefault(201, new AtomicInteger()).get(), "성공은 정확히 1건");
        assertEquals(threads - 1, statusCounts.getOrDefault(409, new AtomicInteger()).get(), "나머지는 전부 409");
        assertEquals(0, statusCounts.getOrDefault(500, new AtomicInteger()).get(), "500은 없어야 함");
    }

    @Test
    void 가입한_이메일로_다시_가입하면_409() throws Exception {
        String email = uniqueEmail();
        signupAndLogin(email);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    @Test
    void 형식이_틀린_가입요청은_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 깨진_JSON_본문도_code_message_형태로_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void 비밀번호가_틀리면_401() throws Exception {
        String email = uniqueEmail();
        signupAndLogin(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 토큰_없이_보호자원에_접근하면_401_토큰이_있으면_통과() throws Exception {
        String accessToken = JsonPath.read(signupAndLogin(uniqueEmail()), "$.accessToken");

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void 권한이_부족하면_403_JSON() throws Exception {
        String accessToken = JsonPath.read(signupAndLogin(uniqueEmail()), "$.accessToken");

        // USER 토큰으로 ADMIN 전용 경로 접근 (핸들러가 없어도 Security 단계에서 먼저 막힌다)
        mockMvc.perform(get("/api/v1/admin/anything")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refresh는_회전되고_이전_refresh_token은_재사용_감지로_전체_폐기된다() throws Exception {
        String firstRefresh = JsonPath.read(signupAndLogin(uniqueEmail()), "$.refreshToken");

        String rotatedBody = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondRefresh = JsonPath.read(rotatedBody, "$.refreshToken");
        assertNotEquals(firstRefresh, secondRefresh);

        // 이전 토큰 재사용 → 재사용 감지 + 전체 폐기
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));

        // 전체 폐기됐으므로 방금 회전받은 토큰도 더는 안 먹는다
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(secondRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_그_refresh_token은_더는_못_쓴다() throws Exception {
        String refresh = JsonPath.read(signupAndLogin(uniqueEmail()), "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 경로조회_화면과_API는_인증_없이_계속_열려있다() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        // 파라미터가 없어 400이 나더라도 최소한 401/403은 아니어야 한다(= 공개 엔드포인트)
        int status = mockMvc.perform(get("/api/v1/last-departure")).andReturn().getResponse().getStatus();
        assertEquals(true, status != 401 && status != 403);
    }
}
