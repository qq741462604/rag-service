# 外调接口固定写法模板（AHI 接口示例）

> **用途**：统一本项目中所有"外调外部 API 接口"的代码写法，包括请求实体类、配置类、调用方式、取值赋值方式。
> **说明**：本文档是固定写法参考。里面的代码块**多为方法片段 / 字段片段，不是完整可编译的类**。
> **生成要求**：模型根据代码块生成代码时，必须**自动补齐** `package`、`import`、类声明，并只保留与当前业务相关的字段/方法，禁止照抄与本次任务无关的部分。

---

## 〇、如何把"代码块"自动补齐成"完整类"

模板中的实体类给了完整类；方法片段只给了方法体。生成时必须遵守：

1. **补齐 package**：按目标类所在真实包路径推导，禁止使用 `com.example` 等兜底包名。
2. **补齐 import**：根据代码块中用到的类型自动补 import（如 `com.fasterxml.jackson.annotation.JsonProperty`、`lombok.Data`、`org.apache.http`、`com.google.gson.JsonObject` 等）。
3. **补齐类声明**：方法片段需放入对应类中（如 `AhiHttpInvokerHandler`），补充 `class`、`@Component` 等声明，保持与目标类一致。
4. **只保留相关代码**：代码块是"该接口的固定写法示范"，生成时按实际业务字段裁剪，**不要整段照搬无关字段/方法**。
5. **常量与依赖**：代码块中出现的常量（如 `REQUEST_URL`、`RESPONSE_HEADER`、`AHI_INVOKE_SUCCESS_RET_CODE`、`IPV4_TYPE`、`FORMATTER`、`MAPPER`）必须在类中定义或 import，禁止生成未定义引用。

---

## 六、固定约束（必须遵守）

1. 请求 body 必须使用固定实体类（`AhiHttpBodyEntity` / `AhiHttpBodyHeaderEntity` / `AhiHttpBodyDataEntity`），禁止散落 Map 拼请求体。
2. 调用必须走统一 Client / Handler 方法，禁止业务类直接 new RestTemplate、HttpClient 或 HttpURLConnection。
3. **URL、超时时间必须来自配置（application.yml + Config 类），禁止硬编码**。
4. 返回结构（`response_header` / `response_data`）必须统一解析后再取值，禁止业务代码散落 JSON 解析。
5. 取值赋值必须通过 `eventData.putDataAndOriginData` 写回，禁止 `eventFields.put`、`eventData.set` 或直接改 `eventData.getData()`。
6. 新增请求/响应字段必须显式扩展实体类，禁止临时塞 Map。
7. 一切代码块生成时必须自动补齐 package、import、类声明，并只保留与当前业务相关的部分。
8. **入口 Handler（如 DataPrepEventHandler）的改造方式是固定写法，必须原样复制 05 分块模板中的 `ahiInvoke` 方法和 `verifyInvokeCondition` 方法**，禁止自行发明调用方式（如 `invoke`、`call` 等自定义方法名），禁止在 `handle` 方法中直接内联拼请求体或解析响应。`handle` 方法中只需调用 `ahiInvoke(context, eventData)` 并用 try-catch 包裹。
9. **`ahiInvoke` 方法内部的调用链是固定的**：`new AhiHttpInvokerHandler().doPost(ctx, eventFields, slfEqpAdr)` 返回 `JsonObject`，然后从 `response_header.rst_code` 判断成功，从 `response_data.predict_rescale_score` 和 `response_data.time` 取值，最后通过 `eventData.putDataAndOriginData` 写回 `ahiResultAvailable`、`time`、`rst_code`、`predict_rescale_score`、`ahiCostTime` 五个字段。禁止改变这个调用链和返回字段。
10. **改造入口 Handler 前必须先 read_file 读取 `docs/predict-call-template-parts/05-entry-and-mapping.md`**，严格按其中的代码块生成，禁止凭印象编写。