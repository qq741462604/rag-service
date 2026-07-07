package com.example.rag.interceptor;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Filter 用 HttpServletRequestWrapper 包装请求
 *
 * 把原始 InputStream 复制到 byte[]
 *
 * Interceptor 使用 复制的 byte[] 做签名
 *
 * Controller 继续使用原始 InputStream（用 Wrapper 重新提供）
 */
public class MultiReadHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public MultiReadHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        InputStream is = request.getInputStream();
        this.body = toByteArray(is);
    }

    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bis = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() { return bis.available() == 0; }
            @Override
            public boolean isReady() { return true; }
            @Override
            public void setReadListener(ReadListener readListener) {}
            @Override
            public int read() throws IOException { return bis.read(); }
        };
    }

    public String getBodyString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
