### 2.3 HttpClient 工具类（连接池、超时、重试、链路追踪）

> 完整工具类示例，连接池、超时、重试、链路追踪均从 `ReadConfig` 读取，禁止硬编码。
> 该工具类为固定写法，直接复用即可；若目标项目已有等价工具类，则复用已有类，不重复创建。

```java
package com.cmbchina.plugin.utils;


import com.cmb.bee.tracer.codec.HttpClientInjectAdapter;
import com.cmb.bee.tracer.util.BeeTraceClientHelper;
import com.cmbchina.plugin.config.ReadConfig;
import com.cmbchina.plugin.exception.CommonException;
import io.opentracing.Scope;
import io.opentracing.propagation.Format;
import org.apache.http.HttpEntity;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

// @Component
public class HttpClientUtil {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientUtil.class);

    public static ReadConfig readConfig = SpringContextBeanUtil.getApplicationContext().getBean(ReadConfig.class);

    // 1、在需要自定义埋点处注入该Bean ： BeeTraceClientHelper
    public static BeeTraceClientHelper beeTraceClientHelper = SpringContextBeanUtil.getApplicationContext().getBean(BeeTraceClientHelper.class);

    private static final PoolingHttpClientConnectionManager connectionManager;


    private CloseableHttpClient httpClient;


    static {
        // 连接池
        connectionManager = new PoolingHttpClientConnectionManager();
        // 初始化
        init();
    }

    public HttpClientUtil() {
        HttpClientBuilder hb = HttpClients.custom();
        hb.setConnectionManager(connectionManager);

        // 连接超时时间，指的是建立连接的超时时间
        int connTimeout = readConfig.getHttpConnectionTimeout();
        // 响应超时时间，连接建立以后，读数据,等待数据超时时间
        int socketTime = readConfig.getHttpSocketTimeout();
        // 连接不够的时候，从池中获取连接的超时等待时间
        int connectionRequestTimeout = readConfig.getHttpConnectionrequestTimeout();
        // 失败重试次数
        int retryTimes = readConfig.getHttpRetryTimes();
        // 是否开启重试机制
        boolean requestSentRetryEnabled = false;
        if (readConfig.getHttpRequestSentRetryEnabled() && retryTimes > 0) {
            requestSentRetryEnabled = true;
        }

        // 设置超时时间
        RequestConfig.Builder rb = RequestConfig.custom();
        rb.setSocketTimeout(socketTime);
        rb.setConnectTimeout(connTimeout);
        rb.setConnectionRequestTimeout(connectionRequestTimeout);
        hb.setDefaultRequestConfig(rb.build());

     // 最大空闲时间，超过该空闲时间即清理
        int connectionMaxIdleTime = readConfig.getHttpConnectionMaxidletime();
        // 开启定期回收过期连接
        hb.evictExpiredConnections();
        // 开启定期回收空闲连接
        hb.evictIdleConnections(connectionMaxIdleTime, TimeUnit.MILLISECONDS);
        // 设置重试次数，httpClient默认的重试次数为3
        hb.setRetryHandler(new DefaultHttpRequestRetryHandler(retryTimes, requestSentRetryEnabled));

        // 获取 httpClient
        httpClient = hb.build();
    }


    public static void init() {
        // 连接池最大连接数
        int maxTotalConnections = readConfig.getHttpMaxtotalConnections();
        // 路由默认最大连接
        int maxPerRouteConnections = readConfig.getHttpMaxperrouteConnections();
        // 最大空闲时间，超过该空闲时间即清理
        int connectionMaxIdleTime = readConfig.getHttpConnectionMaxidletime();

        // 整个连接池最大连接数
        connectionManager.setMaxTotal(maxTotalConnections);
        // 路由的默认最大连接（只有一个路由，可以设置为连接池最大连接数）
        connectionManager.setDefaultMaxPerRoute(maxPerRouteConnections);
        // 关闭空闲时间超过 connectionMaxIdleTime 的连接，空闲时间从交还给连接池时开始，不管是否已过期，超过空闲时间则关闭。
        connectionManager.closeIdleConnections(connectionMaxIdleTime, TimeUnit.MICROSECONDS);
        SocketConfig.Builder sb = SocketConfig.custom();
        // 开启监视 TCP 连接是否有效，使用setKeepAlive 会向对方发送 一个空的ACK包，来保持通信。
        sb.setSoKeepAlive(true);
        // 是否立即发送数据，设置为 true 会关闭 Socket 缓冲，默认为 false
        sb.setTcpNoDelay(true);
        connectionManager.setDefaultSocketConfig(sb.build());
    }

    public <T> T invoke(RequestBuilder requestBuilder, String url, Map<String, String> headers, HttpEntity httpEntity,
                        ResponseHandler<T> responseHandler) {
        try {
            return invoke0(requestBuilder, url, headers, httpEntity, responseHandler);
        } catch (Exception e) {
            throw new CommonException(e.getMessage());
        }
    }

    private <T> T invoke0(RequestBuilder builder, String url, Map<String, String> headers, HttpEntity httpEntity,
                          ResponseHandler<T> handler) {
        try {

            int doubleSlashIndex = url.indexOf("//");
            String host = "";
            String api = "";
            if (doubleSlashIndex != -1) {
                String hostAll = url.substring(doubleSlashIndex + 2);
                // 找到主机名之后的第一个斜杠
                int slashIndex = hostAll.indexOf("/");
                if (slashIndex != -1) {
                    // 提取API部分
                    api = hostAll.substring(slashIndex);
                    // 只保留主机名部分
                    host = hostAll.substring(0, slashIndex);
                }
            }
            // 1.可调用该方法设置链路日志的callStack中的subHost、subApi属性信息
            //   host：代表当前请求访问的接收方的host地址及端口号，如："localhost：8080"
            //   api ：代表所访问的接口名或作业条名，用于区分同一服务器下不同功能模块，如：local:8080/hi 中的"/hi"
            Scope scope = beeTraceClientHelper.startTracing(host, api);


            // 设置调用的 url
      builder.setUri(url);
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (HTTP.CONTENT_LEN.equalsIgnoreCase(e.getKey())) {
                        continue;
                    }
                    // 设置 header 头部参数
                    builder.addHeader(e.getKey(), e.getValue());
                }
            }

            if (null != httpEntity) {
                // 设置 body 体
                builder.setEntity(httpEntity);
            }
            long beginTime = System.currentTimeMillis();

            // 2.调用该方法可以将构建的链路信息存储到请求中，具体形参解释，可参考源码定义
            //   scope   startTracing方法的返回值
            //   format  定义的注入的格式，可自定义实现进行扩展，这里可以直接使用：Format.Builtin.HTTP_HEADERS
            //   carrier 链路信息的载体，可自定义扩展, 这里可以直接使用：HttpClientInjectAdapter
            beeTraceClientHelper.inject(scope, Format.Builtin.HTTP_HEADERS, new HttpClientInjectAdapter(builder.build()));
            String returnCode = "";

            try (CloseableHttpResponse response = (CloseableHttpResponse)httpClient.execute(builder.build())) {
                // 回调
                returnCode = response.getStatusLine().getStatusCode() + "";
                return handler.handleResponse(response);
            } finally {
                long timeCost = System.currentTimeMillis() - beginTime;
                logger.info("HttpClient execute costs time: {}",
                        timeCost);
                // 3. 结束链路追踪，该方法必须执行，所以一般放在finally中执行，具体形参解释，可参考源码定义
                //    scope      表示客户端scope，也即是当调用startTracing方法时的返回值
                //    returnCode 表示执行完请求，返回的响应状态码，最终体现在链路日志的callStack的subReturnCode字段中
                //    该方法存在重载方法：（1）returnCode可以直接自定义一个字符串（建议使用该方法）;(2) 可以传入响应状态码
                beeTraceClientHelper.finishTracing(scope, returnCode);
            }
        } catch (Exception e) {
            logger.error("Invoke AHI service failed");
            throw new CommonException(e.getMessage());
        }
    }
}
```