package com.example.transit.domain;

import jakarta.persistence.AttributeConverter;

/**
 * enum을 그 {@code name()} 문자열로 저장하는 JPA 컨버터의 공통 베이스.
 * <p>
 * {@code @Enumerated(EnumType.STRING)} 대신 컨버터를 쓰는 이유: {@code @Enumerated}는 Hibernate가
 * {@code check (col in ('A','B',...))} 제약을 자동 생성하는데, 이 프로젝트는 {@code ddl-auto=update}라
 * enum에 값을 추가해도 기존 check 제약이 갱신되지 않는다 → 새 값 INSERT가 제약 위반으로 깨진다.
 * 컨버터로 두면 그냥 {@code varchar}라 check 제약이 안 생겨서 값 추가가 자유롭다.
 * <p>
 * 하위 클래스는 {@code @Converter(autoApply = true)}를 붙이고 기본 생성자에서 enum 타입을 넘긴다.
 */
public abstract class EnumStringConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> enumType;

    protected EnumStringConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Enum.valueOf(enumType, dbData);
    }
}
