//package com.example.rag.interceptor;
//
//import org.springframework.core.annotation.Order;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//import org.springframework.web.util.ContentCachingRequestWrapper;
//
//import javax.servlet.FilterChain;
//import javax.servlet.ServletException;
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//import java.io.IOException;
//import java.io.InputStream;
//
//@Component
//@Order(0)
//public class RequestBodyCacheFilter extends OncePerRequestFilter {
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request,
//                                    HttpServletResponse response,
//                                    FilterChain filterChain)
//            throws ServletException, IOException {
//
//        // 避免重复包装
//        if (!(request instanceof ContentCachingRequestWrapper)) {
//            request = new ContentCachingRequestWrapper(request);
//        }
//
//        // 不要在这里提前读取 InputStream！
//        // 只包装即可
//        filterChain.doFilter(request, response);
//    }
//}
//
//
////public class RequestBodyCacheFilter extends OncePerRequestFilter {
////
////    @Override
////    protected void doFilterInternal(
////            HttpServletRequest request,
////            HttpServletResponse response,
////            FilterChain filterChain
////    ) throws ServletException, IOException {
////
////        // 如果已经是 ContentCachingRequestWrapper，就不要重复包装
////        if (request instanceof ContentCachingRequestWrapper) {
////            filterChain.doFilter(request, response);
////            return;
////        }
////
////        ContentCachingRequestWrapper wrapper =
////                new ContentCachingRequestWrapper(request);
////
////        /*
////         * 🔥 关键点：
////         * ContentCachingRequestWrapper 只有在“读取 InputStream 时”
////         * 才会缓存 body，所以这里必须主动读一遍
////         *
////         * Java 8 没有 readAllBytes()，用 while 循环触发读取
////         */
////        InputStream inputStream = wrapper.getInputStream();
////        byte[] buffer = new byte[1024];
////        while (inputStream.read(buffer) != -1) {
////            // 只为触发读取和缓存，不需要处理内容
////        }
////
////        // 继续请求链，后续 Interceptor / Controller
////        // 都将从缓存中读取 body
////        filterChain.doFilter(wrapper, response);
////    }
////}
