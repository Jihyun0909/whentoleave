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
            return new StationSearchService(null, null, null) {
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

    /**
     * 후보가 여러 개면 라디오로 고르는 폼을 보여준다. 예전에는 후보마다 링크였는데, 출발지·도착지가
     * 둘 다 모호하면 한쪽씩 고를 때마다 페이지가 새로 떠서 한 폼에서 다 고르도록 바꿨다.
     */
    @Test
    void 역_이름이_여러_후보에_걸치면_후보를_고르는_폼을_보여준다() throws Exception {
        mockMvc.perform(get("/").param("originName", "강남").param("destName", "존재안함"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"originPoint\"")))
                .andExpect(content().string(containsString("127.027619,37.497952")))
                // "수도권" 접두어는 화면에 안 보여준다 (수도권 전용 서비스라 정보를 더하지 않음)
                .andExpect(content().string(containsString("2호선")))
                .andExpect(content().string(not(containsString("수도권 2호선"))))
                .andExpect(content().string(containsString("신분당선")))
                .andExpect(content().string(containsString("존재안함")));
    }

    /** 폼에서 고른 "x,y" 한 값이 좌표로 풀려서, 이름 재검색 없이 그대로 계산에 쓰여야 한다. */
    @Test
    void 후보_선택값으로_좌표가_확정되면_이름으로_다시_찾지_않는다() throws Exception {
        mockMvc.perform(get("/")
                        .param("originName", "강남")
                        .param("destName", "강남")
                        .param("originPoint", "127.027619,37.497952")
                        .param("destPoint", "127.028358,37.496373"))
                .andExpect(status().isOk())
                // 좌표가 확정됐으므로 후보 선택 폼이 더 이상 나오면 안 된다
                .andExpect(content().string(not(containsString("name=\"originPoint\""))));
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
