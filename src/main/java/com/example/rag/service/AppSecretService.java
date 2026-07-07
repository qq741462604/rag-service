package com.example.rag.service;


import com.example.rag.config.AuthProperties;
import org.springframework.stereotype.Service;

/**
 * AppSecretService（只做语义判断）
 */
@Service
public class AppSecretService {

    private final AuthProperties properties;

    public AppSecretService(AuthProperties properties) {
        this.properties = properties;
    }

    public boolean exists(String appId) {
        return properties.getAppSecrets().containsKey(appId);
    }

    public String getSecret(String appId) {
        return properties.getAppSecrets().get(appId);
    }
}
