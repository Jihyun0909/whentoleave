package com.example.transit.service.client;

import com.example.transit.service.client.dto.OdsayPathResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * ODsay 무료 플랜의 일일 호출 한도를 실제로 소진한 적이 있어서, 같은 요청이 두 번 나가지
 * 않는지를 테스트로 고정한다. 특히 경로탐색은 목표 도착 시각과 무관하게 좌표로만 결정되므로,
 * 시각을 바꿔가며 재검색해도 호출이 늘면 안 된다.
 */
class OdsayClientCacheTest {

    private static final String PATH_JSON = """
            {"result":{"path":[{"pathType":1,"subPath":[]}]}}
            """;

    @Test
    void 같은_요청은_한_번만_호출하고_이후에는_캐시를_쓴다() throws Exception {
        OdsayClient client = new OdsayClient("https://api.odsay.test/v1/api", "test-key");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("/searchPubTransPathT")))
                .andRespond(withSuccess(PATH_JSON, APPLICATION_JSON));

        OdsayPathResponse first = client.searchPath(127.0, 37.6, 127.1, 37.5, SearchPathType.SUBWAY_ONLY);
        OdsayPathResponse second = client.searchPath(127.0, 37.6, 127.1, 37.5, SearchPathType.SUBWAY_ONLY);

        assertNotNull(first);
        assertEquals(first, second);
        server.verify(); // once()라서 두 번 호출됐으면 여기서 실패한다
    }

    @Test
    void 파라미터가_다르면_각각_호출한다() throws Exception {
        OdsayClient client = new OdsayClient("https://api.odsay.test/v1/api", "test-key");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("SearchPathType=1")))
                .andRespond(withSuccess(PATH_JSON, APPLICATION_JSON));
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("SearchPathType=2")))
                .andRespond(withSuccess(PATH_JSON, APPLICATION_JSON));

        client.searchPath(127.0, 37.6, 127.1, 37.5, SearchPathType.SUBWAY_ONLY);
        client.searchPath(127.0, 37.6, 127.1, 37.5, SearchPathType.BUS_ONLY);

        server.verify();
    }

    /** OdsayClient가 RestClient를 내부에서 만들기 때문에, 테스트에서는 그 자리에 목을 끼워 넣는다. */
    private MockRestServiceServer bindMockServer(OdsayClient client) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        Field field = OdsayClient.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(client, builder.build());
        return server;
    }
}
