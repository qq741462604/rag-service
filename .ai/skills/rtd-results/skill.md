name:
rtd-results

keywords:

- rtdResults
- procVars
- rtqVars
- externalVars
- predict_rescale_score
- ScoreAnalysisEventHandler
- 模型评分
- 评分返回
- 返回数据加工
- 实时变量
- 事件变量
- 外部变量
- RTD返回
- RTD结果

Target:

class:
ScoreAnalysisEventHandler

method:
handle

Rules:

1. 所有返回数据加工逻辑必须在 ScoreAnalysisEventHandler.handle 中完成。
2. Target 类名固定为 ScoreAnalysisEventHandler，package 和路径以目标工程实际源码为准；先 search_code/read_file 确认并读取完整文件，禁止硬编码示例包名。
3. 只能围绕已有 rtdResults 增量追加返回字段，禁止重写 rtdResults 创建、赋值、返回或已有 put 逻辑。
4. 禁止删除、替换、移动原本逻辑，禁止修改已有方法签名、继承关系、构造器和生命周期。
5. 禁止新增 Service、Controller、DTO、Config 或辅助类。
6. 如果目标字段或变量已经存在，必须复用已有代码，禁止重复声明变量或重复 put 同名 key。
7. 如果需求未明确字段来源，必须优先围绕 eventData、eventFields、rtdResults 的既有代码分析，不能编造外部数据源。
8. patch_file 必须使用小片段增量替换，禁止把整个类或整个文件作为 oldText/newText。
9. patch_file 的 oldText 禁止包含日志字符串 CustomerEventHandler start... 或任何 ... 字符。
10. rtdResults.put 追加返回字段必须放到 handle 方法已有 try 代码块内部，和原有 scoreResult 写回逻辑保持同一异常处理边界。
11. EventData 的 getProcVars/getEventVars/getRtqVars 等签名必须先从已有调用或真实 API 确认；示例注释不保证当前版本存在该方法，禁止凭空打开无效调用。
12. 返回已有模型评分不要求新增模型调用；只把已存在的评分转换为 String。来源 Map 缺失、评分缺失或显式 null 时按空字符串返回，禁止 String.valueOf(null) 生成字符串 "null"。

Implementation:

Step1:
search_code 定位 ScoreAnalysisEventHandler，read_file 确认真正的 package 和完整源码，不按示例包名猜路径。

Step2:
read_file 读取完整 ScoreAnalysisEventHandler，确认 handle 方法中已有 rtdResults、eventData、eventFields 等上下文。

Step3:
如果需求是将 procVars 添加到返回结果:

必须使用小片段 patch_file 将以下注释打开:

```java
// Map<String, Object> procVars = eventData.getProcVars();
```

变为:

```java
Map<String, Object> procVars = eventData.getProcVars();
```

然后在不破坏原有 rtdResults 逻辑的前提下增加:

```java
rtdResults.put("procVars", procVars);
```

该 rtdResults.put 必须追加到已有 try 代码块内部，建议放在原有:

```java
rtdResults.put("scoreResult", decision);
```

之后。

Step4:
如果需求是将模型评分返回:

必须先使用小片段 patch_file 将以下注释打开:

```java
// Map<String, Object> eventFields = eventData.getEventVars();
```

变为:

```java
Map<String, Object> eventFields = eventData.getEventVars();
```

然后从 eventFields 取出 predict_rescale_score，转成 String 后放入 rtdResults。

推荐增量代码:

```java
Object scoreValue = eventFields == null ? null : eventFields.get("predict_rescale_score");
String predictRescaleScore = scoreValue == null ? "" : String.valueOf(scoreValue);
rtdResults.put("predict_rescale_score", predictRescaleScore);
```

上述取值和 rtdResults.put 必须追加到已有 try 代码块内部，建议放在原有:

```java
rtdResults.put("scoreResult", decision);
```

之后。

Step5:
如果需求是其他 rtdResults 返回数据加工:

只能基于已有 eventData、eventFields、rtqVars、procVars、externalVars 或上下文中已存在的数据取值，然后使用 rtdResults.put 增量追加字段。

所有 rtdResults.put 追加返回字段必须放到已有 try 代码块内部，禁止放在 try 外部。

如果需要使用被注释的变量，必须只打开对应变量的单行注释，禁止把同一注释块中的无关变量一起打开。

常见变量打开方式:

```java
// Map<String, Object> rtqVars = eventData.getRtqVars();
```

变为:

```java
Map<String, Object> rtqVars = eventData.getRtqVars();
```

Step6:
patch_file 生成要求:

1. oldText 只包含需要替换的最小连续代码片段。
2. newText 只在 oldText 基础上增加本需求代码。
3. 禁止整文件替换。
4. 禁止 oldText 包含 CustomerEventHandler start...。
5. 禁止 oldText 包含 ...。

Acceptance:

- 调用真实 handle，原有 scoreResult 和其它返回项保持正确；逐一验证用户要求的 procVars/rtqVars/externalVars/自定义 key，不仅检查返回 Map 非空。
- 已有模型评分按真实来源返回字符串；缺失、显式 null、空来源 Map 返回空字符串；数值评分验证实际字符串值，不触发模型/HTTP 调用。
- 新增写回与已有 scoreResult 处于同一 try 异常边界；不重复 put 同名 key、不覆盖已有无关返回值。
- 使用真实 EventData API，Mock 外部依赖；保留原有测试及断言，不为测试改变入口生命周期。

Forbidden:

- create ScoreAnalysisEventHandler
- replace ScoreAnalysisEventHandler
- modify package
- modify handle signature
- rewrite rtdResults
- delete existing rtdResults.put
- delete existing business logic
- create Service
- create Controller
- create DTO
- create Config
- hardcode fake package
- com.example 兜底包名
