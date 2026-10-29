## 三、调用 Handler（完整类示例）

> 统一使用 `HttpClientUtil` 发送 POST 请求，请求头 `Content-Type: application/json`，返回 `JsonObject`。
> 该类为**完整类示例**，包含 `doPost`（发请求）与 `buildRequestBody`（构建请求体）。`REQUEST_URL` 从配置读取（见第二章），禁止硬编码。

```java
package com.cmbchina.plugin.handler;


import com.cmbchina.plugin.config.ReadConfig;
import com.cmbchina.plugin.entity.AhiHttpBodyDataEntity;
import com.cmbchina.plugin.entity.AhiHttpBodyEntity;
import com.cmbchina.plugin.entity.AhiHttpBodyHeaderEntity;
import com.cmbchina.plugin.utils.HttpClientUtil;
import com.cmbchina.plugin.utils.SpringContextBeanUtil;
import com.cmbchina.rces.executor.common.ContextKeyConstant;
import com.cmbchina.rces.executor.common.EventData;
import com.cmbchina.rces.executor.pipeline.PipelineContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.mvel2.sh.CommandException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class AhiHttpInvokerHandler {
    private static final Logger logger = LoggerFactory.getLogger(AhiHttpInvokerHandler.class);

    public static ReadConfig readConfig = SpringContextBeanUtil.getApplicationContext().getBean(ReadConfig.class);

    private static final String REQUEST_URL = readConfig.getAhiHttpBusinessUrl();


    private static final HttpClientUtil httpClientUtil = new HttpClientUtil();


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter FORMATTER_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String RESPONSE_HEADER = "response_header";


    /**
     * 发送 模型http 请求
     *
     * @return
     */
    public JsonObject doPost(PipelineContext ctx, Map<String, Object> eventFields, String slf_eqp_adr) {

        EventData eventData = (EventData)ctx.getUserData().get(ContextKeyConstant.EVENT_DATA);

        // 构造 post 请求
        RequestBuilder requestBuilder = RequestBuilder.post();

        long beginTime = System.currentTimeMillis();
            // 评估分值，得分范围：[0 - 1]
        JsonObject predictResponseBody = null;
        StringEntity ahiRequestEntity = null;
        String ahiRequestBody = null;
        try {
            // 构造请求 body 体
            Map<String, String> ahiRequestHeaders = new HashMap<>();
            ahiRequestHeaders.put(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
            ahiRequestBody = buildRequestBody(eventFields, slf_eqp_adr);
            ahiRequestEntity = new StringEntity(ahiRequestBody, Consts.UTF_8);

            // 请求返回体
            predictResponseBody = httpClientUtil.invoke(requestBuilder, REQUEST_URL, ahiRequestHeaders, ahiRequestEntity,
                    new ResponseHandler<JsonObject>() {
                        @Override
                        public JsonObject handleResponse(HttpResponse response)
                                throws ClientProtocolException, IOException {
                            // 评估分值，得分范围：[0 - 1]
                            JsonObject responseBody = null;
                            // HTTP 状态码
                            int httpStatusCode = response.getStatusLine().getStatusCode();
                            if (httpStatusCode == HttpStatus.SC_OK) {
                                // HTTP 200
                                HttpEntity ahiResponseEntity = response.getEntity();
                                String ahiResponseBody = EntityUtils.toString(ahiResponseEntity, Consts.UTF_8);

                                if (!StringUtils.isEmpty(ahiResponseBody)) {
                                    // 获取返回 body 体
                                    responseBody = new JsonParser().parse(ahiResponseBody).getAsJsonObject();
                                    // 判断返回码:接口返回成功一定有response_header结构，不需要判断为空
                                    String resCode = responseBody.getAsJsonObject(RESPONSE_HEADER).get("rst_code").getAsString();
                                    if (!"0000".equals(resCode)) {
                                        logger.error("模型调用报错：{}", responseBody.getAsJsonObject(RESPONSE_HEADER).get("rst_msg"));
                                    }

                                }
                            } else {
                                // 非 HTTP 200
                                logger.error(String.format("Rtd seqNo: %s, errorCode: %d, reason: %s",
                                        eventData.getSeqNo(), httpStatusCode,
                                        response.getStatusLine().getReasonPhrase()));
                            }

                            return responseBody;
                        }
                    });
        } catch (Exception e) {
            logger.error("Invoke Ahi Service UnExpected Exception: ", e);
        } finally {
            // 监控需求，把时延和请求体结合在一起，并在前加标识符
            long timeCos = System.currentTimeMillis() - beginTime;
            logger.info(
                    "AhiRequestBodyAndResponseTime - Rtd seqNo: {}, Ahi request body: {}, AhiHttpInvokerEventHandler doPost costs time: {}",
                    eventData.getSeqNo(), ahiRequestBody, timeCos);
        }
        return predictResponseBody;
    }


    /**
     * 构建请求 body 体
     * 说明：body 中每个字段的取值均来自 eventFields.getOrDefault(key, 默认值)，
     * 其中 key（如 "cltNum"、"refBbr"、"trxCcy"、"trxAmt" 等）为示例取值键，
     * 实际生成时具体取哪个 key 必须根据本次需求文档/接口字段映射确定，禁止照抄示例键。
     *
     * @param eventFields
     * @return
     */
    protected String buildRequestBody(Map<String, Object> eventFields, String slf_eqp_adr) {
        if (eventFields == null) {
            return "";
        }
        // 时间戳 yyyyMMddHHmmss
        String requestTime = getDdApdatestr();

        // 创建 AhiHttpBodyHeaderEntity 参数
        AhiHttpBodyHeaderEntity ahiHttpBodyHeaderEntity = new AhiHttpBodyHeaderEntity();
        //        测试数据，记得删除
        ahiHttpBodyHeaderEntity.setEntityId("TBX");
        ahiHttpBodyHeaderEntity.setBusinessNo(getUUID());
        ahiHttpBodyHeaderEntity.setRequestTime(requestTime);
        // 请求头 isTestEvent 固定为 Boolean；不得将 false 改为字符串或改用 setTestEvent。
        ahiHttpBodyHeaderEntity.setIsTestEvent(false);

        // 创建 AhiHttpBodyDataEntity 参数
        AhiHttpBodyDataEntity ahiHttpBodyDataEntity = new AhiHttpBodyDataEntity();
        //cltNum 交易客户号码（企业一网通用户编号）
        ahiHttpBodyDataEntity.setBsrNtbNbr(eventFields.getOrDefault("cltNum", "").toString());
        ahiHttpBodyDataEntity.setBsrReqNbr(eventFields.getOrDefault("refBbr", "").toString());
        // 使用默认值"F00001"填充
        ahiHttpBodyDataEntity.setBusCdPrcsCd("F00001");
        ahiHttpBodyDataEntity.setCcyCd(eventFields.getOrDefault("trxCcy", "").toString());
        ahiHttpBodyDataEntity.setTrxAmt(eventFields.getOrDefault("trxAmt", "").toString());

// 用当前日期填充
        String bsrDate = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FORMATTER_DAY);
        ahiHttpBodyDataEntity.setBsrOprDat(bsrDate);
        String d = "".equals(eventFields.getOrDefault("expDat", "").toString()) ? requestTime.substring(0, 8) :
                eventFields.getOrDefault("expDat", "").toString();

        String t = "".equals(eventFields.getOrDefault("expTim", "").toString()) ? requestTime.substring(8, 14) :
                eventFields.getOrDefault("expTim", "").toString();
        // 使用期望日填充，若无，用当前日期填充
        ahiHttpBodyDataEntity.setBsrEptDat(d);
        // 使用期望日填充，若无，用当前日期填充
        ahiHttpBodyDataEntity.setBsrEptTim(t);
        // 可用时间补全，形如"20220928145943"
        ahiHttpBodyDataEntity.setBsrYurRef(requestTime);

        ahiHttpBodyDataEntity.setTrxEacId(eventFields.getOrDefault("acctNo", "").toString());
        ahiHttpBodyDataEntity.setOppTrxEacNbr(eventFields.getOrDefault("trxEac", "").toString());

        ahiHttpBodyDataEntity.setOppCustNm(eventFields.getOrDefault("trxNam", "").toString());
        ahiHttpBodyDataEntity.setCustId(eventFields.getOrDefault("customerId", "").toString());
        ahiHttpBodyDataEntity.setEacSeqNbr("EAA00001");
        ahiHttpBodyDataEntity.setEacNm(eventFields.getOrDefault("cltNam", "").toString());
        // 与真实实体的 slfEqpAdr 字段及 SLF_EQP_ADR 协议键一致，禁止另造 SLF_REQ_ADR 字段。
        ahiHttpBodyDataEntity.setSlfEqpAdr(slf_eqp_adr);

        ahiHttpBodyDataEntity.setCustSmr(eventFields.getOrDefault("memo", "").toString());
        ahiHttpBodyDataEntity.setOppTrxTxtSmr("");
        ahiHttpBodyDataEntity.setOppCustTypCd("");
        ahiHttpBodyDataEntity.setTrxTyp("A");


        // 创建 AhiHttpBodyEntity 参数
        AhiHttpBodyEntity ahiHttpBodyEntity = new AhiHttpBodyEntity();
        ahiHttpBodyEntity.setRequestHeader(ahiHttpBodyHeaderEntity);
        ahiHttpBodyEntity.setRequestData(ahiHttpBodyDataEntity);

        // string 格式的请求 body 结构体
        try {
            return MAPPER.writeValueAsString(ahiHttpBodyEntity);
        } catch (JsonProcessingException e) {
            throw new CommandException(e.getMessage());
        }
    }


    /**
     * 将ipV4转化为16进制格式
     *
     * @param ipAddress
     * @return
     */
    public String ipToHexString(String ipAddress) {
        StringBuilder sb = new StringBuilder();
        List<String> ipList = Arrays.asList(ipAddress.split("\\."));
        for (int i = 0; i < ipList.size(); i++) {
            String ipHex = Integer.toHexString(Integer.parseInt(ipList.get(i)));
            if (ipHex.length() < 2) {
                ipHex = "0" + ipHex;
            }
            sb.append(ipHex.toUpperCase() + (i == ipList.size() - 1 ? "" : "."));
        }
        return sb.toString();
    }
/**
     * 生成32位 UUID
     *
     * @return
     */
    protected String getUUID() {
        return UUID.randomUUID().toString().trim().replace("-", "");
    }


    /**
     * 获取指定格式的ddApdatestr
     *
     * @return
     */
    public String getDdApdatestr() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FORMATTER);
    }
}
```

**本方法片段需要补齐的 import / 常量：**

| 名称                                                         | 类型                                 | 来源                                                     |
| ------------------------------------------------------------ | ------------------------------------ | -------------------------------------------------------- |
| `REQUEST_URL`                                                | `String` 常量                        | 从 `ReadConfig.getAhiHttpBusinessUrl()` 读取，禁止硬编码 |
| `RESPONSE_HEADER`                                            | `String` 常量（`"response_header"`） | 类内常量                                                 |
| `httpClientUtil`                                             | HTTP 工具类                          | 复用 `HttpClientUtil`（第二章 2.3）                      |
| `MAPPER` / `FORMATTER` / `FORMATTER_DAY`                     | 序列化/日期常量                      | 类内常量                                                 |
| `RequestBuilder` / `StringEntity` / `Consts` / `ContentType` / `HTTP` | `org.apache.http.*`                  | import                                                   |
| `ResponseHandler` / `HttpResponse` / `HttpEntity` / `EntityUtils` | `org.apache.http.*`                  | import                                                   |
| `HttpStatus` / `ClientProtocolException`                     | `org.apache.http.*`                  | import                                                   |
| `JsonObject` / `JsonParser`                                  | `com.google.gson.*`                  | import                                                   |
| `PipelineContext` / `EventData` / `ContextKeyConstant`       | 业务类                               | import                                                   |
| `SpringContextBeanUtil` / `ReadConfig`                       | 配置工具类                           | import                                                   |

---
