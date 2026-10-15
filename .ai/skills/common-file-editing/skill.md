name: common-file-editing
always: true

Rules:

1. 修改已有文件前必须先通过 search_code 或 read_file 确认真实路径。
2. 已有文件只能使用 patch_file 修改，禁止 write_file 覆盖已有文件。
3. patch_file 前必须 read_file 读取同一路径的完整文件。
4. patch_file 的 oldText 必须来自最近一次 read_file 的真实内容，禁止使用 search_code 片段、模板片段或自行拼接内容。
5. patch_file 的 newText 必须基于 oldText 增量修改，并保留原有 package、import、class 定义、继承关系、已有字段、已有方法签名和已有业务逻辑。
6. 禁止为了新增依赖而重写目标类、替换目标类、移动目标代码或删除无关逻辑。
7. 新文件只能在确认不存在后使用 write_file 创建；如果文件已存在，必须 read_file 后 patch_file。
8. 如果计划中同时包含新文件和已有文件修改，必须先创建或修改依赖类，再 patch 目标调用点。
9. 如果已有文件为空，仍然必须先 read_file，再用 patch_file，oldText 使用空字符串，newText 使用完整文件内容。
10. 如果观察到 write_file 失败且原因是文件已存在，下一次计划必须 read_file 该文件后 patch_file，禁止重复 write_file。
11. 目标项目代码开发环境按 JDK 11 处理，新增目标项目 Java 代码必须兼容 JDK 11。
12. 新增或修改目标项目代码后，必须增加最小单元测试或可独立执行的测试类验证核心逻辑。
13. 如果是外部接口调用类，优先生成测试类直接调用 Client 或核心方法验证请求构造、配置读取和返回处理，避免依赖完整业务流程才能验证。
14. 测试代码只能服务当前改造点，禁止引入无关测试框架、无关 Mock 架构或大范围重构。
15. 如果项目已有 src/test/java 目录，测试类优先写入目标类同包路径下；如果没有测试目录，创建 src/test/java 下的对应包路径。
16. 测试类命名优先使用 目标类名 + 当前改造点 + Test，例如 ScoreAnalysisEventHandlerRtdResultsTest。

Forbidden:

- write_file 修改已有文件
- 编造 oldText
- 对已存在文件重复 write_file
- 用模板重写已有类
- 删除无关代码
- 修改无关方法签名
- 根据 package 猜测路径
- 生成超过 JDK 11 支持范围的语法
- 只新增或修改业务代码但缺少最小测试或测试类
