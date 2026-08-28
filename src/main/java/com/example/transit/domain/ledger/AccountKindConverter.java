package com.example.transit.domain.ledger;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link AccountKind}를 문자열 컬럼으로 저장한다. {@code @Enumerated(STRING)} 대신 컨버터를 쓰는
 * 이유: {@code @Enumerated}는 Hibernate가 {@code check (kind in (...))} 제약을 생성하는데,
 * 이 enum은 PR마다 값이 늘어나고({@code ddl-auto=update}는 기존 check 제약을 갱신하지 못한다)
 * 그러면 새 값 INSERT가 제약 위반으로 실패한다. 컨버터로 두면 그냥 varchar라 제약이 안 생긴다.
 */
@Converter(autoApply = true)
public class AccountKindConverter implements AttributeConverter<AccountKind, String> {

    @Override
    public String convertToDatabaseColumn(AccountKind attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public AccountKind convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AccountKind.valueOf(dbData);
    }
}
