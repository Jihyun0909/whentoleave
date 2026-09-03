package com.example.transit.domain;

import jakarta.persistence.Converter;

/** {@link RideStatus}를 문자열 컬럼으로 저장한다({@link EnumStringConverter} 주석 참고). */
@Converter(autoApply = true)
public class RideStatusConverter extends EnumStringConverter<RideStatus> {

    public RideStatusConverter() {
        super(RideStatus.class);
    }
}
