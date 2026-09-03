package com.example.transit.domain;

import jakarta.persistence.Converter;

/** {@link DayType}을 문자열 컬럼으로 저장한다({@link EnumStringConverter} 주석 참고). */
@Converter(autoApply = true)
public class DayTypeConverter extends EnumStringConverter<DayType> {

    public DayTypeConverter() {
        super(DayType.class);
    }
}
