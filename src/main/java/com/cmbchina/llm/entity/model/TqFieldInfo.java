package com.cmbchina.llm.entity.model;

import lombok.Data;

/**
 * 天启Excel字段信息
 *
 * @author IT006901
 * @date 2026-08-12 17:00:00
 */
@Data
public class TqFieldInfo {
    /**
     * 序号
     */
    private Integer serialNumber;

    /**
     * 字段名（大写）
     */
    private String fieldName;

    /**
     * 字段类型（VARCHAR/DECIMAL/TIMESTAMP等）
     */
    private String fieldType;

    /**
     * 精度（小数位，如DECIMAL(18,2)中的2）
     */
    private String decimalPrecision;

    /**
     * 精度（长度，如VARCHAR(255)中的255）
     */
    private String length;

    /**
     * 非空（true/false）
     */
    private String notNull;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 主键（true/false）
     */
    private String primaryKey;

    /**
     * 分区键（预留，未来维度信息）
     */
    private String partitionKey;

    /**
     * 字段描述（注释）
     */
    private String comment;
}