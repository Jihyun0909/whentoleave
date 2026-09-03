package com.example.transit.domain;

import jakarta.persistence.Converter;

/** {@link SettlementStatus}를 문자열 컬럼으로 저장한다({@link EnumStringConverter} 주석 참고). */
@Converter(autoApply = true)
public class SettlementStatusConverter extends EnumStringConverter<SettlementStatus> {

    public SettlementStatusConverter() {
        super(SettlementStatus.class);
    }
}
