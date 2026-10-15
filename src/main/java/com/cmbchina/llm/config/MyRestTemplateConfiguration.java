package com.cmbchina.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2025/11/12
 */
@Configuration
public class MyRestTemplateConfiguration {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(clientHttpRequestFactory());
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30 * 60 * 1000); // 连接超时时间，单位毫秒（4分钟）
        factory.setReadTimeout(30 * 60 * 1000); // 读取超时时间，单位毫秒（4分钟）
        return factory;
    }
}
