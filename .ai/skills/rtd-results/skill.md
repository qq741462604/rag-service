name:
rtd-results

keywords:

- rtdResults
- procVars
- rtqVars
- eventFields
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
2. Target 固定为 package com.study.plugin.extension 下的 ScoreAnalysisEventHandler，必须先 search_code/read_file 读取真实路径和完整文件后再修改。
3. 只能围绕已有 rtdResults 增量追加返回字段，禁止重写 rtdResults 创建、赋值、返回或已有 put 逻辑。
4. 禁止删除、替换、移动原本逻辑，禁止修改已有方法签名、继承关系、构造器和生命周期。
5. 禁止新增 Service、Controller、DTO、Config 或辅助类。
6. 如果目标字段或变量已经存在，必须复用已有代码，禁止重复声明变量或重复 put 同名 key。
7. 如果需求未明确字段来源，必须优先围绕 eventData、eventFields、rtdResults 的既有代码分析，不能编造外部数据源。
8. patch_file 必须使用小片段增量替换，禁止把整个类或整个文件作为 oldText/newText。
9. patch_file 的 oldText 禁止包含日志字符串 CustomerEventHandler start... 或任何 ... 字符。
10. rtdResults.put 追加返回字段必须放到 handle 方法已有 try 代码块内部，和原有 scoreResult 写回逻辑保持同一异常处理边界。

Implementation:

Step1:
search_code 定位 ScoreAnalysisEventHandler，确认 package 为 com.study.plugin.extension。

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
String predictRescaleScore = String.valueOf(
        eventFields.getOrDefault("predict_rescale_score", "")
);
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
