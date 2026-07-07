package com.example.rag.interceptor;


import com.example.rag.annotation.IgnoreAuth;
import com.example.rag.config.AuthProperties;
import com.example.rag.service.AppSecretService;
import com.example.rag.util.AuthResponseUtil;
import com.example.rag.util.HmacUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private final AuthProperties properties;
    private final AppSecretService appSecretService;

    public ApiAuthInterceptor(
            AuthProperties properties,
            AppSecretService appSecretService
    ) {
        this.properties = properties;
        this.appSecretService = appSecretService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws Exception {

        // ===== 灰度控制 =====
        if (!properties.isEnabled()) {
            return true;
        }

        // 1️⃣ 跳过 ERROR 派发（关键）
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }

        // ===== 非 Controller 请求 =====
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String uri = request.getRequestURI();

        // ===== 配置化排除路径（探针 / 框架）=====
        if (isExcludedPath(uri, properties.getExcludedPathPrefixes())) {
            return true;
        }

        HandlerMethod hm = (HandlerMethod) handler;

        // ===== 显式放行注解 =====
        if (hm.hasMethodAnnotation(IgnoreAuth.class)
                || hm.getBeanType().isAnnotationPresent(IgnoreAuth.class)) {
            return true;
        }

        // ===== 以下是强制鉴权逻辑 =====
        return doAuth(request, response);
    }

    private boolean doAuth(HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        String appId = request.getHeader("X-App-Id");
        String ts = request.getHeader("X-Timestamp");
        String sign = request.getHeader("X-Sign");

        if (StringUtils.isAnyBlank(appId, ts, sign)) {
//            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing auth headers");
            AuthResponseUtil.writeUnauthorized(
                    response,
                    40100,
                    "Missing auth headers"
            );
            return false;
        }

        if (!appSecretService.exists(appId)) {
//            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid appId");
            AuthResponseUtil.writeUnauthorized(
                    response,
                    40101,
                    "Invalid signature"
            );
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid timestamp");
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestamp) > properties.getTimeWindowSeconds()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Timestamp expired");
            return false;
        }

        // 🔥 关键：按 HTTP 方法决定 body
        String body = resolveBody(request);

        String signBase = appId + "\n" + ts + "\n" + body;
        String expectedSign = HmacUtil.hmacSha256(
                appSecretService.getSecret(appId),
                signBase
        );

        if (!MessageDigest.isEqual(
                expectedSign.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8)
        )) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid sign");
            return false;
        }

        return true;
    }

    private String resolveBody(HttpServletRequest request) {
        if (request instanceof MultiReadHttpServletRequest) {
            return ((MultiReadHttpServletRequest) request).getBodyString();
        }
        return "";
    }


//    private String resolveBody(HttpServletRequest request) {
//        String method = request.getMethod();
//
//        if ("POST".equalsIgnoreCase(method)
//                || "PUT".equalsIgnoreCase(method)
//                || "PATCH".equalsIgnoreCase(method)) {
//
//            if (request instanceof ContentCachingRequestWrapper) {
//                ContentCachingRequestWrapper wrapper =
//                        (ContentCachingRequestWrapper) request;
//                byte[] buf = wrapper.getContentAsByteArray();
//                if (buf.length > 0) {
//                    return new String(buf, StandardCharsets.UTF_8);
//                }
//            }
//        }
//        return "";
//    }

//    private String resolveBody(HttpServletRequest request) {
//        if (request instanceof ContentCachingRequestWrapper) {
//            ContentCachingRequestWrapper wrapper =
//                    (ContentCachingRequestWrapper) request;
//
//            // 🔑 注意这里：只有读取缓存才安全
//            byte[] buf = wrapper.getContentAsByteArray();
//
//            // 如果 buf 为空，可能 Controller 还没触发读取
//            // 可以先尝试触发 getReader()，确保缓存
//            if (buf == null || buf.length == 0) {
//                try {
//                    wrapper.getReader().lines().forEach(s -> {}); // 触发缓存
//                    buf = wrapper.getContentAsByteArray();
//                } catch (Exception e) {
//                    // 忽略
//                }
//            }
//
//            if (buf != null && buf.length > 0) {
//                return new String(buf, StandardCharsets.UTF_8);
//            }
//        }
//        return "";
//    }


    private boolean isExcludedPath(String uri, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
