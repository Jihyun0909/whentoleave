package com.example.transit.domain.ledger;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link AccountOwnerType}를 문자열 컬럼으로 저장한다({@link AccountKindConverter} 주석 참고). */
@Converter(autoApply = true)
public class AccountOwnerTypeConverter implements AttributeConverter<AccountOwnerType, String> {

    @Override
    public String convertToDatabaseColumn(AccountOwnerType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public AccountOwnerType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AccountOwnerType.valueOf(dbData);
    }
}
