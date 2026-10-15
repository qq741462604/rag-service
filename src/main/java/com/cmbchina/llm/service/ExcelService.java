package com.cmbchina.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExcelService {

    public String parseExcelToJSON(MultipartFile file) throws IOException {
        InputStream inputStream = file.getInputStream();
        Workbook workbook = new XSSFWorkbook(inputStream);
        Sheet sheet = workbook.getSheetAt(0);

        Map<String, Integer> columnMap = new HashMap<>();
        Row headerRow = sheet.getRow(0);
        for (Cell cell : headerRow) {
            columnMap.put(cell.getStringCellValue(), cell.getColumnIndex());
        }

        int nameIndex = columnMap.getOrDefault("name", -1);
        int descriptionIndex = columnMap.getOrDefault("description", -1);

        if (nameIndex == -1 || descriptionIndex == -1) {
            throw new IllegalArgumentException("Excel文件中缺少name或description列");
        }

        List<Map<String, String>> dataList = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                Map<String, String> data = new HashMap<>();
                Cell nameCell = getMergedCell(sheet, i, nameIndex);
                Cell descriptionCell = getMergedCell(sheet, i, descriptionIndex);
                data.put("name", nameCell.getStringCellValue());
                data.put("description", descriptionCell.getStringCellValue());
                dataList.add(data);
            }
        }

        // 去重逻辑
        Set<String> uniqueNames = new HashSet<>();
        List<Map<String, String>> uniqueDataList = new ArrayList<>();
        for (Map<String, String> data : dataList) {
            if (!uniqueNames.contains(data.get("name"))) {
                uniqueNames.add(data.get("name"));
                uniqueDataList.add(data);
            }
        }

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(uniqueDataList);
        workbook.close();
        inputStream.close();

        return json;
    }

    private static Cell getMergedCell(Sheet sheet, int rowIndex, int columnIndex) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress mergedRegion = sheet.getMergedRegion(i);
            if (mergedRegion.isInRange(rowIndex, columnIndex)) {
                return sheet.getRow(mergedRegion.getFirstRow()).getCell(mergedRegion.getFirstColumn());
            }
        }
        return sheet.getRow(rowIndex).getCell(columnIndex);
    }
}