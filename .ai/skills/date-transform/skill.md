name: date-transform

Keywords:

- 日期转换
- 日期格式转换
- 日期字段格式化
- 日期拼接
- yyyyMMdd
- yyyy-MM-dd
- timestamp

Target:

class: DataPrepEventHandler
method: handle

Rules:

1. 本 Skill 负责直接日期转换和日期字段拼接；先识别本需求中各项字段加工的源字段、输入格式、目标字段和输出格式。
2. 同一项加工包含截取时由 field-slicing 负责“截取、日期转换、写回”完整链，本 Skill 不重复增加该项方法或入口调用；同时存在独立日期加工时仍完成独立部分。
3. 先 search_code/read_file 确认真实 DataPrepEventHandler 路径、package、完整 handle 和已有私有加工方法；只做局部修改，保留其它字段加工调用、异常边界和报文结构。
4. 在 eventFields 获取之后接入 private dateTransform(EventData eventData) 或复用已有等价方法。所有日期转换必须从 handle 可达，不能只生成空方法或格式常量。
5. 只在源值非 null 且非空白时转换为 String；多字段拼接时必须逐个校验，任一必填组成字段缺失则不写入该目标。
6. 必须严格校验真实日期并消费完整输入，不允许自动滚动修正非法日期、不接受多余字符。使用 SimpleDateFormat 时 setLenient(false) 并检查 ParsePosition 消费到末尾；使用 java.time 时使用 STRICT 和公历年份 uuuu（对应需求中的 yyyy）。
7. 时间按 24 小时制 HHmmss / HH:mm:ss 处理，不用无 AM/PM 的 hhmmss。仅有日期而输出含时间时明确补 00:00:00；多字段日期+时间需求中的时间缺失不能悄悄补默认值。
8. 时间戳必须有明确秒/毫秒单位和所需时区；缺少必要约定时说明缺失信息，不猜测单位或转换日期。
9. 解析/格式化全部成功后才使用 eventData.putDataAndOriginData 写回指定目标。空值或非法值不新增目标、不覆盖已有目标、不改变原字段，继续其它加工。
10. 不创建 Service、Controller、DTO 或无关类；不改变入口签名、继承关系、构造器和生命周期，不猜 package、import 或依赖。

Implementation:

Step1:
读取真实 Target 和需求涉及的源字段上下文，明确每项加工是否由 field-slicing 负责，避免同一字段重复加工。

Step2:
保留 handle 已有调用，新增一次 dateTransform(eventData) 或补齐已有日期方法。局部补丁，不覆盖整个类。

Step3:
单字段示例：oprdate 按 yyyyMMdd 解析后写入 newDate，格式 yyyy-MM-dd HH:mm:ss，日期合法时明确补零点。实际字段和格式以用户需求为准。

Step4:
多字段示例：oprDate + oprTime 分别按 yyyyMMdd + HHmmss 校验后，合成为 allDate，输出 yyyy-MM-dd HH:mm:ss；任一字段缺失或非法均不写回。

Step5:
依据 Rules 严格处理解析失败；不删除或改写已有不相关日期规则。核对真实入口调用、所有需求字段、依赖和写回后结束 S1。

Acceptance:

- 调用真实 handle，按实际需求核对每个日期转换目标及 data/originData；输入原字段和既有加工结果保持正确。
- 24 小时制：时间 130501 输出 13:05:01；日期单字段输出含时间时验证 00:00:00。
- 合法闰年 20240229 转换成功；非闰年 20230229、20241301、错误长度/格式及尾随字符不写回。
- 单字段 null/空白、多字段任一缺失、非法时间 250000：不抛出解析异常、不新增/覆盖目标；不把 null 拼进日期。
- 截取和独立日期需求同时存在时，各自完成且同一字段加工不被重复执行；已有字段/调用保留。

Forbidden:

- create or replace DataPrepEventHandler
- modify Target signature or lifecycle
- delete existing processing calls
- direct eventFields.put
- lenient or partial date parsing
- ambiguous 12-hour time without AM/PM
- write invalid or incomplete dates
- guess timestamp units or timezone
