## 四、入口改造逻辑（调用 ahiInvoke）—— ⚠️ 必须原样复制，禁止自行发明

> **这是固定写法，不是参考示例。** 在目标业务 Handler 的入口方法（`handle`）中**只能**调用 `ahiInvoke(context, eventData)`，并用 `try-catch` 包裹。
> **禁止**在 `handle` 方法中直接内联拼请求体、直接调用 `AhiHttpInvokerHandler.invoke()` 或其他自定义方法名。
> **禁止**用 `eventFields.put` 写回结果，必须通过 `ahiInvoke` 内部的 `eventData.putDataAndOriginData` 写回。
> 注意：`ahiInvoke` 所在类的实例需按目标项目实际获取（若为 Bean 则注入，否则 new 实例）。

```java
try {
    // 调用模型平台接口
    ahiInvoke(context, eventData);
} catch (Exception e) {
    log.error("模型接口调用异常:{}", e);
}
```

> 说明：`context` 为 `PipelineContext`，`eventData` 为从 `context.getUserData().get(ContextKeyConstant.EVENT_DATA)` 取出的 `EventData`。若入口方法中没有这两个对象，需先按目标 Handler 的既有写法获取。

---

## 五、取值赋值方式（方法片段）—— ⚠️ `ahiInvoke` 和 `verifyInvokeCondition` 必须原样复制

> **以下两个方法是固定写法，必须原样复制到入口 Handler 类中**，只按实际业务字段裁剪，禁止改变调用链、方法名、参数或返回字段。
> 调用成功后再取值赋值。**返回结构解析（`response_header` / `response_data`）是固定写法**，取值后必须通过 `eventData.putDataAndOriginData` 写回，禁止 `eventFields.put`、`eventData.set` 或直接改 `eventData.getData()`。
> **调用链固定为**：`new AhiHttpInvokerHandler().doPost(ctx, eventFields, slfEqpAdr)` → 返回 `JsonObject responseBody` → 解析 `response_header.rst_code` 判断成功 → 从 `response_data` 取 `predict_rescale_score` 和 `time` → 通过 `eventData.putDataAndOriginData` 写回 `ahiResultAvailable`、`time`、`rst_code`、`predict_rescale_score`、`ahiCostTime` 五个字段。


```java
/**
     * 接口调用
     *
     * @param eventData
     */
    public void ahiInvoke(PipelineContext ctx, EventData eventData) {
        Map<String, Object> eventFields = eventData.getData();
        // 是否符合调用条件
        boolean isAhiInvoke = verifyInvokeCondition(eventFields);
        if (!isAhiInvoke) {
            eventData.putDataAndOriginData("ahiResultAvailable", "2");
            return;
        }

        String slfEqpAdr = null;
        try {
            slfEqpAdr = eventFields.getOrDefault("SLF_EQP_ADR", "").toString();

        } catch (Exception ex) {
            log.error("eventFields preprocessing failed.", ex);
        }
      

        // 起始时间
        long beginTime = System.currentTimeMillis();

        // 评估分值，得分范围：[0 - 1]
        Float predictScore = null;
        String rstCode = "10000";
        //      模型预测耗时
        int ahiTime = 0;
        try {
            JsonObject responseBody = new AhiHttpInvokerHandler().doPost(ctx, eventFields, slfEqpAdr);
            if (responseBody != null) {
                // 获取 body 体中的 responseHeader 结构
                JsonObject responseBodyHeader = responseBody.getAsJsonObject("response_header");
                if (responseBodyHeader != null) {
                    // 状态码 为 0000 表示调用正常
                    rstCode = responseBodyHeader.get("rst_code").getAsString();
                    if (AHI_INVOKE_SUCCESS_RET_CODE.equals(rstCode)) {
                        // 获取 body 体中的 responseData 结构
                        JsonObject responseBodyData = responseBody
                                .getAsJsonObject("response_data")
                            JsonObject responseBodyData = responseBody
                                .getAsJsonObject("response_data");
                        if (responseBodyData != null) {
                            predictScore = responseBodyData.get("predict_rescale_score").getAsFloat();
                            ahiTime = responseBodyData.get("time").getAsInt();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("responseBody数据解析错误：{}", e);
        }
        log.info("模型评分为：{}", predictScore);

        // 调用成功：ahiResultAvailable = 1；调用失败：ahiResultAvailable = 0；未被调用：ahiResultAvailable = 2
        String modelRes = predictScore == null ? "0" : "1";
        eventData.putDataAndOriginData("ahiResultAvailable", modelRes);

        // 耗时，默认为0
        eventData.putDataAndOriginData("time", ahiTime);

        // 返回的状态码
        eventData.putDataAndOriginData("rst_code", rstCode);

        // 结果不为空，保存结果用于规则引擎使用,为空时设为2
        BigDecimal modelScore = null;
        if (predictScore != null) {
            modelScore = BigDecimal.valueOf(predictScore);
        } else {
            modelScore = BigDecimal.valueOf(2);
        }
        eventData.putDataAndOriginData("predict_rescale_score", modelScore);

        //       插件调用接口花费时间
        String ahiPluginTime = String.valueOf(System.currentTimeMillis() - beginTime);
        // 统计该模块处理时间
        eventData.putDataAndOriginData("ahiCostTime", Integer.valueOf(ahiPluginTime));

    }
/**
     * 验证是否符合调用 ahi 接口的条件
     *
     * @return
     */
    private boolean verifyInvokeCondition(Map<String, Object> eventFields) {
        if (eventFields == null) {
            return false;
        }

        boolean flag = readConfig.getAhiHttpInvokeEnable();
        if (!Boolean.TRUE.equals(flag)) {
            // 调用开关是否打开
            return false;
        }

        return true;
    }

```

**本方法片段需要补齐的 import / 常量：**

| 名称                          | 类型                         | 来源     |
| ----------------------------- | ---------------------------- | -------- |
| `AHI_INVOKE_SUCCESS_RET_CODE` | `String` 常量（`"0000"`）    | 类内常量 |
| `JsonObject`                  | `com.google.gson.JsonObject` | import   |
| `BigDecimal`                  | JDK                          | import   |
| `AhiHttpInvokerHandler`       | 调用处理类                   | import   |
| `verifyInvokeCondition`       | 业务方法                     | 类内方法 |

---