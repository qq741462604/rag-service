name: predict-api

Keywords:

- 模型预测
- 预测接口
- 模型调用
- 外调模型
- 模型平台接口
- 模型评分
- predict
- AHI
- ahi
- AhiHttpInvokerHandler
- HttpClientUtil
- 评估分值
- predict_rescale_score
- ahiInvoke
- buildRequestBody
- AhiHttpBodyEntity
- AhiHttpBodyDataEntity
- AhiHttpBodyHeaderEntity

Target:

class: DataPrepEventHandler
method: handle
type: existing

Rules:

1. 外调外部 API 接口必须使用固定写法：固定实体类 + 统一 Client/Handler 调用 + DTO/JsonObject 取值赋值，禁止散落 Map 拼请求体或业务类直接调 HTTP。
2. 请求 body 必须使用固定实体类（如 AhiHttpBodyEntity / AhiHttpBodyHeaderEntity / AhiHttpBodyDataEntity），字段通过 @JsonProperty 映射，禁止改成小写驼峰直接发出去。
3. 调用必须走统一 Client / Handler 方法，禁止业务类直接 new RestTemplate、HttpClient 或 HttpURLConnection。
4. URL、超时时间必须来自配置（application.yml + Config 类），禁止硬编码到 Java 代码。
5. 返回结构（response_header / response_data）必须统一解析后再取值；取值赋值必须通过 eventData.putDataAndOriginData 写回，禁止 eventFields.put、eventData.set 或直接改 eventData.getData()。
6. 参考模板已按类拆分至 docs/predict-call-template-parts/ 目录（00-overview-and-constraints.md 总览与固定约束、01-request-entity.md 请求实体类、02-config-class.md 配置类、03-http-client-util.md HttpClient 工具类、04-invoke-handler.md 调用 Handler、05-entry-and-mapping.md 入口改造与取值赋值）。需要编写外调接口代码时：先 read_file 读取 docs/predict-call-template-parts/00-overview-and-constraints.md 了解整体结构，再分析本次任务需要编写哪些类，然后 read_file 读取对应类别的分块模板，严格按分块模板的结构逐类生成代码（本质是复制粘贴后按目标项目调整包名/类名/字段）。禁止把模板内容复制进 prompt 或每次请求注入。
7. 模板中的代码块多为方法片段/字段片段，生成时必须自动补齐 package、import、类声明，并只保留与当前业务相关的字段/方法，禁止整段照搬无关代码。
8. 模板中的 package、import、类名仅为结构参考，实际生成必须按目标项目真实包名/类名补齐，禁止直接照抄。
9. 新增 Java 类必须包含正确 package、import、类级 Javadoc（作者 IT006901）。

Implementation:

Step1:
如果任务需要编写"外调模型 / 模型预测接口"相关代码，先 read_file 读取 docs/predict-call-template-parts/00-overview-and-constraints.md 了解整体结构与固定约束，分析本次任务需要编写哪些类（请求实体类 / 配置类 / HttpClient 工具类 / 调用 Handler / 入口改造），然后 read_file 读取对应类别的分块模板：

- 请求实体类：docs/predict-call-template-parts/01-request-entity.md
- 配置类：docs/predict-call-template-parts/02-config-class.md
- HttpClient 工具类：docs/predict-call-template-parts/03-http-client-util.md
- 调用 Handler：docs/predict-call-template-parts/04-invoke-handler.md
- 入口改造与取值赋值：docs/predict-call-template-parts/05-entry-and-mapping.md

Step2:
按模板结构生成或修改请求实体类（AhiHttpBodyEntity / AhiHttpBodyHeaderEntity / AhiHttpBodyDataEntity），字段按实际接口文档增删，保留 @JsonProperty 映射。

Step3:
检查 application.yml 是否已配置接口地址、超时时间；未配置则补充，并创建对应 Config 类（@ConfigurationProperties）和超时注入配置。

Step4:
按模板结构生成或修改调用类，使用统一 Client/Handler 发送 POST 请求，URL/超时从配置读取，响应统一解析。

Step5:
业务调用处按模板取值赋值，通过 eventData.putDataAndOriginData 写回结果。

Step6:
所有代码块必须自动补齐 package、import、类声明，并只保留与当前业务相关的部分。

Forbidden:

- 业务类直接 new RestTemplate
- 业务类直接 HttpURLConnection
- 散落 Map 拼请求体
- 业务代码散落 JSON 解析取值
- 硬编码 URL / 超时时间
- 用 eventFields.put / eventData.set 写回，或直接改 eventData.getData()
- 照抄模板中的 package / 类名 / 路径
- 把模板文件内容复制进 prompt 或每次请求注入
- 跳过 read_file 直接凭印象编写外调代码
- 生成未定义引用的 import / 常量