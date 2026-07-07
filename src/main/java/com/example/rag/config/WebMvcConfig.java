package com.example.rag.config;

import com.example.rag.interceptor.ApiAuthInterceptor;
import com.example.rag.interceptor.MultiReadHttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * WebMvcConfig（注册拦截器）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiAuthInterceptor apiAuthInterceptor;

    public WebMvcConfig(ApiAuthInterceptor apiAuthInterceptor) {
        this.apiAuthInterceptor = apiAuthInterceptor;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> multiReadFilter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain)
                    throws ServletException, IOException {
                MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(request);
                filterChain.doFilter(wrapped, response);
            }
        });
        bean.setOrder(0);
        return bean;
    }


    /**
     * Servlet 的“铁律”
     * 请求体（InputStream）只能被读取一次
     * 所以需要增加这个Filter：
     * 把这个“包一层 request 的 Filter”注册进 Spring 容器
     * 让 Interceptor 能“读取请求 body”，同时不影响 Controller 再次读取 body。
     * Order 越小 → 越早执行
     * @return
     */
//    @Bean
//    public FilterRegistrationBean<OncePerRequestFilter> requestWrapperFilter() {
//        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
//        bean.setFilter(new OncePerRequestFilter() {
//            @Override
//            protected void doFilterInternal(
//                    HttpServletRequest request,
//                    HttpServletResponse response,
//                    FilterChain filterChain
//            ) throws ServletException, IOException {
//                filterChain.doFilter(
//                        new ContentCachingRequestWrapper(request),
//                        response
//                );
//            }
//        });
//        bean.setOrder(0);
//        return bean;
//    }

    // 有点问题
//    @Bean
//    public FilterRegistrationBean<RequestBodyCacheFilter> requestBodyCacheFilter() {
//        FilterRegistrationBean<RequestBodyCacheFilter> bean =
//                new FilterRegistrationBean<>();
//
//        bean.setFilter(new RequestBodyCacheFilter());
//
//        // ⚠️ 必须最早执行，保证 Interceptor 前 body 已缓存
//        bean.setOrder(0);
//
//        // 可选：只拦需要鉴权的路径，减少开销
//        // bean.addUrlPatterns("/api/*");
//
//        return bean;
//    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuthInterceptor)
                .addPathPatterns("/api/**");
    }
}
