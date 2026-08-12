package com.example.transit.service;

import com.example.transit.service.client.KakaoLocalClient;
import com.example.transit.service.client.dto.KakaoAddressSearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 주소 문자열을 좌표로 바꾼다(카카오 로컬 API 지오코딩). 역 이름 검색이 실패했을 때의
 * 폴백으로 쓰인다 (이슈 #7). ODsay의 경로탐색(searchPubTransPathT)이 임의의 좌표를
 * 받아 가까운 역까지의 도보 구간까지 알아서 계산해주므로, 여기서는 "가장 가까운 역"을
 * 직접 찾을 필요 없이 좌표만 넘기면 된다.
 */
@Service
public class AddressSearchService {

    private final KakaoLocalClient kakaoLocalClient;

    public AddressSearchService(KakaoLocalClient kakaoLocalClient) {
        this.kakaoLocalClient = kakaoLocalClient;
    }

    /**
     * 지번/도로명 주소 형태가 아니면 못 찾을 수 있다 (건물명 등 키워드 검색은 지원 범위 밖).
     * 여러 건이 나와도 카카오가 가장 정확하다고 판단한 첫 번째 결과만 쓴다.
     */
    public Optional<StationResolution.Resolved> resolve(String address) {
        KakaoAddressSearchResponse response = kakaoLocalClient.searchAddress(address);
        List<KakaoAddressSearchResponse.Document> documents = response.documents();
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        KakaoAddressSearchResponse.Document first = documents.get(0);
        return Optional.of(new StationResolution.Resolved(
                Double.parseDouble(first.x()), Double.parseDouble(first.y()), first.addressName()));
    }
}
