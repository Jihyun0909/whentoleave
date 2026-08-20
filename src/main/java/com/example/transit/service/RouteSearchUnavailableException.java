package com.example.transit.service;

/**
 * 경로탐색 API가 응답은 했지만 결과 대신 오류를 준 경우(일일 호출 한도 초과, 서비스 점검 등).
 * <p>
 * "경로가 없다"와 반드시 구분해야 한다 - ODsay는 실패해도 HTTP 200에 error 필드만 담아 주기
 * 때문에, 이걸 구분하지 않으면 API 한도 초과가 "대중교통 운행이 종료되어 안내가 불가능합니다"로
 * 안내된다(실사용 중 실제로 발생). 사용자가 기다려도 소용없는 상황인데 운행 종료로 오해하게 된다.
 */
public class RouteSearchUnavailableException extends RuntimeException {

    public RouteSearchUnavailableException(String message) {
        super(message);
    }
}
