package com.cmbchina.llm.service;

import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.entity.model.RequirementDoc;
import com.cmbchina.llm.entity.model.TqFieldInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天启Excel生成服务
 * 基于Iteration1ResultDTO生成天启系统使用的建表语句Excel
 *
 * @author IT006901
 * @date 2026-08-12 17:00:00
 */
@Slf4j
@Service
public class TqExcelService {

    /**
     * 输出目录
     */
    private static final String OUTPUT_DIR = "output/";

    /**
     * 结合Iteration1ResultDTO和RequirementDoc生成天启Excel文件（多维度多Sheet）
     * 直接从llmResult.llmOut取值，不依赖createSql：
     * 1. 从llmOut提取全部字段信息：列名取column_name，注释取description，类型取dataType
     * 2. 保留字段与事件源字段name匹配，匹配不上再匹配llmOut的aliases，再匹配不上预留向量匹配（暂未实现）
     * 3. llmOut数据量即全字段量，不过滤，保留字段有什么全部保留
     * 4. 表名、分区键、主键等信息从RequirementDoc获取，主键和分区字段标记非空
     * 5. 输出不填精度
     * 6. 根据confidence分值标记Excel颜色：>0.9不标记，<0.5标记红色，0.5~0.9标记黄色
     *
     * @param iteration1Result 大模型解析结果（提供llmOut字段信息、confidence等）
     * @param doc 需求文档（提供表名、分区键、主键、保留字段、事件源字段等信息）
     * @return 生成的Excel文件路径
     */
    public String generateTqExcelCombined(Iteration1ResultDTO iteration1Result, RequirementDoc doc) {
        try {
            if (doc == null || doc.getDimensionInfos() == null || doc.getDimensionInfos().isEmpty()) {
                log.warn("需求文档或维度信息为空");
                return null;
            }

            // 生成Excel文件名：事件源ID + _天启建表语句
            String eventSourceId = doc.getBasicInfo().getEventSourceId();
            String fileName = eventSourceId + "_天启建表语句.xlsx";
            String filePath = OUTPUT_DIR + fileName;

            // 构建Iteration1ResultDTO字段映射（column_name -> LlmField），提供字段描述、confidence等信息
            Map<String, Iteration1ResultDTO.LlmField> llmFieldMap = buildLlmFieldMap(iteration1Result);

            // 创建工作簿
            Workbook workbook = new XSSFWorkbook();

            // 创建表头样式和普通单元格样式
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle cellStyle = createCellStyle(workbook);
            // 创建黄色提醒样式（confidence在0.5~0.9之间）
            CellStyle yellowStyle = createWarningCellStyle(workbook, IndexedColors.YELLOW);
            // 创建红色关注样式（confidence < 0.5）
            CellStyle redStyle = createWarningCellStyle(workbook, IndexedColors.RED);

            // 构建事件源字段映射（小写name -> EventSourceField），用于保留字段匹配
            Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap = buildEventSourceFieldMap(doc);

            // 遍历每个维度，创建对应的Sheet
            for (RequirementDoc.DimensionInfo dimension : doc.getDimensionInfos()) {
                String tableName = dimension.getTableName();
                log.info("开始生成维度Sheet：{}，表名：{}", dimension.getDimensionName(), tableName);

                // 1. 解析保留字段（不填或all则默认保留全部字段）
                log.info("维度{}的retainFields原始值：{}", dimension.getDimensionName(), dimension.getRetainFields());
                List<String> retainFields = resolveRetainFields(doc, dimension);
                log.info("维度{}解析后的保留字段：{}", dimension.getDimensionName(), retainFields);

                // 2. 从llmOut提取字段信息，按保留字段匹配（匹配不上的也输出）
                // 主键和分区键信息在匹配时直接带入
                List<TqFieldInfo> fieldList = extractFieldInfoFromLlmOut(iteration1Result, retainFields, llmFieldMap,
                        eventSourceFieldMap, dimension.getPrimaryKeys(), dimension.getPartitionKey());
                log.info("维度{}从llmOut提取到{}个字段", dimension.getDimensionName(), fieldList.size());
                if (fieldList.isEmpty()) {
                    log.warn("维度{}从llmOut未提取到任何字段信息", dimension.getDimensionName());
                    continue;
                }

                // 3. 结合RequirementDoc补充分区键、主键等信息（主键和分区字段标记非空）
                enrichFieldInfoFromRequirement(fieldList, doc, dimension, llmFieldMap, eventSourceFieldMap);

                // 4. 创建Sheet（Sheet名为表名，带颜色标记）
                createSheetWithConfidence(workbook, fieldList, tableName, headerStyle, cellStyle, yellowStyle, redStyle, llmFieldMap);
            }

            // 确保输出目录存在
            java.io.File dir = new java.io.File(OUTPUT_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            } finally {
                workbook.close();
            }

            log.info("天启Excel生成成功（结合模式），事件源ID：{}，文件路径：{}", eventSourceId, filePath);
            return filePath;
        } catch (Exception e) {
            log.error("天启Excel生成失败（结合模式）：{}", e);
            return null;
        }
    }

    /**
     * 根据RequirementDoc生成天启Excel文件（多维度多Sheet）
     * 有几个维度就建几张表（Sheet页），表名、分区键和主键从需求文档的维度信息获取
     * 输出文件以：事件源（事件源ID）+ "_天启建表语句"命名
     *
     * @param doc 需求文档
     * @return 生成的Excel文件路径
     */
    public String generateTqExcelByRequirement(RequirementDoc doc) {
        try {
            if (doc == null || doc.getDimensionInfos() == null || doc.getDimensionInfos().isEmpty()) {
                log.warn("需求文档或维度信息为空");
                return null;
            }

            // 生成Excel文件名：事件源ID + _天启建表语句
            String eventSourceId = doc.getBasicInfo().getEventSourceId();
            String fileName = eventSourceId + "_天启建表语句.xlsx";
            String filePath = OUTPUT_DIR + fileName;

            // 创建工作簿
            Workbook workbook = new XSSFWorkbook();

            // 创建表头样式和普通单元格样式
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle cellStyle = createCellStyle(workbook);

            // 遍历每个维度，创建对应的Sheet
            for (RequirementDoc.DimensionInfo dimension : doc.getDimensionInfos()) {
                String tableName = dimension.getTableName();
                log.info("开始生成维度Sheet：{}，表名：{}", dimension.getDimensionName(), tableName);

                // 从需求文档提取当前维度的字段信息
                List<TqFieldInfo> fieldList = extractFieldInfoFromRequirement(doc, dimension);
                if (fieldList.isEmpty()) {
                    log.warn("维度{}未提取到任何字段信息", dimension.getDimensionName());
                    continue;
                }

                // 创建Sheet（Sheet名为表名）
                createSheet(workbook, fieldList, tableName, headerStyle, cellStyle);
            }

            // 确保输出目录存在
            java.io.File dir = new java.io.File(OUTPUT_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            } finally {
                workbook.close();
            }

            log.info("天启Excel生成成功（多维度），事件源ID：{}，文件路径：{}", eventSourceId, filePath);
            return filePath;

        } catch (Exception e) {
            log.error("天启Excel生成失败（需求文档模式）：{}", e);
            return null;
        }
    }
    /**
     * 从createSql中解析字段定义信息
     * 格式示例：BSR_NTB_NBR VARCHAR(8 BYTES) NOT NULL
     * 提取字段名称、字段类型、非空标记等信息
     *
     * @param createSql 建表SQL语句
     * @return 字段信息列表
     */
    private List<TqFieldInfo> extractFieldInfoFromCreateSql(String createSql) {
        List<TqFieldInfo> fieldList = new ArrayList<>();
        if (createSql == null || createSql.isEmpty()) {
            log.warn("createSql为空，无法解析字段定义");
            return fieldList;
        }

        // 匹配字段定义行：字段名 类型(长度) [NOT NULL]
        // 示例：BSR_NTB_NBR VARCHAR(8 BYTES) NOT NULL
        Pattern fieldPattern = Pattern.compile(
                "(?m)([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z]+)(?:\\(([^)]*)\\))?\\s*(NOT\\s+NULL)?");
        Matcher matcher = fieldPattern.matcher(createSql);

        // 序号计数器
        int serialNumber = 1;

        while (matcher.find()) {
            String fieldName = matcher.group(1).toUpperCase();
            String dataType = matcher.group(2);
            String lengthInfo = matcher.group(3);
            String notNull = matcher.group(4);

            // 跳过CREATE TABLE、PRIMARY KEY等关键字行
            if ("CREATE".equalsIgnoreCase(fieldName) || "TABLE".equalsIgnoreCase(fieldName)
                    || "PRIMARY".equalsIgnoreCase(fieldName) || "KEY".equalsIgnoreCase(fieldName)
                    || "CONSTRAINT".equalsIgnoreCase(fieldName) || "UNIQUE".equalsIgnoreCase(fieldName)
                    || "INDEX".equalsIgnoreCase(fieldName) || "FOREIGN".equalsIgnoreCase(fieldName)
                    || "REFERENCES".equalsIgnoreCase(fieldName) || "ON".equalsIgnoreCase(fieldName)
                    || "DELETE".equalsIgnoreCase(fieldName) || "CASCADE".equalsIgnoreCase(fieldName)) {
                continue;
            }

            TqFieldInfo fieldInfo = new TqFieldInfo();
            fieldInfo.setSerialNumber(serialNumber++);
            fieldInfo.setFieldName(fieldName);

            // 字段类型
            setFieldType(fieldInfo, dataType);

            // 精度（长度）
            if (lengthInfo != null && !lengthInfo.isEmpty()) {
                // 提取数字部分作为长度，如 VARCHAR(8 BYTES) -> 8
                Matcher lengthMatcher = Pattern.compile("(\\d+)").matcher(lengthInfo);
                if (lengthMatcher.find()) {
                    fieldInfo.setLength(lengthMatcher.group(1));
                }
            }

            // 非空标记
            if (notNull != null && !notNull.isEmpty()) {
                fieldInfo.setNotNull("TRUE");
            }

            // 其他字段预留
            fieldInfo.setDecimalPrecision("");
            fieldInfo.setDefaultValue("");
            fieldInfo.setPrimaryKey("");
            fieldInfo.setPartitionKey("");
            fieldInfo.setComment("");

            fieldList.add(fieldInfo);
        }

        return fieldList;
    }
    /**
     * 结合RequirementDoc补充字段信息
     * 主键、分区键等信息从RequirementDoc获取，用canonicalField匹配（精准匹配或大致一样）
     * 注释已在extractFieldInfoFromLlmOut中从description获取，此处不再重复设置
     *
     * @param fieldList 字段信息列表（从llmOut提取）
     * @param doc 需求文档
     * @param dimension 维度信息
     * @param llmFieldMap 大模型字段映射（大写column_name -> LlmField）
     */
    /**
     * 结合RequirementDoc补充字段信息
     * 主键、分区键等信息从RequirementDoc获取，通过canonicalField匹配事件源字段name和aliases
     * 保留字段匹配优先级：1.与事件源字段name精准匹配 2.与llmOut的aliases匹配 3.预留向量匹配（暂未实现）
     * 主键和分区字段标记非空
     *
     * @param fieldList 字段信息列表（从llmOut提取）
     * @param doc 需求文档
     * @param dimension 维度信息
     * @param llmFieldMap 大模型字段映射（大写column_name -> LlmField）
     * @param eventSourceFieldMap 事件源字段映射（小写name -> EventSourceField）
     */
    private void enrichFieldInfoFromRequirement(List<TqFieldInfo> fieldList, RequirementDoc doc,
                                                RequirementDoc.DimensionInfo dimension,
                                                Map<String, Iteration1ResultDTO.LlmField> llmFieldMap,
                                                Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap) {
        if (fieldList == null || fieldList.isEmpty()) {
            return;
        }

        // 获取当前维度的主键字段和分区键
        List<String> primaryKeys = dimension.getPrimaryKeys();
        String partitionKey = dimension.getPartitionKey();

        // 构建主键字段匹配集合（去除下划线的小写形式，用于大致匹配）
        Map<String, Integer> primaryKeyOrder = new HashMap<>();
        if (primaryKeys != null) {
            for (int i = 0; i < primaryKeys.size(); i++) {
                String pk = primaryKeys.get(i).toLowerCase().replace("_", "");
                primaryKeyOrder.put(pk, i);
            }
        }

        // 分区键去除下划线的小写形式
        String partitionKeyNorm = partitionKey != null ? partitionKey.toLowerCase().replace("_", "") : null;

        // 遍历字段列表，通过canonicalField匹配主键和分区键
        for (TqFieldInfo fieldInfo : fieldList) {
            String fieldName = fieldInfo.getFieldName().toUpperCase();

            // 从llmFieldMap获取canonicalField，用于匹配主键和分区键
            Iteration1ResultDTO.LlmField llmField = llmFieldMap.get(fieldName);
            if (llmField == null) {
                continue;
            }

            String canonicalField = llmField.getCanonicalField();
            if (canonicalField == null || canonicalField.isEmpty()) {
                continue;
            }

            // canonicalField去除下划线的小写形式，用于大致匹配
            String canonicalNorm = canonicalField.toLowerCase().replace("_", "");

            // 标记主键（通过canonicalField匹配）
            boolean isPrimaryKey = primaryKeyOrder.containsKey(canonicalNorm);

            // 标记分区键（通过canonicalField匹配）
            boolean isPartitionKey = partitionKeyNorm != null && canonicalNorm.equals(partitionKeyNorm);

            if (isPrimaryKey) {
                fieldInfo.setPrimaryKey("TRUE");
            }

            if (isPartitionKey) {
                fieldInfo.setPartitionKey("TRUE");
            }

            // 主键和分区字段标记非空
            if (isPrimaryKey || isPartitionKey) {
                fieldInfo.setNotNull("TRUE");
            }
        }

        // 主键字段按PRIMARY KEY顺序排在前面
        if (!primaryKeyOrder.isEmpty()) {
            reorderFieldList(fieldList, primaryKeyOrder, llmFieldMap);
        }
    }

    /**
     * 创建带颜色标记的Sheet页（根据confidence分值标记）
     * confidence > 0.9：不标记（默认样式）
     * confidence < 0.5：标记红色（需要人工关注）
     * 0.5 ~ 0.9：标记黄色（提醒）
     *
     * @param workbook 工作簿
     * @param fieldList 字段信息列表
     * @param sheetName Sheet名称
     * @param headerStyle 表头样式
     * @param cellStyle 默认单元格样式
     * @param yellowStyle 黄色提醒样式
     * @param redStyle 红色关注样式
     * @param llmFieldMap 大模型字段映射（用于获取confidence）
     */
    private void createSheetWithConfidence(Workbook workbook, List<TqFieldInfo> fieldList, String sheetName,
                                           CellStyle headerStyle, CellStyle cellStyle,
                                           CellStyle yellowStyle, CellStyle redStyle,
                                           Map<String, Iteration1ResultDTO.LlmField> llmFieldMap) {
        Sheet sheet = workbook.createSheet(sheetName);

        // 创建表头（10列：序号/字段名/类型/精度(小数)/精度(长度)/非空/默认值/主键/分区键/注释）
        Row headerRow = sheet.createRow(0);
        String[] headers = {"序号", "字段名", "字段类型", "精度", "精度", "非空", "默认值", "主键", "分区键", "注释"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        // 设置列宽
        sheet.setColumnWidth(0, 8 * 256);   // 序号
        sheet.setColumnWidth(1, 20 * 256);  // 字段名
        sheet.setColumnWidth(2, 15 * 256);  // 字段类型
        sheet.setColumnWidth(3, 10 * 256);  // 精度(小数)
        sheet.setColumnWidth(4, 10 * 256);  // 精度(长度)
        sheet.setColumnWidth(5, 8 * 256);   // 非空
        sheet.setColumnWidth(6, 10 * 256);  // 默认值
        sheet.setColumnWidth(7, 8 * 256);   // 主键
        sheet.setColumnWidth(8, 8 * 256);   // 分区键
        sheet.setColumnWidth(9, 30 * 256);  // 注释

        // 写入数据行
        for (int i = 0; i < fieldList.size(); i++) {
            TqFieldInfo fieldInfo = fieldList.get(i);
            Row row = sheet.createRow(i + 1);

            // 根据confidence分值确定单元格样式
            CellStyle rowStyle = cellStyle;
            Iteration1ResultDTO.LlmField llmField = llmFieldMap.get(fieldInfo.getFieldName().toUpperCase());
            if (llmField != null && llmField.getConfidence() != null) {
                double confidence = llmField.getConfidence();
                if (confidence < 0.5) {
                    // 红色：需要人工关注
                    rowStyle = redStyle;
                } else if (confidence <= 0.9) {
                    // 黄色：提醒
                    rowStyle = yellowStyle;
                }
                // confidence > 0.9：不标记，使用默认样式
            }

            createCell(row, 0, fieldInfo.getSerialNumber().toString(), rowStyle);
            createCell(row, 1, fieldInfo.getFieldName(), rowStyle);
            createCell(row, 2, fieldInfo.getFieldType(), rowStyle);
            createCell(row, 3, fieldInfo.getDecimalPrecision(), rowStyle);
            createCell(row, 4, fieldInfo.getLength(), rowStyle);
            createCell(row, 5, fieldInfo.getNotNull(), rowStyle);
            createCell(row, 6, fieldInfo.getDefaultValue(), rowStyle);
            createCell(row, 7, fieldInfo.getPrimaryKey(), rowStyle);
            createCell(row, 8, fieldInfo.getPartitionKey(), rowStyle);
            createCell(row, 9, fieldInfo.getComment(), rowStyle);
        }
    }

    /**
     * 创建带背景颜色的单元格样式（用于confidence标记）
     *
     * @param workbook 工作簿
     * @param color 背景颜色
     * @return 带背景颜色的单元格样式
     */
    private CellStyle createWarningCellStyle(Workbook workbook, IndexedColors color) {
        CellStyle warningStyle = workbook.createCellStyle();
        warningStyle.setFillForegroundColor(color.getIndex());
        warningStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        warningStyle.setBorderBottom(BorderStyle.THIN);
        warningStyle.setBorderTop(BorderStyle.THIN);
        warningStyle.setBorderLeft(BorderStyle.THIN);
        warningStyle.setBorderRight(BorderStyle.THIN);
        warningStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        return warningStyle;
    }
    /**
     * 构建事件源字段映射（小写name -> EventSourceField）
     * 用于保留字段与事件源字段name匹配
     *
     * @param doc 需求文档
     * @return 字段名到事件源字段的映射（key为小写字段名，便于不区分大小写匹配）
     */
    private Map<String, RequirementDoc.EventSourceField> buildEventSourceFieldMap(RequirementDoc doc) {
        Map<String, RequirementDoc.EventSourceField> fieldMap = new HashMap<>();
        if (doc == null || doc.getEventSourceFields() == null) {
            return fieldMap;
        }
        for (RequirementDoc.EventSourceField field : doc.getEventSourceFields()) {
            if (field.getName() != null && !field.getName().isEmpty()) {
                fieldMap.put(field.getName().toLowerCase(), field);
            }
        }
        return fieldMap;
    }

    /**
     * 从llmOut提取字段信息，按保留字段匹配
     * 保留字段为全字段（all或空）时，输出全部llmOut字段
     * 保留字段指定时，严格按照保留字段匹配（先匹配name，再匹配aliases），匹配不上的也输出结果
     * 列名取column_name，注释取description，类型取dataType，不填精度
     *
     * @param iteration1Result 大模型解析结果
     * @param retainFields 保留字段列表（解析后的）
     * @param llmFieldMap 大模型字段映射（大写column_name -> LlmField）
     * @param eventSourceFieldMap 事件源字段映射（小写name -> EventSourceField）
     * @param primaryKeys 主键字段列表
     * @param partitionKey 分区键字段
     * @return 字段信息列表
     */
    private List<TqFieldInfo> extractFieldInfoFromLlmOut(Iteration1ResultDTO iteration1Result,
                                                         List<String> retainFields,
                                                         Map<String, Iteration1ResultDTO.LlmField> llmFieldMap,
                                                         Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap,
                                                         List<String> primaryKeys,
                                                         String partitionKey) {
        List<TqFieldInfo> fieldList = new ArrayList<>();

        if (iteration1Result == null || iteration1Result.getLlmResult() == null) {
            log.warn("Iteration1ResultDTO或llmResult为空");
            return fieldList;
        }

        List<Iteration1ResultDTO.LlmField> llmOut = iteration1Result.getLlmResult().getLlmOut();
        if (llmOut == null || llmOut.isEmpty()) {
            log.warn("llmOut为空");
            return fieldList;
        }

        // 判断保留字段是否为全字段（retainFields为null或空，说明是all或未指定，保留全部）
        boolean useAllFields = retainFields == null || retainFields.isEmpty();

        // 构建保留字段匹配集合（小写），用于匹配
        Map<String, Boolean> retainFieldSet = new HashMap<>();
        if (!useAllFields) {
            for (String field : retainFields) {
                if (field != null && !field.trim().isEmpty()) {
                    retainFieldSet.put(field.trim().toLowerCase(), true);
                }
            }
        }
        // 序号计数器
        int serialNumber = 1;

        // 遍历llmOut，提取字段信息
        for (Iteration1ResultDTO.LlmField fieldObj : llmOut) {
            TqFieldInfo fieldInfo = new TqFieldInfo();

            // 序号
            fieldInfo.setSerialNumber(serialNumber++);

            // 列名取column_name（大写）
            String columnName = fieldObj.getColumn_name();
            fieldInfo.setFieldName(columnName != null ? columnName.toUpperCase() : "");

            // 类型取dataType
            String dataType = fieldObj.getDataType();
            setFieldType(fieldInfo, dataType);

            // 注释取description
            fieldInfo.setComment(fieldObj.getDescription());

            // 不填精度
            fieldInfo.setDecimalPrecision("");
            fieldInfo.setLength("");

            // 非空、主键、分区键等字段预留
            fieldInfo.setNotNull("");
            fieldInfo.setDefaultValue("");
            fieldInfo.setPrimaryKey("");
            fieldInfo.setPartitionKey("");

            fieldList.add(fieldInfo);
        }

        // 如果不是全字段，需要按保留字段匹配，匹配不上的也输出结果
        if (!useAllFields) {
            fieldList = filterByRetainFields(fieldList, retainFieldSet, llmFieldMap, eventSourceFieldMap,
                    primaryKeys, partitionKey);
        }

        return fieldList;
    }

    /**
     * 按保留字段过滤字段信息
     * 保留字段与llmOut字段匹配（先匹配canonicalField，再匹配aliases，再匹配description），匹配不上的也输出结果
     * 匹配上的字段：注释取description，主键/分区键信息直接带入
     *
     * @param fieldList 字段信息列表（从llmOut提取的全部字段）
     * @param retainFieldSet 保留字段集合（小写）
     * @param llmFieldMap 大模型字段映射（大写column_name -> LlmField）
     * @param eventSourceFieldMap 事件源字段映射（小写name -> EventSourceField）
     * @param primaryKeys 主键字段列表
     * @param partitionKey 分区键字段
     * @return 过滤后的字段信息列表
     */
    private List<TqFieldInfo> filterByRetainFields(List<TqFieldInfo> fieldList,
                                                   Map<String, Boolean> retainFieldSet,
                                                   Map<String, Iteration1ResultDTO.LlmField> llmFieldMap,
                                                   Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap,
                                                   List<String> primaryKeys,
                                                   String partitionKey) {
        List<TqFieldInfo> resultList = new ArrayList<>();
        // 构建主键字段匹配集合（去除下划线的小写形式，用于大致匹配）
        Map<String, Integer> primaryKeyOrder = new HashMap<>();
        if (primaryKeys != null) {
            for (int i = 0; i < primaryKeys.size(); i++) {
                String pk = primaryKeys.get(i).toLowerCase().replace("_", "");
                primaryKeyOrder.put(pk, i);
            }
        }

        // 分区键去除下划线的小写形式
        String partitionKeyNorm = partitionKey != null ? partitionKey.toLowerCase().replace("_", "") : null;

        // 构建保留字段 -> 是否主键/分区键的映射
        // key同时使用保留字段名的小写形式和去除下划线的小写形式，确保matchedRetain无论是什么格式都能匹配
        Map<String, Boolean> retainFieldIsPrimaryKey = new HashMap<>();
        Map<String, Boolean> retainFieldIsPartitionKey = new HashMap<>();
        for (String retainField : retainFieldSet.keySet()) {
            String retainLower = retainField.toLowerCase();
            String retainNorm = retainLower.replace("_", "");
            boolean isPrimaryKey = primaryKeyOrder.containsKey(retainNorm);
            boolean isPartitionKey = partitionKeyNorm != null && retainNorm.equals(partitionKeyNorm);
            // 用保留字段名的小写形式作为key
            retainFieldIsPrimaryKey.put(retainLower, isPrimaryKey);
            retainFieldIsPartitionKey.put(retainLower, isPartitionKey);
            // 同时用去除下划线的小写形式作为key，兼容matchedRetain的不同格式
            retainFieldIsPrimaryKey.put(retainNorm, isPrimaryKey);
            retainFieldIsPartitionKey.put(retainNorm, isPartitionKey);
        }

        // 记录已匹配上的保留字段（小写），用于第二步判断哪些保留字段未匹配上
        Map<String, Boolean> matchedRetainFields = new HashMap<>();

        // 遍历llmOut提取的字段，判断是否在保留字段中
        for (TqFieldInfo fieldInfo : fieldList) {
            String fieldName = fieldInfo.getFieldName().toUpperCase();
            Iteration1ResultDTO.LlmField llmField = llmFieldMap.get(fieldName);
            if (llmField == null) {
                // 无法匹配到llmField，直接保留
                resultList.add(fieldInfo);
                continue;
            }

            // 判断该字段是否在保留字段中
            boolean isInRetainFields = matchRetainField(llmField, retainFieldSet, eventSourceFieldMap);
            if (isInRetainFields) {
                // 记录匹配上的保留字段
                String matchedRetain = findMatchedRetainField(llmField, retainFieldSet, eventSourceFieldMap);
                if (matchedRetain != null) {
                    matchedRetainFields.put(matchedRetain, true);
                }

                // 匹配上的字段：注释取description（如果为空则从事件源字段获取）
                if (fieldInfo.getComment() == null || fieldInfo.getComment().isEmpty()) {
                    fieldInfo.setComment(llmField.getDescription());
                }

                // 主键/分区键信息直接带入
                // 用匹配到的保留字段名（matchedRetain）查映射，判断是否为主键/分区键
                // 因为保留字段是需求文档中的字段，主键也是需求文档中的字段，两者应该一致
                if (matchedRetain != null) {
                    // 对matchedRetain做规范化处理（小写、去除下划线），兼容不同格式
                    String matchedRetainLower = matchedRetain.toLowerCase();
                    String matchedRetainNorm = matchedRetainLower.replace("_", "");
                    // 标记主键（先查规范化形式，再查原始形式）
                    if (Boolean.TRUE.equals(retainFieldIsPrimaryKey.get(matchedRetainNorm))
                            || Boolean.TRUE.equals(retainFieldIsPrimaryKey.get(matchedRetainLower))) {
                        fieldInfo.setPrimaryKey("TRUE");
                        fieldInfo.setNotNull("TRUE");
                    }
                    // 标记分区键（先查规范化形式，再查原始形式）
                    if (Boolean.TRUE.equals(retainFieldIsPartitionKey.get(matchedRetainNorm))
                            || Boolean.TRUE.equals(retainFieldIsPartitionKey.get(matchedRetainLower))) {
                        fieldInfo.setPartitionKey("TRUE");
                        fieldInfo.setNotNull("TRUE");
                    }
                }

                resultList.add(fieldInfo);
            }
        }

        // 对于保留字段中匹配不上llmOut的字段，也需要输出结果（用保留字段名作为字段名）
        for (String retainField : retainFieldSet.keySet()) {
            // 检查该保留字段是否已经被匹配上
            if (matchedRetainFields.containsKey(retainField)) {
                continue;
            }

            // 匹配不上的保留字段，用保留字段名作为字段名输出
            TqFieldInfo fieldInfo = new TqFieldInfo();
            fieldInfo.setSerialNumber(resultList.size() + 1);
            fieldInfo.setFieldName(retainField.toUpperCase());
            fieldInfo.setFieldType("");
            fieldInfo.setComment("");
            fieldInfo.setDecimalPrecision("");
            fieldInfo.setLength("");
            fieldInfo.setNotNull("");
            fieldInfo.setDefaultValue("");
            fieldInfo.setPrimaryKey("");
            fieldInfo.setPartitionKey("");

            // 主键/分区键信息直接带入（匹配不上的保留字段）
            String retainNorm = retainField.toLowerCase().replace("_", "");
            if (primaryKeyOrder.containsKey(retainNorm)) {
                fieldInfo.setPrimaryKey("TRUE");
                fieldInfo.setNotNull("TRUE");
            }
            if (partitionKeyNorm != null && retainNorm.equals(partitionKeyNorm)) {
                fieldInfo.setPartitionKey("TRUE");
                fieldInfo.setNotNull("TRUE");
            }

            resultList.add(fieldInfo);
        }

        // 重新设置序号
        for (int i = 0; i < resultList.size(); i++) {
            resultList.get(i).setSerialNumber(i + 1);
        }

        return resultList;
    }
    /**
     * 查找llmField匹配到的保留字段名（小写）
     * 匹配优先级：
     * 1. canonicalField精准匹配（不区分大小写）
     * 2. aliases精准匹配（不区分大小写）
     * 3. canonicalField去除下划线后匹配（如agcNtbNbr匹配AGC_NTB_NBR）
     * 4. description匹配（llmField的description与事件源字段的description匹配）
     * 5. aliases去除下划线后匹配
     * 6. 预留向量匹配（暂未实现）
     *
     * @param llmField 大模型字段
     * @param retainFieldSet 保留字段集合（小写）
     * @param eventSourceFieldMap 事件源字段映射（小写name -> EventSourceField）
     * @return 匹配到的保留字段名（小写），未匹配返回null
     */
    private String findMatchedRetainField(Iteration1ResultDTO.LlmField llmField, Map<String, Boolean> retainFieldSet,
                                          Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap) {
        if (llmField == null || retainFieldSet == null || retainFieldSet.isEmpty()) {
            return null;
        }

        // 1. 匹配canonicalField（精准匹配，不区分大小写）
        String canonicalField = llmField.getCanonicalField();
        if (canonicalField != null && !canonicalField.isEmpty()
                && retainFieldSet.containsKey(canonicalField.toLowerCase())) {
            return canonicalField.toLowerCase();
        }

        // 2. 匹配aliases（精准匹配，不区分大小写）
        List<String> aliases = llmField.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isEmpty()
                        && retainFieldSet.containsKey(alias.toLowerCase())) {
                    return alias.toLowerCase();
                }
            }
        }

        // 3. canonicalField去除下划线后匹配（如agcNtbNbr匹配AGC_NTB_NBR，返回保留字段名）
        if (canonicalField != null && !canonicalField.isEmpty()) {
            String canonicalNoUnderline = canonicalField.toLowerCase().replace("_", "");
            for (String retainField : retainFieldSet.keySet()) {
                String retainNoUnderline = retainField.replace("_", "");
                if (canonicalNoUnderline.equals(retainNoUnderline)) {
                    return retainField;
                }
            }
        }

        // 4. description匹配（llmField的description与事件源字段的description匹配）
        if (eventSourceFieldMap != null && !eventSourceFieldMap.isEmpty()) {
            String llmDescription = llmField.getDescription();
            if (llmDescription != null && !llmDescription.isEmpty()) {
                String llmDescNorm = llmDescription.trim().toLowerCase();
                for (RequirementDoc.EventSourceField eventField : eventSourceFieldMap.values()) {
                    // 该事件源字段是否在保留字段中
                    if (eventField.getName() != null && !eventField.getName().isEmpty()
                            && retainFieldSet.containsKey(eventField.getName().toLowerCase())) {
                        String eventDesc = eventField.getDescription();
                        if (eventDesc != null && !eventDesc.isEmpty()
                                && eventDesc.trim().toLowerCase().equals(llmDescNorm)) {
                            return eventField.getName().toLowerCase();
                        }
                    }
                }
            }
        }

        // 5. aliases去除下划线后匹配
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.isEmpty()) {
                    continue;
                }
                String aliasNoUnderline = alias.toLowerCase().replace("_", "");
                for (String retainField : retainFieldSet.keySet()) {
                    String retainNoUnderline = retainField.replace("_", "");
                    if (aliasNoUnderline.equals(retainNoUnderline)) {
                        return retainField;
                    }
                }
            }
        }

        // 6. 预留向量匹配（暂未实现）
        // TODO: 向量匹配逻辑

        return null;
    }

    /**
     * 判断llmField是否在保留字段中
     * 匹配优先级：
     * 1. canonicalField精准匹配（不区分大小写）
     * 2. aliases精准匹配（不区分大小写）
     * 3. canonicalField去除下划线后匹配（如agcNtbNbr匹配AGC_NTB_NBR）
     * 4. description匹配（llmField的description与事件源字段的description匹配）
     * 5. aliases去除下划线后匹配
     * 6. 预留向量匹配（暂未实现）
     *
     * @param llmField 大模型字段
     * @param retainFieldSet 保留字段集合（小写）
     * @param eventSourceFieldMap 事件源字段映射（小写name -> EventSourceField）
     * @return 是否匹配
     */
    private boolean matchRetainField(Iteration1ResultDTO.LlmField llmField, Map<String, Boolean> retainFieldSet,
                                     Map<String, RequirementDoc.EventSourceField> eventSourceFieldMap) {
        if (llmField == null || retainFieldSet == null || retainFieldSet.isEmpty()) {
            return false;
        }
        // 1. 匹配canonicalField（精准匹配，不区分大小写）
        String canonicalField = llmField.getCanonicalField();
        if (canonicalField != null && !canonicalField.isEmpty()
                && retainFieldSet.containsKey(canonicalField.toLowerCase())) {
            return true;
        }

        // 2. 匹配aliases（精准匹配，不区分大小写）
        List<String> aliases = llmField.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isEmpty()
                        && retainFieldSet.containsKey(alias.toLowerCase())) {
                    return true;
                }
            }
        }

        // 3. canonicalField去除下划线后匹配（如agcNtbNbr匹配AGC_NTB_NBR）
        if (canonicalField != null && !canonicalField.isEmpty()) {
            String canonicalNoUnderline = canonicalField.toLowerCase().replace("_", "");
            for (String retainField : retainFieldSet.keySet()) {
                String retainNoUnderline = retainField.replace("_", "");
                if (canonicalNoUnderline.equals(retainNoUnderline)) {
                    return true;
                }
            }
        }

        // 4. description匹配（llmField的description与事件源字段的description匹配）
        if (eventSourceFieldMap != null && !eventSourceFieldMap.isEmpty()) {
            String llmDescription = llmField.getDescription();
            if (llmDescription != null && !llmDescription.isEmpty()) {
                String llmDescNorm = llmDescription.trim().toLowerCase();
                for (RequirementDoc.EventSourceField eventField : eventSourceFieldMap.values()) {
                    // 该事件源字段是否在保留字段中
                    if (eventField.getName() != null && !eventField.getName().isEmpty()
                            && retainFieldSet.containsKey(eventField.getName().toLowerCase())) {
                        String eventDesc = eventField.getDescription();
                        if (eventDesc != null && !eventDesc.isEmpty()
                                && eventDesc.trim().toLowerCase().equals(llmDescNorm)) {
                            return true;
                        }
                    }
                }
            }
        }

        // 5. aliases去除下划线后匹配
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.isEmpty()) {
                    continue;
                }
                String aliasNoUnderline = alias.toLowerCase().replace("_", "");
                for (String retainField : retainFieldSet.keySet()) {
                    String retainNoUnderline = retainField.replace("_", "");
                    if (aliasNoUnderline.equals(retainNoUnderline)) {
                        return true;
                    }
                }
            }
        }

        // 6. 预留向量匹配（暂未实现）
        // TODO: 向量匹配逻辑

        return false;
    }
    /**
     * 构建Iteration1ResultDTO字段映射（column_name -> LlmField）
     * 用于从大模型解析结果中获取字段类型等信息
     *
     * @param iteration1Result 大模型解析结果
     * @return 字段名到大模型字段的映射（key为大写字段名）
     */
    private Map<String, Iteration1ResultDTO.LlmField> buildLlmFieldMap(Iteration1ResultDTO iteration1Result) {
        Map<String, Iteration1ResultDTO.LlmField> llmFieldMap = new HashMap<>();
        if (iteration1Result == null || iteration1Result.getLlmResult() == null) {
            return llmFieldMap;
        }

        List<Iteration1ResultDTO.LlmField> llmOut = iteration1Result.getLlmResult().getLlmOut();
        if (llmOut == null || llmOut.isEmpty()) {
            return llmFieldMap;
        }

        for (Iteration1ResultDTO.LlmField fieldObj : llmOut) {
            String columnName = fieldObj.getColumn_name();
            if (columnName != null && !columnName.isEmpty()) {
                llmFieldMap.put(columnName.toUpperCase(), fieldObj);
            }
        }
        return llmFieldMap;
    }

    /**
     * 结合Iteration1ResultDTO和RequirementDoc提取当前维度的字段信息
     * 字段类型从Iteration1ResultDTO获取，主键/分区键/描述从RequirementDoc获取
     *
     * @param doc 需求文档
     * @param dimension 维度信息
     * @param llmFieldMap 大模型字段映射（column_name -> LlmField）
     * @return 字段信息列表
     */
    private List<TqFieldInfo> extractFieldInfoCombined(RequirementDoc doc, RequirementDoc.DimensionInfo dimension,
                                                       Map<String, Iteration1ResultDTO.LlmField> llmFieldMap) {
        List<TqFieldInfo> fieldList = new ArrayList<>();

        if (doc.getEventSourceFields() == null || doc.getEventSourceFields().isEmpty()) {
            log.warn("事件源字段信息为空");
            return fieldList;
        }

        // 构建事件源字段映射（name -> description）
        Map<String, String> fieldDescMap = new HashMap<>();
        for (RequirementDoc.EventSourceField field : doc.getEventSourceFields()) {
            if (field.getName() != null) {
                fieldDescMap.put(field.getName().toUpperCase(), field.getDescription());
            }
        }

        // 获取当前维度的保留字段
        // 选填：不填或填写all表示默认保留事件源全部字段；填写指定字段则只保留指定字段
        List<String> retainFields = resolveRetainFields(doc, dimension);

        // 获取当前维度的主键字段
        List<String> primaryKeys = dimension.getPrimaryKeys();
        String partitionKey = dimension.getPartitionKey();
        // 主键字段顺序映射
        Map<String, Integer> primaryKeyOrder = new HashMap<>();
        if (primaryKeys != null) {
            for (int i = 0; i < primaryKeys.size(); i++) {
                primaryKeyOrder.put(primaryKeys.get(i).toUpperCase(), i);
            }
        }

        // 序号计数器
        int serialNumber = 1;

        // 先添加主键字段（按主键顺序）
        if (primaryKeys != null) {
            for (String pkField : primaryKeys) {
                String fieldName = pkField.toUpperCase();
                TqFieldInfo fieldInfo = createTqFieldInfoCombined(serialNumber++, fieldName, fieldDescMap, llmFieldMap);
                fieldInfo.setPrimaryKey("TRUE");
                // 分区键也是主键的情况
                if (partitionKey != null && fieldName.equals(partitionKey.toUpperCase())) {
                    fieldInfo.setPartitionKey("TRUE");
                }
                fieldList.add(fieldInfo);
            }
        }

        // 再添加非主键的保留字段
        for (String retainField : retainFields) {
            String fieldName = retainField.toUpperCase();
            // 跳过已经作为主键添加的字段
            if (primaryKeyOrder.containsKey(fieldName)) {
                continue;
            }
            TqFieldInfo fieldInfo = createTqFieldInfoCombined(serialNumber++, fieldName, fieldDescMap, llmFieldMap);
            // 标记分区键
            if (partitionKey != null && fieldName.equals(partitionKey.toUpperCase())) {
                fieldInfo.setPartitionKey("TRUE");
            }
            fieldList.add(fieldInfo);
        }

        return fieldList;
    }

    /**
     * 解析保留字段列表
     * 选填逻辑：不填或填写all表示默认保留事件源全部字段；填写指定字段则只保留指定字段
     *
     * @param doc 需求文档
     * @param dimension 维度信息
     * @return 解析后的保留字段列表
     */
    private List<String> resolveRetainFields(RequirementDoc doc, RequirementDoc.DimensionInfo dimension) {
        List<String> retainFields = dimension.getRetainFields();
        // 判断是否需要默认保留全部字段：retainFields为空、为null、或包含all（不区分大小写）
        boolean useAllFields = retainFields == null || retainFields.isEmpty();
        if (!useAllFields) {
            for (String field : retainFields) {
                if (field != null && "all".equalsIgnoreCase(field.trim())) {
                    useAllFields = true;
                    break;
                }
            }
        }

        if (useAllFields) {
            log.info("维度{}的保留字段未指定或为all，默认保留事件源全部字段，retainFields={}",
                    dimension.getDimensionName(), retainFields);
            // 从事件源字段信息中获取全部字段名
            List<String> allFieldNames = new ArrayList<>();
            if (doc.getEventSourceFields() != null) {
                for (RequirementDoc.EventSourceField field : doc.getEventSourceFields()) {
                    if (field.getName() != null && !field.getName().isEmpty()) {
                        allFieldNames.add(field.getName().toUpperCase());
                    }
                }
            }
            return allFieldNames;
        }

        log.info("维度{}的保留字段已指定：{}", dimension.getDimensionName(), retainFields);
        return retainFields;
    }

    /**
     * 按保留字段列表过滤字段信息
     * 只保留retainFields中指定的字段（不区分大小写）
     *
     * @param fieldList 字段信息列表（从createSql解析）
     * @param retainFields 保留字段列表
     * @return 过滤后的字段信息列表
     */
    private List<TqFieldInfo> filterFieldsByRetainList(List<TqFieldInfo> fieldList, List<String> retainFields) {
        if (retainFields == null || retainFields.isEmpty()) {
            return fieldList;
        }

        // 构建保留字段集合（大写）
        Map<String, Boolean> retainFieldSet = new HashMap<>();
        for (String field : retainFields) {
            if (field != null && !field.trim().isEmpty()) {
                retainFieldSet.put(field.trim().toUpperCase(), true);
            }
        }

        // 过滤字段列表
        List<TqFieldInfo> filteredList = new ArrayList<>();
        for (TqFieldInfo fieldInfo : fieldList) {
            String fieldName = fieldInfo.getFieldName().toUpperCase();
            if (retainFieldSet.containsKey(fieldName)) {
                filteredList.add(fieldInfo);
            }
        }
        // 重新设置序号
        for (int i = 0; i < filteredList.size(); i++) {
            filteredList.get(i).setSerialNumber(i + 1);
        }

        return filteredList;
    }

    /**
     * 创建TqFieldInfo对象（结合模式）
     * 字段类型从Iteration1ResultDTO获取，描述从事件源字段信息获取
     *
     * @param serialNumber 序号
     * @param fieldName 字段名
     * @param fieldDescMap 字段描述映射
     * @param llmFieldMap 大模型字段映射
     * @return TqFieldInfo对象
     */
    private TqFieldInfo createTqFieldInfoCombined(int serialNumber, String fieldName,
                                                  Map<String, String> fieldDescMap,
                                                  Map<String, Iteration1ResultDTO.LlmField> llmFieldMap) {
        TqFieldInfo fieldInfo = new TqFieldInfo();
        fieldInfo.setSerialNumber(serialNumber);
        fieldInfo.setFieldName(fieldName);

        // 从大模型解析结果获取字段类型
        Iteration1ResultDTO.LlmField llmField = llmFieldMap.get(fieldName);
        if (llmField != null && llmField.getDataType() != null) {
            setFieldType(fieldInfo, llmField.getDataType());
        } else {
            // 默认VARCHAR
            fieldInfo.setFieldType("VARCHAR");
        }

        // 字段描述从事件源字段信息获取
        fieldInfo.setComment(fieldDescMap.getOrDefault(fieldName, ""));

        // 精度预留
        fieldInfo.setDecimalPrecision("");
        fieldInfo.setLength("");

        // 非空、默认值预留
        fieldInfo.setNotNull("");
        fieldInfo.setDefaultValue("");
        fieldInfo.setPrimaryKey("");
        fieldInfo.setPartitionKey("");
        return fieldInfo;
    }

    /**
     * 从需求文档中提取当前维度的字段信息
     *
     * @param doc 需求文档
     * @param dimension 维度信息
     * @return 字段信息列表
     */
    private List<TqFieldInfo> extractFieldInfoFromRequirement(RequirementDoc doc, RequirementDoc.DimensionInfo dimension) {
        List<TqFieldInfo> fieldList = new ArrayList<>();

        if (doc.getEventSourceFields() == null || doc.getEventSourceFields().isEmpty()) {
            log.warn("事件源字段信息为空");
            return fieldList;
        }

        // 构建事件源字段映射（name -> description）
        Map<String, String> fieldDescMap = new HashMap<>();
        for (RequirementDoc.EventSourceField field : doc.getEventSourceFields()) {
            if (field.getName() != null) {
                fieldDescMap.put(field.getName().toUpperCase(), field.getDescription());
            }
        }

        // 获取当前维度的保留字段
        // 选填：不填或填写all表示默认保留事件源全部字段；填写指定字段则只保留指定字段
        List<String> retainFields = resolveRetainFields(doc, dimension);

        // 获取当前维度的主键字段
        List<String> primaryKeys = dimension.getPrimaryKeys();
        String partitionKey = dimension.getPartitionKey();

        // 主键字段顺序映射
        Map<String, Integer> primaryKeyOrder = new HashMap<>();
        if (primaryKeys != null) {
            for (int i = 0; i < primaryKeys.size(); i++) {
                primaryKeyOrder.put(primaryKeys.get(i).toUpperCase(), i);
            }
        }

        // 序号计数器
        int serialNumber = 1;

        // 先添加主键字段（按主键顺序）
        if (primaryKeys != null) {
            for (String pkField : primaryKeys) {
                String fieldName = pkField.toUpperCase();
                TqFieldInfo fieldInfo = createTqFieldInfo(serialNumber++, fieldName, fieldDescMap);
                fieldInfo.setPrimaryKey("TRUE");
                // 分区键也是主键的情况
                if (partitionKey != null && fieldName.equals(partitionKey.toUpperCase())) {
                    fieldInfo.setPartitionKey("TRUE");
                }
                fieldList.add(fieldInfo);
            }
        }

        // 再添加非主键的保留字段
        for (String retainField : retainFields) {
            String fieldName = retainField.toUpperCase();
            // 跳过已经作为主键添加的字段
            if (primaryKeyOrder.containsKey(fieldName)) {
                continue;
            }
            TqFieldInfo fieldInfo = createTqFieldInfo(serialNumber++, fieldName, fieldDescMap);
            // 标记分区键
            if (partitionKey != null && fieldName.equals(partitionKey.toUpperCase())) {
                fieldInfo.setPartitionKey("TRUE");
            }
            fieldList.add(fieldInfo);
        }

        return fieldList;
    }
    /**
     * 创建TqFieldInfo对象
     *
     * @param serialNumber 序号
     * @param fieldName 字段名
     * @param fieldDescMap 字段描述映射
     * @return TqFieldInfo对象
     */
    private TqFieldInfo createTqFieldInfo(int serialNumber, String fieldName, Map<String, String> fieldDescMap) {
        TqFieldInfo fieldInfo = new TqFieldInfo();
        fieldInfo.setSerialNumber(serialNumber);
        fieldInfo.setFieldName(fieldName);
        // 字段类型默认VARCHAR，后续可从大模型解析结果补充
        fieldInfo.setFieldType("VARCHAR");
        // 字段描述从事件源字段信息获取
        fieldInfo.setComment(fieldDescMap.getOrDefault(fieldName, ""));
        // 精度预留
        fieldInfo.setDecimalPrecision("");
        fieldInfo.setLength("");
        // 非空、默认值预留
        fieldInfo.setNotNull("");
        fieldInfo.setDefaultValue("");
        fieldInfo.setPrimaryKey("");
        fieldInfo.setPartitionKey("");
        return fieldInfo;
    }

    /**
     * 创建表头样式
     *
     * @param workbook 工作簿
     * @return 表头样式
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        return headerStyle;
    }

    /**
     * 创建普通单元格样式
     *
     * @param workbook 工作簿
     * @return 单元格样式
     */
    private CellStyle createCellStyle(Workbook workbook) {
        CellStyle cellStyle = workbook.createCellStyle();
        cellStyle.setBorderBottom(BorderStyle.THIN);
        cellStyle.setBorderTop(BorderStyle.THIN);
        cellStyle.setBorderLeft(BorderStyle.THIN);
        cellStyle.setBorderRight(BorderStyle.THIN);
        cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        return cellStyle;
    }

    /**
     * 创建Sheet页
     *
     * @param workbook 工作簿
     * @param fieldList 字段信息列表
     * @param sheetName Sheet名称
     * @param headerStyle 表头样式
     * @param cellStyle 单元格样式
     */
    private void createSheet(Workbook workbook, List<TqFieldInfo> fieldList, String sheetName,
                             CellStyle headerStyle, CellStyle cellStyle) {
        Sheet sheet = workbook.createSheet(sheetName);

        // 创建表头（10列：序号/字段名/类型/精度(小数)/精度(长度)/非空/默认值/主键/分区键/注释）
        Row headerRow = sheet.createRow(0);
        String[] headers = {"序号", "字段名", "字段类型", "精度", "精度", "非空", "默认值", "主键", "分区键", "注释"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 设置列宽
        sheet.setColumnWidth(0, 8 * 256);   // 序号
        sheet.setColumnWidth(1, 20 * 256);  // 字段名
        sheet.setColumnWidth(2, 15 * 256);  // 字段类型
        sheet.setColumnWidth(3, 10 * 256);  // 精度(小数)
        sheet.setColumnWidth(4, 10 * 256);  // 精度(长度)
        sheet.setColumnWidth(5, 8 * 256);   // 非空
        sheet.setColumnWidth(6, 10 * 256);  // 默认值
        sheet.setColumnWidth(7, 8 * 256);   // 主键
        sheet.setColumnWidth(8, 8 * 256);   // 分区键
        sheet.setColumnWidth(9, 30 * 256);  // 注释

        // 写入数据行
        for (int i = 0; i < fieldList.size(); i++) {
            TqFieldInfo fieldInfo = fieldList.get(i);
            Row row = sheet.createRow(i + 1);

            createCell(row, 0, fieldInfo.getSerialNumber().toString(), cellStyle);
            createCell(row, 1, fieldInfo.getFieldName(), cellStyle);
            createCell(row, 2, fieldInfo.getFieldType(), cellStyle);
            createCell(row, 3, fieldInfo.getDecimalPrecision(), cellStyle);
            createCell(row, 4, fieldInfo.getLength(), cellStyle);
            createCell(row, 5, fieldInfo.getNotNull(), cellStyle);
            createCell(row, 6, fieldInfo.getDefaultValue(), cellStyle);
            createCell(row, 7, fieldInfo.getPrimaryKey(), cellStyle);
            createCell(row, 8, fieldInfo.getPartitionKey(), cellStyle);
            createCell(row, 9, fieldInfo.getComment(), cellStyle);
        }
    }

    /**
     * 根据Iteration1ResultDTO生成天启Excel文件
     *
     * @param iteration1Result 大模型解析结果
     * @param tableName 表名（如TBL_XXX）
     * @return 生成的Excel文件路径
     */
    public String generateTqExcel(Iteration1ResultDTO iteration1Result, String tableName) {
        try {
            // 提取字段信息
            List<TqFieldInfo> fieldList = extractFieldInfo(iteration1Result);
            if (fieldList.isEmpty()) {
                log.warn("未提取到任何字段信息，tableName: {}", tableName);
                return null;
            }

            // 解析createSql，补充非空和主键信息
            parseCreateSql(iteration1Result.getCreateSql(), fieldList);

            // 生成Excel文件名
            String fileName = tableName + "_天启建表语句.xlsx";
            String filePath = OUTPUT_DIR + fileName;

            // 创建Excel文件（单表模式，Sheet名为表名）
            createExcelFile(fieldList, tableName, filePath);

            log.info("天启Excel生成成功，表名：{}，文件路径：{}", tableName, filePath);
            return filePath;

        } catch (Exception e) {
            log.error("天启Excel生成失败，表名：{}", tableName, e);
            return null;
        }
    }
    /**
     * 从Iteration1ResultDTO中提取字段信息
     *
     * @param iteration1Result 大模型解析结果
     * @return 字段信息列表
     */
    private List<TqFieldInfo> extractFieldInfo(Iteration1ResultDTO iteration1Result) {
        List<TqFieldInfo> fieldList = new ArrayList<>();

        if (iteration1Result == null || iteration1Result.getLlmResult() == null) {
            log.warn("Iteration1ResultDTO或llmResult为空");
            return fieldList;
        }

        List<Iteration1ResultDTO.LlmField> llmOut = iteration1Result.getLlmResult().getLlmOut();

        if (llmOut == null || llmOut.isEmpty()) {
            log.warn("llmOut为空");
            return fieldList;
        }

        // 序号计数器
        int serialNumber = 1;

        // 遍历llmOut，提取字段信息
        for (Iteration1ResultDTO.LlmField fieldObj : llmOut) {
            TqFieldInfo fieldInfo = new TqFieldInfo();

            // 序号
            fieldInfo.setSerialNumber(serialNumber++);

            // 字段名（大写）- 从column_name获取
            String columnName = fieldObj.getColumn_name();
            fieldInfo.setFieldName(columnName != null ? columnName.toUpperCase() : "");

            // 字段类型 - 从dataType转换
            String dataType = fieldObj.getDataType();
            setFieldType(fieldInfo, dataType);

            // 字段描述（注释）
            fieldInfo.setComment(fieldObj.getDescription());

            // 精度（小数位和长度）预留
            fieldInfo.setDecimalPrecision("");
            fieldInfo.setLength("");

            // 非空、主键、分区键等字段预留
            fieldInfo.setNotNull("");
            fieldInfo.setDefaultValue("");
            fieldInfo.setPrimaryKey("");
            fieldInfo.setPartitionKey("");

            fieldList.add(fieldInfo);
        }

        return fieldList;
    }
    /**
     * 解析createSql，补充非空和主键信息
     *
     * @param createSql 建表SQL语句
     * @param fieldList 字段信息列表
     */
    private void parseCreateSql(String createSql, List<TqFieldInfo> fieldList) {
        if (createSql == null || createSql.isEmpty() || fieldList.isEmpty()) {
            return;
        }

        // 构建字段名到字段信息的映射（大写）
        Map<String, TqFieldInfo> fieldMap = new HashMap<>();
        for (TqFieldInfo fieldInfo : fieldList) {
            if (fieldInfo.getFieldName() != null && !fieldInfo.getFieldName().isEmpty()) {
                fieldMap.put(fieldInfo.getFieldName().toUpperCase(), fieldInfo);
            }
        }

        // 解析NOT NULL标记
        parseNotNullFromCreateSql(createSql, fieldMap);

        // 解析PRIMARY KEY
        parsePrimaryKeyFromCreateSql(createSql, fieldMap, fieldList);
    }

    /**
     * 从createSql中解析NOT NULL标记
     * 格式示例：BSR_NTB_NBR VARCHAR(8 BYTES) NOT NULL
     *
     * @param createSql 建表SQL语句
     * @param fieldMap 字段名到字段信息的映射
     */
    private void parseNotNullFromCreateSql(String createSql, Map<String, TqFieldInfo> fieldMap) {
        // 匹配 字段名 ... NOT NULL 的模式
        Pattern notNullPattern = Pattern.compile(
                "(?i)([A-Za-z_][A-Za-z0-9_]*)\\s+\\w+(?:\\([^)]*\\))?\\s+NOT\\s+NULL");
        Matcher matcher = notNullPattern.matcher(createSql);

        while (matcher.find()) {
            String fieldName = matcher.group(1).toUpperCase();
            TqFieldInfo fieldInfo = fieldMap.get(fieldName);
            if (fieldInfo != null) {
                fieldInfo.setNotNull("TRUE");
            }
        }
    }
    /**
     * 从createSql中解析PRIMARY KEY
     * 格式示例：PRIMARY KEY (ID, CUSTOMER_NUMBER)
     * 主键字段按从左到右的顺序在Excel中从上往下排列
     *
     * @param createSql 建表SQL语句
     * @param fieldMap 字段名到字段信息的映射
     * @param fieldList 字段信息列表
     */
    private void parsePrimaryKeyFromCreateSql(String createSql, Map<String, TqFieldInfo> fieldMap, List<TqFieldInfo> fieldList) {
        // 匹配 PRIMARY KEY (字段1, 字段2, ...) 的模式
        Pattern primaryKeyPattern = Pattern.compile(
                "(?i)PRIMARY\\s+KEY\\s*\\(([^)]+)\\)");
        Matcher matcher = primaryKeyPattern.matcher(createSql);

        if (matcher.find()) {
            String primaryKeyFields = matcher.group(1);
            // 按逗号分割主键字段
            String[] primaryKeyFieldArray = primaryKeyFields.split(",");
            // 记录主键字段及其顺序
            Map<String, Integer> primaryKeyOrder = new HashMap<>();
            for (int i = 0; i < primaryKeyFieldArray.length; i++) {
                String fieldName = primaryKeyFieldArray[i].trim().toUpperCase();
                primaryKeyOrder.put(fieldName, i);
                // 标记主键字段
                TqFieldInfo fieldInfo = fieldMap.get(fieldName);
                if (fieldInfo != null) {
                    fieldInfo.setPrimaryKey("TRUE");
                }
            }

            // 重新排序字段列表：主键字段按PRIMARY KEY中的顺序排在前面，非主键字段保持原顺序
            reorderFieldList(fieldList, primaryKeyOrder);
        }
    }

    /**
     * 重新排序字段列表
     * 主键字段按PRIMARY KEY中的顺序从上往下排列，非主键字段保持原顺序
     *
     * @param fieldList 字段信息列表
     * @param primaryKeyOrder 主键字段顺序映射（字段名 -> 顺序索引）
     */
    private void reorderFieldList(List<TqFieldInfo> fieldList, Map<String, Integer> primaryKeyOrder) {
        // 分离主键字段和非主键字段
        List<TqFieldInfo> primaryKeyFields = new ArrayList<>();
        List<TqFieldInfo> nonPrimaryKeyFields = new ArrayList<>();

        for (TqFieldInfo fieldInfo : fieldList) {
            String fieldName = fieldInfo.getFieldName().toUpperCase();
            if (primaryKeyOrder.containsKey(fieldName)) {
                primaryKeyFields.add(fieldInfo);
            } else {
                nonPrimaryKeyFields.add(fieldInfo);
            }
        }

        // 按PRIMARY KEY中的顺序对主键字段排序
        primaryKeyFields.sort((f1, f2) -> {
            Integer order1 = primaryKeyOrder.get(f1.getFieldName().toUpperCase());
            Integer order2 = primaryKeyOrder.get(f2.getFieldName().toUpperCase());
            return order1.compareTo(order2);
        });

        // 清空原列表，重新添加排序后的字段
        fieldList.clear();
        fieldList.addAll(primaryKeyFields);
        fieldList.addAll(nonPrimaryKeyFields);

        // 重新设置序号
        for (int i = 0; i < fieldList.size(); i++) {
            fieldList.get(i).setSerialNumber(i + 1);
        }
    }
    /**
     * 重新排序字段列表：主键字段按PRIMARY KEY中的顺序排在前面，非主键字段保持原顺序
     * 通过canonicalField匹配主键（去除下划线的小写形式匹配）
     *
     * @param fieldList 字段信息列表
     * @param primaryKeyOrder 主键字段顺序映射（去除下划线的小写canonicalField -> 顺序索引）
     * @param llmFieldMap 大模型字段映射（大写column_name -> LlmField）
     */
    private void reorderFieldList(List<TqFieldInfo> fieldList, Map<String, Integer> primaryKeyOrder,
                                  Map<String, Iteration1ResultDTO.LlmField> llmFieldMap) {
        // 分离主键字段和非主键字段
        List<TqFieldInfo> primaryKeyFields = new ArrayList<>();
        List<TqFieldInfo> nonPrimaryKeyFields = new ArrayList<>();

        for (TqFieldInfo fieldInfo : fieldList) {
            String fieldName = fieldInfo.getFieldName().toUpperCase();
            // 从llmFieldMap获取canonicalField，用于匹配主键
            Iteration1ResultDTO.LlmField llmField = llmFieldMap.get(fieldName);
            if (llmField != null && llmField.getCanonicalField() != null) {
                String canonicalNorm = llmField.getCanonicalField().toLowerCase().replace("_", "");
                if (primaryKeyOrder.containsKey(canonicalNorm)) {
                    primaryKeyFields.add(fieldInfo);
                } else {
                    nonPrimaryKeyFields.add(fieldInfo);
                }
            } else {
                nonPrimaryKeyFields.add(fieldInfo);
            }
        }

        // 按PRIMARY KEY中的顺序对主键字段排序
        primaryKeyFields.sort((f1, f2) -> {
            Iteration1ResultDTO.LlmField llm1 = llmFieldMap.get(f1.getFieldName().toUpperCase());
            Iteration1ResultDTO.LlmField llm2 = llmFieldMap.get(f2.getFieldName().toUpperCase());
            String norm1 = llm1 != null && llm1.getCanonicalField() != null
                    ? llm1.getCanonicalField().toLowerCase().replace("_", "") : "";
            String norm2 = llm2 != null && llm2.getCanonicalField() != null
                    ? llm2.getCanonicalField().toLowerCase().replace("_", "") : "";
            Integer order1 = primaryKeyOrder.get(norm1);
            Integer order2 = primaryKeyOrder.get(norm2);
            return order1.compareTo(order2);
        });

        // 清空原列表，重新添加排序后的字段
        fieldList.clear();
        fieldList.addAll(primaryKeyFields);
        fieldList.addAll(nonPrimaryKeyFields);

        // 重新设置序号
        for (int i = 0; i < fieldList.size(); i++) {
            fieldList.get(i).setSerialNumber(i + 1);
        }
    }

    /**
     * 创建Excel文件（单表模式，Sheet名为表名）
     *
     * @param fieldList 字段信息列表
     * @param tableName 表名（Sheet名）
     * @param filePath 文件路径
     */
    private void createExcelFile(List<TqFieldInfo> fieldList, String tableName, String filePath) throws IOException {
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(tableName);

        // 创建表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // 创建普通单元格样式
        CellStyle cellStyle = workbook.createCellStyle();
        cellStyle.setBorderBottom(BorderStyle.THIN);
        cellStyle.setBorderTop(BorderStyle.THIN);
        cellStyle.setBorderLeft(BorderStyle.THIN);
        cellStyle.setBorderRight(BorderStyle.THIN);
        cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // 创建表头（10列：序号/字段名/类型/精度(小数)/精度(长度)/非空/默认值/主键/分区键/注释）
        Row headerRow = sheet.createRow(0);
        String[] headers = {"序号", "字段名", "字段类型", "精度", "精度", "非空", "默认值", "主键", "分区键", "注释"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        // 设置列宽
        sheet.setColumnWidth(0, 8 * 256);   // 序号
        sheet.setColumnWidth(1, 20 * 256);  // 字段名
        sheet.setColumnWidth(2, 15 * 256);  // 字段类型
        sheet.setColumnWidth(3, 10 * 256);  // 精度(小数)
        sheet.setColumnWidth(4, 10 * 256);  // 精度(长度)
        sheet.setColumnWidth(5, 8 * 256);   // 非空
        sheet.setColumnWidth(6, 10 * 256);  // 默认值
        sheet.setColumnWidth(7, 8 * 256);   // 主键
        sheet.setColumnWidth(8, 8 * 256);   // 分区键
        sheet.setColumnWidth(9, 30 * 256);  // 注释

        // 写入数据行
        for (int i = 0; i < fieldList.size(); i++) {
            TqFieldInfo fieldInfo = fieldList.get(i);
            Row row = sheet.createRow(i + 1);

            // 序号
            createCell(row, 0, fieldInfo.getSerialNumber().toString(), cellStyle);
            // 字段名
            createCell(row, 1, fieldInfo.getFieldName(), cellStyle);
            // 字段类型
            createCell(row, 2, fieldInfo.getFieldType(), cellStyle);
            // 精度(小数)
            createCell(row, 3, fieldInfo.getDecimalPrecision(), cellStyle);
            // 精度(长度)
            createCell(row, 4, fieldInfo.getLength(), cellStyle);
            // 非空
            createCell(row, 5, fieldInfo.getNotNull(), cellStyle);
            // 默认值
            createCell(row, 6, fieldInfo.getDefaultValue(), cellStyle);
            // 主键
            createCell(row, 7, fieldInfo.getPrimaryKey(), cellStyle);
            // 分区键（预留）
            createCell(row, 8, fieldInfo.getPartitionKey(), cellStyle);
            // 注释
            createCell(row, 9, fieldInfo.getComment(), cellStyle);
        }

        // 写入文件
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }
    }
    /**
     * 创建单元格并设置值
     */
    private void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    /**
     * 根据dataType映射为天启字段类型
     *
     * @param fieldInfo 字段信息
     * @param dataType 原始数据类型
     */
    private void setFieldType(TqFieldInfo fieldInfo, String dataType) {
        if (dataType == null || dataType.isEmpty()) {
            fieldInfo.setFieldType("VARCHAR");
            return;
        }

        String upperDataType = dataType.toUpperCase();
        String fieldType;

        if (upperDataType.contains("STRING") || upperDataType.startsWith("VARCHAR") || upperDataType.startsWith("CHAR")) {
            fieldType = "VARCHAR";
        } else if (upperDataType.contains("DECIMAL") || upperDataType.contains("BIGDECIMAL")) {
            fieldType = "DECIMAL";
        } else if (upperDataType.contains("LONG") || upperDataType.contains("INT")) {
            fieldType = "INT";
        } else if (upperDataType.contains("TIMESTAMP") || upperDataType.contains("DATE")) {
            fieldType = "TIMESTAMP";
        } else if (upperDataType.contains("BOOLEAN") || upperDataType.contains("BOOL")) {
            fieldType = "INT";
        } else {
            fieldType = "VARCHAR";
        }

        fieldInfo.setFieldType(fieldType);
    }
}
