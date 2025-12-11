package com.example.rag.util;

import java.io.*;

public class ResourceUtils {

    public static File getResourceAsFile(String resourcePath) throws IOException {
        // 尝试本地文件系统
        File file = new File("src/main/resources/" + resourcePath);
        if (file.exists()) {
            return file;
        }

        // 尝试从 classpath 读取
        InputStream inputStream = ResourceUtils.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream != null) {
            File tempFile = File.createTempFile("tmp-", "-" + new File(resourcePath).getName());
            tempFile.deleteOnExit();
            copyInputStreamToFile(inputStream, tempFile);
            return tempFile;
        }

        throw new FileNotFoundException("Resource not found: " + resourcePath);
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

