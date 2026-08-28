package com.example.transit.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가상 택시 이용 완료 → 결제 → 5% 페이백 적립 → 잔액·이력 반영까지 실제 필터체인·트랜잭션으로 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RidePaybackFlowTest {

    @Autowired
    private MockMvc mockMvc;

    private String bearer() throws Exception {
        String email = "rider-" + System.nanoTime() + "@test.com";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"password123"}""".formatted(email)));
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private long anActivePartnerId(String auth) throws Exception {
        String body = mockMvc.perform(get("/api/v1/partners").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$[0].id")).longValue();
    }

    private long createRide(String auth, long partnerId, long fare) throws Exception {
        String body = mockMvc.perform(post("/api/v1/rides").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerId":%d,"origin":"강남역","destination":"수유역","fareAmount":%d}"""
                                .formatted(partnerId, fare)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions complete(String auth, long rideId, long pointToUse) throws Exception {
        mockMvc.perform(post("/api/v1/rides/" + rideId + "/start").header("Authorization", auth))
                .andExpect(status().isOk());
        return mockMvc.perform(post("/api/v1/rides/" + rideId + "/complete").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pointToUse":%d}""".formatted(pointToUse)));
    }

    @Test
    void 이용_완료시_요금의_5퍼센트가_적립되고_잔액에_반영된다() throws Exception {
        String auth = bearer();
        long partnerId = anActivePartnerId(auth);
        long rideId = createRide(auth, partnerId, 20_000);

        complete(auth, rideId, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.pointEarned").value(1000))
                .andExpect(jsonPath("$.payment.pointUsed").value(0))
                .andExpect(jsonPath("$.payment.cashAmount").value(20000));

        mockMvc.perform(get("/api/v1/points").header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(1000));
        mockMvc.perform(get("/api/v1/points/history").header("Authorization", auth))
                .andExpect(jsonPath("$[0].kind").value("EARN"))
                .andExpect(jsonPath("$[0].amount").value(1000));
    }

    @Test
    void 적립된_포인트를_다음_이용_결제에_선불로_쓸_수_있다() throws Exception {
        String auth = bearer();
        long partnerId = anActivePartnerId(auth);

        // 1회차: 30,000원 → 1,500 적립
        complete(auth, createRide(auth, partnerId, 30_000), 0).andExpect(status().isOk());

        // 2회차: 10,000원 결제에 1,000 포인트 사용 → 남은 포인트 500 + 이번 적립 500 = 1,000
        long ride2 = createRide(auth, partnerId, 10_000);
        complete(auth, ride2, 1_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.pointUsed").value(1000))
                .andExpect(jsonPath("$.payment.cashAmount").value(9000))
                .andExpect(jsonPath("$.payment.pointEarned").value(500));

        mockMvc.perform(get("/api/v1/points").header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(1000));
    }

    @Test
    void 이미_완료된_이용을_다시_완료하면_409() throws Exception {
        String auth = bearer();
        long rideId = createRide(auth, anActivePartnerId(auth), 12_000);
        complete(auth, rideId, 0).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/rides/" + rideId + "/complete").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"pointToUse":0}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    @Test
    void 보유한_것보다_많은_포인트를_쓰려_하면_결제_실패하고_이용도_완료되지_않는다() throws Exception {
        String auth = bearer();
        long partnerId = anActivePartnerId(auth);
        long rideId = createRide(auth, partnerId, 10_000);

        complete(auth, rideId, 5_000)  // 잔액 0인데 5,000 사용 시도
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_POINT"));

        // 이용은 여전히 IN_PROGRESS (완료 롤백됨)
        mockMvc.perform(get("/api/v1/rides/" + rideId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void 남의_이용은_건드릴_수_없다() throws Exception {
        String owner = bearer();
        long rideId = createRide(owner, anActivePartnerId(owner), 10_000);

        String stranger = bearer();
        mockMvc.perform(post("/api/v1/rides/" + rideId + "/start").header("Authorization", stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void 없는_제휴사로는_이용을_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/rides").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"partnerId":999999,"origin":"A","destination":"B","fareAmount":10000}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARTNER_NOT_AVAILABLE"));
    }
}
