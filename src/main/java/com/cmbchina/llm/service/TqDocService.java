package com.cmbchina.llm.service;

import com.cmbchina.llm.config.DimensionConfig;
import com.cmbchina.llm.entity.model.FieldType;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.entity.model.RequirementDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 天启文档生成服务
 * 基于Iteration1ResultDTO和RequirementDoc生成天启系统使用的Markdown文档
 *
 * @author IT006901
 * @date 2026-08-13 00:10:00
 */
@Slf4j
@Service
public class TqDocService {

    @Autowired
    private DimensionConfig dimensionConfig;

    /**
     * 输出目录
     */
    private static final String OUTPUT_DIR = "output/";

    /**
     * 时间类型默认日期
     */
    private static final String DEFAULT_DATE = "2001-01-01 00:00:00.001";

    /**
     * 固定追加字段：入库时间
     */
    private static final String FIXED_FIELD_DD_APDATE = "ddApdate";

    /**
     * 固定追加字段：序号
     */
    private static final String FIXED_FIELD_SEQ_NO = "seqNo";

    /**
     * 固定追加字段：事件源类型
     */
    private static final String FIXED_FIELD_DS_TYPE = "dsType";

    /**
     * 根据Iteration1ResultDTO生成天启Markdown文档（兼容旧接口）
     * 只有Iteration1ResultDTO，没有RequirementDoc时使用
     * 事件源ID取tableName，事件源名称为空，无计算集
     *
     * @param iteration1Result 大模型解析结果
     * @param tableName 表名（作为事件源ID）
     * @return 生成的Markdown文件路径
     */
    public String generateTqDoc(Iteration1ResultDTO iteration1Result, String tableName) {
        try {
            if (iteration1Result == null || iteration1Result.getEventPost() == null) {
                log.warn("Iteration1ResultDTO或eventPost为空");
                return null;
            }

            // 构造一个简单的RequirementDoc
            RequirementDoc doc = new RequirementDoc();
            RequirementDoc.BasicInfo basicInfo = new RequirementDoc.BasicInfo();
            basicInfo.setEventSourceId(tableName);
            basicInfo.setEventSourceName(tableName);
            doc.setBasicInfo(basicInfo);

            return generateTqDoc(iteration1Result, doc);

        } catch (Exception e) {
            log.error("天启文档生成失败，表名：{}", tableName, e);
            return null;
        }
    }



    /**
     * 根据Iteration1ResultDTO和RequirementDoc生成天启Markdown文档
     * 只生成一份MD文档，内容包含：
     * 1. 事件源ID、事件源名称（取输入文档的事件源名称）
     * 2. 输入参数格式
     * 3. 输入参数默认值配置（dsType默认值填写事件源ID）
     * 4. 计算集（根据维度生成，有几个维度就有几个计算集）
     *
     * @param iteration1Result 大模型解析结果
     * @param doc 需求文档（提供事件源ID、事件源名称、维度信息等）
     * @return 生成的Markdown文件路径
     */
    public String generateTqDoc(Iteration1ResultDTO iteration1Result, RequirementDoc doc) {
        try {
            if (iteration1Result == null || iteration1Result.getEventPost() == null) {
                log.warn("Iteration1ResultDTO或eventPost为空");
                return null;
            }
            if (doc == null || doc.getBasicInfo() == null) {
                log.warn("RequirementDoc或basicInfo为空");
                return null;
            }

            // 生成Markdown内容
            String markdownContent = generateMarkdownContent(iteration1Result, doc);

            // 生成文件名（使用事件源ID）
            String eventSourceId = doc.getBasicInfo().getEventSourceId();
            String fileName = eventSourceId + "_天启文档.md";
            String filePath = OUTPUT_DIR + fileName;

            // 确保输出目录存在
            File dir = new File(OUTPUT_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 写入文件
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8)) {
                writer.write(markdownContent);
            }

            log.info("天启文档生成成功，事件源ID：{}，文件路径：{}", eventSourceId, filePath);
            return filePath;

        } catch (Exception e) {
            log.error("天启文档生成失败：{}", e);
            return null;
        }
    }
    /**
     * 生成Markdown内容
     *
     * @param iteration1Result 大模型解析结果
     * @param doc 需求文档
     * @return Markdown内容
     */
    private String generateMarkdownContent(Iteration1ResultDTO iteration1Result, RequirementDoc doc) {
        StringBuilder sb = new StringBuilder();

        // 事件源ID
        String eventSourceId = doc.getBasicInfo().getEventSourceId();
        String eventSourceName = doc.getBasicInfo().getEventSourceName();
        String dataRetentionTime = doc.getBasicInfo().getDataRetentionTime();

        // 事件源ID
        sb.append("事件源ID：").append(eventSourceId).append("\n\n");

        // 事件源名称（取输入文档的事件源名称）
        sb.append("事件源名称：").append(eventSourceName != null ? eventSourceName : "").append("\n\n");

        // 输入参数格式
        sb.append("输入参数格式：\n");
        sb.append("```json\n");
        sb.append(generateInputParamFormat(iteration1Result));
        sb.append("\n```\n\n");

        // 输入参数默认值配置（dsType默认值填写事件源ID）
        sb.append("输入参数默认值配置：\n");
        sb.append("```json\n");
        sb.append(generateDefaultValueConfig(iteration1Result, eventSourceId));
        sb.append("\n```\n\n");

        // 计算集（根据维度生成，有几个维度就有几个计算集）
        sb.append("计算集：\n");
        sb.append(generateCalcSets(doc, doc.getEventSourceFields(), eventSourceId, eventSourceName, dataRetentionTime));

        return sb.toString();
    }

    /**
     * 生成输入参数格式JSON
     * 格式：{ "fieldName": "description-Type" }
     * 固定追加字段：ddApdate(入库时间-Timestamp)、seqNo(序号-String)、dsType(事件源类型-String)
     *
     * @param iteration1Result 大模型解析结果
     * @return 输入参数格式JSON字符串
     */
    private String generateInputParamFormat(Iteration1ResultDTO iteration1Result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        List<Iteration1ResultDTO.EventField> eventFields = iteration1Result.getEventPost().getEventFields();

        // 遍历eventFields
        for (int i = 0; i < eventFields.size(); i++) {
            Iteration1ResultDTO.EventField field = eventFields.get(i);
            String typeName = FieldType.getTypeNameByJdbcType(field.getType());

            sb.append("  \"").append(field.getName()).append("\": \"").append(field.getDescription()).append("-").append(typeName);
            if (i < eventFields.size() - 1) {
                sb.append("\",\n");
            } else {
                sb.append("\",\n");
            }
        }

        // 固定追加字段
        sb.append("  \"").append(FIXED_FIELD_DD_APDATE).append("\": \"").append("入库时间-").append(FieldType.TIMESTAMP.getJavaTypeName()).append("\",\n");
        sb.append("  \"").append(FIXED_FIELD_SEQ_NO).append("\": \"").append("序号-").append(FieldType.VARCHAR.getJavaTypeName()).append("\",\n");
        sb.append("  \"").append(FIXED_FIELD_DS_TYPE).append("\": \"").append("事件源类型-").append(FieldType.VARCHAR.getJavaTypeName()).append("\"\n");

        sb.append("}");
        return sb.toString();
    }
    /**
     * 生成输入参数默认值配置JSON
     * 格式：{ "fieldName": "defaultValue" }
     * 时间类型的字段设置默认日期
     * 固定追加字段：ddApdate(默认日期)、seqNo(空)、dsType(事件源ID)
     *
     * @param iteration1Result 大模型解析结果
     * @param eventSourceId 事件源ID（dsType默认值）
     * @return 默认值配置JSON字符串
     */
    private String generateDefaultValueConfig(Iteration1ResultDTO iteration1Result, String eventSourceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        List<Iteration1ResultDTO.EventField> eventFields = iteration1Result.getEventPost().getEventFields();

        // 遍历eventFields
        for (int i = 0; i < eventFields.size(); i++) {
            Iteration1ResultDTO.EventField field = eventFields.get(i);
            String typeName = FieldType.getTypeNameByJdbcType(field.getType());
            String defaultValue = getDefaultValue(field, typeName);

            sb.append("  \"").append(field.getName()).append("\": \"").append(defaultValue).append("\",\n");
        }

        // 固定追加字段默认值
        sb.append("  \"").append(FIXED_FIELD_DD_APDATE).append("\": \"").append(DEFAULT_DATE).append("\",\n");
        sb.append("  \"").append(FIXED_FIELD_SEQ_NO).append("\": \"\",\n");
        // dsType默认值填写事件源ID
        sb.append("  \"").append(FIXED_FIELD_DS_TYPE).append("\": \"").append(eventSourceId != null ? eventSourceId : "").append("\"\n");

        sb.append("}");
        return sb.toString();
    }

    /**
     * 计算集（根据维度生成）
     * 有几个维度就有几个计算集，每个计算集包含：
     * - 计算集ID：rv_model_ + 事件源ID + 分区字段
     * - 计算集名称：事件源名称 + 维度名称 + "计算集"
     * - 维度：匹配维度配置后的维度ID
     * - 分区字段映射：取输入的分区字段
     * - 数据表：取输入数据
     * - 最长保留时间：取输入数据
     *
     * @param doc 需求文档
     * @param eventSourceFields 事件源字段列表（用于获取分区字段的中文含义）
     * @param eventSourceId 事件源ID
     * @param eventSourceName 事件源名称
     * @param dataRetentionTime 最长保留时间
     * @return 计算集Markdown内容
     */
    private String generateCalcSets(RequirementDoc doc, List<RequirementDoc.EventSourceField> eventSourceFields,
                                    String eventSourceId, String eventSourceName, String dataRetentionTime) {
        StringBuilder sb = new StringBuilder();

        List<RequirementDoc.DimensionInfo> dimensionInfos = doc.getDimensionInfos();
        if (dimensionInfos == null || dimensionInfos.isEmpty()) {
            sb.append("（无维度信息）\n");
            return sb.toString();
        }

        // 构建维度配置映射（维度ID -> 维度名称）
        Map<String, String> dimensionMap = new HashMap<>();
        if (dimensionConfig != null && dimensionConfig.getConfig() != null) {
            for (DimensionConfig.DimensionItem item : dimensionConfig.getConfig()) {
                if (item.getId() != null && item.getName() != null) {
                    dimensionMap.put(item.getId(), item.getName());
                }
            }
        }

        // 构建事件源字段映射（字段名 -> 字段描述）
        Map<String, String> eventSourceFieldDescMap = new HashMap<>();
        if (eventSourceFields != null) {
            for (RequirementDoc.EventSourceField field : eventSourceFields) {
                if (field.getName() != null && field.getDescription() != null) {
                    eventSourceFieldDescMap.put(field.getName().toLowerCase(), field.getDescription());
                }
            }
        }

        for (RequirementDoc.DimensionInfo dimension : dimensionInfos) {
            // 计算集ID：rv_model_ + 事件源ID + 分区字段
            String partitionKey = dimension.getPartitionKey();
            String calcSetId = "rv_model_" + eventSourceId + "_" + (partitionKey != null ? partitionKey : "");
            // 计算集名称：事件源名称 + 维度名称 + "计算集"
            String calcSetName = (eventSourceName != null ? eventSourceName : "")
                    + (dimension.getDimensionName() != null ? dimension.getDimensionName() : "") + "计算集";

            sb.append("计算集ID：").append(calcSetId).append("\n");
            sb.append("计算集名称：").append(calcSetName).append("\n");

            // 匹配维度
            String matchedDimension = matchDimension(partitionKey, dimensionMap, eventSourceFieldDescMap);
            sb.append("维度：").append(matchedDimension).append("\n");

            sb.append("分区字段映射：").append(partitionKey != null ? partitionKey : "").append("\n");
            sb.append("数据表：").append(dimension.getTableName() != null ? dimension.getTableName() : "").append("\n");
            sb.append("最长保留时间：").append(dataRetentionTime != null ? dataRetentionTime : "").append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 匹配维度
     * 匹配流程：
     * 1. 精准匹配：分区字段 == 维度ID
     * 2. 去除下划线匹配：分区字段去掉下划线后 == 维度ID去掉下划线后
     * 3. 中文含义包含匹配：用分区字段对应的中文含义与维度名称做包含匹配
     *
     * @param partitionKey 分区字段
     * @param dimensionMap 维度配置映射（维度ID -> 维度名称）
     * @param eventSourceFieldDescMap 事件源字段描述映射（字段名 -> 描述）
     * @return 匹配的维度ID，匹配不上返回分区字段本身并用Markdown警告语法提醒
     */
    private String matchDimension(String partitionKey, Map<String, String> dimensionMap,
                                  Map<String, String> eventSourceFieldDescMap) {
        if (partitionKey == null || partitionKey.isEmpty()) {
            return "（无分区字段）";
        }
        if (dimensionMap.isEmpty()) {
            // 维度配置为空，原样输出并提醒
            return "> ⚠️ 维度：" + partitionKey + "（维度配置为空，请人工确认）";
        }

        // 1. 精准匹配：分区字段 == 维度ID
        for (String dimensionId : dimensionMap.keySet()) {
            if (partitionKey.equalsIgnoreCase(dimensionId)) {
                return dimensionId;
            }
        }

        // 2. 去除下划线匹配：分区字段去掉下划线后 == 维度ID去掉下划线后
        String partitionKeyNoUnderline = partitionKey.toLowerCase().replace("_", "");
        for (String dimensionId : dimensionMap.keySet()) {
            String dimensionIdNoUnderline = dimensionId.toLowerCase().replace("_", "");
            if (partitionKeyNoUnderline.equals(dimensionIdNoUnderline)) {
                return dimensionId;
            }
        }

        // 3. 中文含义包含匹配：用分区字段对应的中文含义与维度名称做包含匹配
        String partitionKeyDesc = eventSourceFieldDescMap.get(partitionKey.toLowerCase());
        if (partitionKeyDesc != null && !partitionKeyDesc.isEmpty()) {
            for (Map.Entry<String, String> entry : dimensionMap.entrySet()) {
                String dimensionId = entry.getKey();
                String dimensionName = entry.getValue();
                // 中文含义包含维度名称关键字，或维度名称包含中文含义关键字
                if (partitionKeyDesc.contains(dimensionName) || dimensionName.contains(partitionKeyDesc)) {
                    return dimensionId;
                }
            }
        }

        // 匹配不上，原样输出并用Markdown警告语法提醒
        return "> ⚠️ 维度：" + partitionKey + "（匹配不上，请人工确认）";
    }

    /**
     * 获取字段默认值
     * 如果字段有默认值则使用默认值，时间类型设置默认日期，其他类型设置为空或0
     *
     * @param field 事件字段
     * @param typeName Java类型名称
     * @return 默认值
     */
    private String getDefaultValue(Iteration1ResultDTO.EventField field, String typeName) {
        // 如果字段已有默认值且不为空，直接使用
        if (field.getDefaultValue() != null && !field.getDefaultValue().isEmpty()) {
            return field.getDefaultValue();
        }

        // 根据类型设置默认值
        switch (typeName) {
            case "Timestamp":
                return DEFAULT_DATE;
            case "Integer":
            case "Long":
                return "0";
            case "BigDecimal":
                return "0";
            case "Boolean":
                return "false";
            default:
                return "";
        }
    }
}