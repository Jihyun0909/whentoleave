package com.example.transit.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영자 전용 엔드포인트 + 전송 계층 에러의 JSON 형태({@code {code, message}}) 검증.
 * 운영자 계정은 {@code StaffSeedInitializer}가 시드한다(test yml의 app.staff.*).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    private String adminBearer() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@test.local","password":"admin-test-password"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    @Test
    void 운영자_조회_엔드포인트_200() throws Exception {
        String auth = adminBearer();
        mockMvc.perform(get("/api/v1/admin/partners").header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/settlements").header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", auth)).andExpect(status().isOk());
    }

    @Test
    void 제휴사_활성화_토글() throws Exception {
        String auth = adminBearer();
        String body = mockMvc.perform(get("/api/v1/admin/partners").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString();
        int id = ((Number) JsonPath.read(body, "$[0].id")).intValue();

        mockMvc.perform(post("/api/v1/admin/partners/" + id + "/deactivate").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/partners/" + id + "/activate").header("Authorization", auth))
                .andExpect(status().isOk());
    }

    @Test
    void 없는_제휴사_비활성화는_404_NOT_FOUND() throws Exception {
        mockMvc.perform(post("/api/v1/admin/partners/99999999/deactivate").header("Authorization", adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 미래_날짜_정산_실행은_400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/settlements/run").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementDate":"2999-01-01"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_REQUEST"));
    }

    @Test
    void 지원하지_않는_ContentType은_415_JSON() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
