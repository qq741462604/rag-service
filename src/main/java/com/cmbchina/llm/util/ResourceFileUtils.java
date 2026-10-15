package com.cmbchina.llm.util;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2025/12/3
 */
import lombok.extern.slf4j.Slf4j;

import java.io.*;

@Slf4j
public class ResourceFileUtils {

    private ResourceFileUtils() {
    }

    /**
     * 只适合读取文件的加载
     * @param resourcePath
     * @return
     */
    public static File getResourceAsFile(String resourcePath){
        // 尝试本地文件系统
        File file = new File("src/main/resources/" + resourcePath);
        if (file.exists()) {
            return file;
        }

        // 尝试从 classpath 读取
        InputStream inputStream = ResourceFileUtils.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream != null) {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("tmp-", "-" + new File(resourcePath).getName());
                tempFile.deleteOnExit();
                copyInputStreamToFile(inputStream, tempFile);
                return tempFile;
            } catch (IOException e) {
                log.error("{}文件读取异常，{}", resourcePath, e);
            }

        }


        log.error("{}资源文件不存在", resourcePath);
        return null;

    }

    private static void copyInputStreamToFile(InputStream inputStream, File file) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(inputStream);
             FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
    }

}
