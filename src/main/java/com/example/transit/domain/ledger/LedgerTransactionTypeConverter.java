package com.example.transit.domain.ledger;

import com.example.transit.domain.EnumStringConverter;
import jakarta.persistence.Converter;

/** {@link LedgerTransactionType}를 문자열 컬럼으로 저장한다({@link EnumStringConverter} 주석 참고). */
@Converter(autoApply = true)
public class LedgerTransactionTypeConverter extends EnumStringConverter<LedgerTransactionType> {

    public LedgerTransactionTypeConverter() {
        super(LedgerTransactionType.class);
    }
}
