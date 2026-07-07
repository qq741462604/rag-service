package com.example.rag.util;


import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一错误输出工具类
 */
public class AuthResponseUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void writeUnauthorized(
            HttpServletResponse response,
            int code,
            String message
    ) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", System.currentTimeMillis() / 1000);

        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
