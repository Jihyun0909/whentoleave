package com.example.transit.web;

import com.example.transit.service.StationCandidate;
import com.example.transit.service.StationResolution;
import com.example.transit.service.StationSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Spring MVC + Thymeleaf 렌더링 파이프라인을 통해 화면을 검증한다.
 * ODsay 네트워크 호출 없이 검증하기 위해 StationSearchService만 페이크로 교체한다
 * (Mockito 없이, 이 프로젝트에서 이미 쓰고 있는 "필요한 메서드만 오버라이드하는 페이크" 패턴).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LastDepartureViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class FakeStationSearchConfig {
        @Bean
        @Primary
        StationSearchService fakeStationSearchService() {
            return new StationSearchService(null, null) {
                @Override
                public StationResolution resolve(String stationName) {
                    if ("강남".equals(stationName)) {
                        return new StationResolution.Ambiguous(List.of(
                                new StationCandidate("강남", "수도권 2호선", 127.027619, 37.497952),
                                new StationCandidate("강남", "수도권 신분당선", 127.028358, 37.496373)
                        ));
                    }
                    return new StationResolution.NotFound(stationName);
                }
            };
        }
    }

    @Test
    void 역_이름이_여러_후보에_걸치면_각_후보로_가는_링크를_보여준다() throws Exception {
        mockMvc.perform(get("/").param("originName", "강남").param("destName", "존재안함"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("originX=127.027619")))
                .andExpect(content().string(containsString("수도권 2호선")))
                .andExpect(content().string(containsString("수도권 신분당선")))
                .andExpect(content().string(containsString("존재안함")));
    }

    @Test
    void 초기_화면에는_검색_폼만_보인다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("검색")))
                .andExpect(content().string(not(containsString("id=\"targetArrivalTime\""))));
    }

    @Test
    void arrival_모드면_목표_도착시간_입력_필드가_보인다() throws Exception {
        mockMvc.perform(get("/").param("mode", "arrival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"targetArrivalTime\"")));
    }
}
