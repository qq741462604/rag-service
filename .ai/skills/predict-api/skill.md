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
2. 请求 body 必须使用固定实体类（如 AhiHttpBodyEntity / AhiHttpBodyHeaderEntity / AhiHttpBodyDataEntity），字段通过 @JsonProperty 映射，禁止改成小写驼峰直接发出去。请求头 isTestEvent 固定为 Boolean（禁止 String/boolean），保留 setIsTestEvent(Boolean)，调用使用 setIsTestEvent(false)，JSON 输出布尔值而非字符串。
3. 调用必须走统一 Client / Handler 方法，禁止业务类直接 new RestTemplate、HttpClient 或 HttpURLConnection。
4. URL、超时时间必须来自配置（application.yml + Config 类），禁止硬编码到 Java 代码。
5. 返回结构（response_header / response_data）必须统一解析后再取值；取值赋值必须通过 eventData.putDataAndOriginData 写回，禁止 eventFields.put、eventData.set 或直接改 eventData.getData()。
6. 参考模板位于 docs/predict-call-template-parts/。先读取 00-overview-and-constraints.md 总览，再严格按 01 请求实体、02 配置、03 HttpClient 工具、04 调用 Handler、05 入口改造的顺序推进。每次只读当前分块，完成该模板全部产物并通过结构检查后，才读取下一分块。总览和当前模板通过工具观察保留到本阶段完成；禁止全量加载所有模板。
7. 模板中的代码块多为方法片段/字段片段，生成时必须自动补齐 package、import、类声明，并只保留与当前业务相关的字段/方法，禁止整段照搬无关代码。
8. 模板中的 package、import 必须按目标项目真实包名补齐；固定支持类名和方法签名保持模板要求，业务入口使用目标项目已有类，禁止照抄示例包名。
9. 新增 Java 类必须包含正确 package、import、类级 Javadoc（作者 summer，日期为创建日期）。
10. S1 必须同步识别当前模板实际使用的公共依赖。先读取入口所属模块 POM 及可访问的本地父 POM，已有编译依赖及明确的传递提供方优先复用，缺项通过 patch_file 局部补齐模块 POM，不能留给 S2 才补。
11. 系统提供包名前缀与 Maven 坐标映射作为识别依据，不是固定整份 POM。dependencyManagement/BOM 只管理版本，不能当作依赖已引入；业务依赖不能只有 test/runtime scope。私有包或版本无可靠依据时先说明，不猜坐标，不升级已有依赖，不修改父 POM。

12. 03 模板的公共追踪类型固定为 com.cmb.bee.tracer.util.BeeTraceClientHelper 和 com.cmb.bee.tracer.codec.HttpClientInjectAdapter，不随业务包迁移。HttpClientUtil 必须同时补齐正确 import、静态 beeTraceClientHelper 字段、SpringContextBeanUtil.getApplicationContext().getBean(BeeTraceClientHelper.class) 初始化及该字段上的三步追踪调用。禁止仿造同名业务类/内部类、编造 getInstance 或改用 GlobalTracer；OpenTracing API 依赖不等于 Bee 公共组件依赖。缺私有依赖依据应明确报告，不得删字段/import 后继续推进。

13. 请求实体与 Handler 的 setter 必须按真实源码核对；自助设备地址新代码统一为 slfEqpAdr / setSlfEqpAdr，JSON 键固定 SLF_EQP_ADR。复用旧字段 slfReqAdr 时保留该 JSON 键并调用真实 setter，禁止新增 SLF_REQ_ADR、重复映射或通过删除协议字段迁就拼写错误；S2 修复前同时读取实体和调用方。

Implementation:

Step1:
先 read_file 读取 docs/predict-call-template-parts/00-overview-and-constraints.md 了解固定约束，再按以下顺序逐份实施；禁止把下列路径当成一次性读取清单，当前模板全部产物完成后才读下一份：

- 请求实体类：docs/predict-call-template-parts/01-request-entity.md
- 配置类：docs/predict-call-template-parts/02-config-class.md
- HttpClient 工具类：docs/predict-call-template-parts/03-http-client-util.md
- 调用 Handler：docs/predict-call-template-parts/04-invoke-handler.md
- 入口改造与取值赋值：docs/predict-call-template-parts/05-entry-and-mapping.md

Step2:
按模板结构生成或修改请求实体类（AhiHttpBodyEntity / AhiHttpBodyHeaderEntity / AhiHttpBodyDataEntity），字段按实际接口文档增删，保留 @JsonProperty 映射。
每个模板实施时结合实际 import 检查模块依赖，优先复用现有版本管理；缺失的业务依赖与当前模板一并补齐，依赖检查通过后才推进下一模板。不预读后续模板，不批量照搬依赖清单。

Step3:
检查 application.yml 是否已配置接口地址、超时时间；未配置则补充，按 02 模板创建或完善 ReadConfig（@Configuration + @Value），保持配置键与字段注入一致。

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
- 照抄模板中的示例 package / 路径，或另建入口类替代已有业务入口
- 一次性读取或注入全部模板，或当前模板未完成就提前改造后续产物
- 跳过 read_file 直接凭印象编写外调代码
- 生成未定义引用的 import / 常量
