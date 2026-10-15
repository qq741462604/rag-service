## 二、配置类（调用地址、超时时间等）

> 调用地址、超时时间**必须**来自配置文件，禁止硬编码到 Java 代码。
> 配置分两层：**application.yml 配值** + **Config 类读取**。下面给出通用骨架，实际按接口替换前缀和字段。

### 2.1 application.yml 配置段

```yaml
# 外调 AHI 接口配置（前缀与 Config 类 @ConfigurationProperties 一致）
# 外调接口配置
http:
  invoke:
    enable: true #模型接口开关
    url: http://rcprtzf.paasuat.cmbchina.cn/services/rest/api/predict # uat环境模型地址
    connection-timeout: 20 # 连接超时时间，
    socket-timeout: 300 # 响应超时时间
#    socket-timeout: 100 # 响应超时时间
  maxtotal-connections: 800 # 连接池最大连接数
  maxperroute-connections: 800 # 路由默认最大连接
  connectionrequest-timeout: 10 # 从池中获取连接的超时等待时间
  connection-maxidletime: 3600 # 最大空闲时间，超过该空闲时间即清理
  retry:
    enabled: false # http 是否重试
    times: 0 # http 重试次数
```

### 2.2 配置读取类（@ConfigurationProperties）

```java
package com.cmbchina.plugin.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * @description: some desc
 * @author: IT006901
 * @date: 2026/1/30
 */
@Data
@Configuration
@AllArgsConstructor
@NoArgsConstructor
public class ReadConfig {
    // 是否要调用 ahi 接口
    @Value("${http.invoke.enable:false}")
    private Boolean ahiHttpInvokeEnable;
    // ahi 访问地址
    @Value("${http.invoke.url}")
    private String ahiHttpBusinessUrl;

    // 模型连接超时时间
    @Value("${http.invoke.connection-timeout}")
    private Integer httpConnectionTimeout;
    // 模型响应超时时间
    @Value("${http.invoke.socket-timeout}")
    private Integer httpSocketTimeout;


    // 连接池最大连接数
    @Value("${http.maxtotal-connections}")
    private Integer httpMaxtotalConnections;
    // 路由默认最大连接
    @Value("${http.maxperroute-connections}")
    private Integer httpMaxperrouteConnections;

    // 从池中获取连接的超时等待时间
    @Value("${http.connectionrequest-timeout}")
    private Integer httpConnectionrequestTimeout;
    // 最大空闲时间，超过该空闲时间即清理
    @Value("${http.connection-maxidletime}")
    private Integer httpConnectionMaxidletime;

    // http 是否重试
    @Value("${http.retry.enabled}")
    private Boolean httpRequestSentRetryEnabled;

    // http 重试次数
    @Value("${http.retry.times}")
    private Integer httpRetryTimes;



}

```