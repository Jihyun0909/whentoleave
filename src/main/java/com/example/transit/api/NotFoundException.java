package com.example.transit.api;

/** 경로로 지목한 리소스가 없을 때 → 404. {@code ApiExceptionHandler}가 {@code {code:"NOT_FOUND"}}로 응답. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + "를 찾을 수 없습니다 (id: " + id + ")");
    }
}
