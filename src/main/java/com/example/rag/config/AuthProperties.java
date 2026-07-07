package com.example.rag.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AuthProperties（统一读配置）
 */
@Configuration
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private boolean enabled = true;

    private long timeWindowSeconds = 300;

    /**
     * appId -> appSecret
     */
    private Map<String, String> appSecrets = new HashMap<>();

    /**
     * 鉴权排除路径前缀
     */
    private List<String> excludedPathPrefixes;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(long timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public Map<String, String> getAppSecrets() {
        return appSecrets;
    }

    public void setAppSecrets(Map<String, String> appSecrets) {
        this.appSecrets = appSecrets;
    }

    public List<String> getExcludedPathPrefixes() {
        return excludedPathPrefixes;
    }

    public void setExcludedPathPrefixes(List<String> excludedPathPrefixes) {
        this.excludedPathPrefixes = excludedPathPrefixes;
    }
}
