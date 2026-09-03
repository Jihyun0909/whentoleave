package com.example.transit.domain;

import jakarta.persistence.Converter;

/** {@link Role}을 문자열 컬럼으로 저장한다({@link EnumStringConverter} 주석 참고). */
@Converter(autoApply = true)
public class RoleConverter extends EnumStringConverter<Role> {

    public RoleConverter() {
        super(Role.class);
    }
}
