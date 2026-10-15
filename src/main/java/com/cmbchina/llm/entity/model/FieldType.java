package com.cmbchina.llm.entity.model;

/**
 * 字段类型枚举
 * 根据JDBC type数字映射到Java类型名称
 *
 * @author IT006901
 * @date 2026-08-13 14:30:00
 */
public enum FieldType {

    /**
     * DECIMAL类型，JDBC type=3
     */
    DECIMAL(3, "BigDecimal"),

    /**
     * INTEGER类型，JDBC type=4
     */
    INTEGER(4, "Integer"),

    /**
     * VARCHAR类型，JDBC type=12
     */
    VARCHAR(12, "String"),

    /**
     * TIMESTAMP类型，JDBC type=93
     */
    TIMESTAMP(93, "Timestamp"),

    /**
     * BIGINT类型，JDBC type=-5
     */
    BIGINT(-5, "Long"),

    /**
     * BOOLEAN类型，JDBC type=16
     */
    BOOLEAN(16, "Boolean");

    /**
     * JDBC type数字
     */
    private final int jdbcType;

    /**
     * Java类型名称
     */
    private final String javaTypeName;

    FieldType(int jdbcType, String javaTypeName) {
        this.jdbcType = jdbcType;
        this.javaTypeName = javaTypeName;
    }

    /**
     * 获取JDBC type数字
     *
     * @return JDBC type数字
     */
    public int getJdbcType() {
        return jdbcType;
    }

    /**
     * 获取Java类型名称
     *
     * @return Java类型名称
     */
    public String getJavaTypeName() {
        return javaTypeName;
    }

    /**
     * 根据JDBC type数字获取对应的枚举值
     *
     * @param jdbcType JDBC type数字
     * @return 对应的枚举值，未匹配则返回VARCHAR
     */
    public static FieldType fromJdbcType(Integer jdbcType) {
        if (jdbcType == null) {
            return VARCHAR;
        }
        for (FieldType fieldType : values()) {
            if (fieldType.jdbcType == jdbcType) {
                return fieldType;
            }
        }
        return VARCHAR;
    }

    /**
     * 根据JDBC type数字获取Java类型名称
     *
     * @param jdbcType JDBC type数字
     * @return Java类型名称，未匹配则返回"String"
     */
    public static String getTypeNameByJdbcType(Integer jdbcType) {
        return fromJdbcType(jdbcType).getJavaTypeName();
    }
}