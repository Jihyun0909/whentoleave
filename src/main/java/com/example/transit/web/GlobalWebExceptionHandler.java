package com.example.transit.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 쿼리스트링을 사용자가 직접 고칠 수 있어서(예: targetDate=not-a-date, originX=abc),
 * 형식이 안 맞는 값이 오면 스프링이 파라미터 바인딩 단계에서 예외를 던지고, 컨트롤러
 * 코드는 실행조차 안 돼서 영어로 된 기본 Whitelabel 에러 페이지가 그대로 노출된다.
 * 잘못된 파라미터를 무시하고 검색 전 화면으로 되돌려 보내는 게 사용자에게 더 낫다.
 */
@ControllerAdvice
public class GlobalWebExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String onBadRequestParam() {
        return "redirect:/";
    }
}
