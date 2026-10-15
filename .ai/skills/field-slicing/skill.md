# 字段截取与日期转换

name: field-slicing

keywords:

- 字段截取
- 截取
- 子串
- 前 N 位
- 后 N 位
- 前10位
- 后10位
- 从第
- 保留前
- 保留后
- 截取后转换
- 截取日期

Target:

class:
DataPrepEventHandler

method:
handle

Rules:

1. 本 skill 负责单字段文本截取，以及“截取后转换为日期”的完整流程；不依赖其他业务 skill 再次生成相同转换逻辑。
2. 只修改真实存在的 DataPrepEventHandler.handle 及其私有辅助方法；先通过 search_code/read_file 确认真实路径、package 和已有字段加工调用。
3. 自然语言位置统一按第 1 位开始计数：截取前 N 位取第 1 至第 N 位；截取后 N 位取末尾 N 位；从第 M 位截取 N 位取第 M 至第 M + N - 1 位。
4. 用户明确“保留”“覆盖”或未指定目标字段时，截取结果写回原字段；用户指定“赋值给”“保存为”或“写入”新字段时，写入指定目标字段。
5. 源字段为空、N 或 M 非正数、起始位置超过长度、请求长度超过可用长度时，不抛出下标异常，不覆盖原字段，也不写入目标字段。
6. 只在源字段非 null 时转换为 String；禁止把 null 转换为字符串 "null"。
7. 所有字段写回都使用 eventData.putDataAndOriginData()；禁止直接修改 eventData.getData() 返回的 Map。
8. 截取后日期转换必须先完成截取，再按用户指定的源格式解析，并按用户指定的目标格式写入新的日期字段；格式或值不合法时不写入目标字段。
9. 必须保留 handle 中已有字段加工调用、异常处理和报文结构；新增调用放在已有 eventFields 获取之后，不替换或删除既有逻辑。
10. 不创建 Service、Controller、DTO、Config 或无关辅助类；需要辅助逻辑时只在目标类新增职责明确的 private 方法。

Implementation:

Step1:
search_code 定位 DataPrepEventHandler，并 read_file 读取完整文件；确认 handle 中获取 eventData.getData() 的位置和已有字段加工调用。

Step2:
从需求中识别源字段、截取方式、N、可选 M、写回字段，以及是否要求日期转换和两种日期格式。

Step3:
在保留已有调用的前提下，在 handle 的 eventFields 获取之后新增一次字段截取调用；同一需求只新增一个入口调用。

Step4:
新增 private 截取方法。先读取源字段并进行 null、位置和长度校验，再按需求截取：前 N 位使用开头范围，后 N 位使用末尾范围，从第 M 位截取 N 位时把业务位置 M 换算为 Java 下标 M - 1。

Step5:
普通截取使用 eventData.putDataAndOriginData 写回原字段或用户指定的新字段。截取后日期转换在同一私有方法或其私有日期辅助方法中完成，成功解析后只写入用户指定的新日期字段。

Example:
“截取 orderNo 前 10 位保存为 orderPrefix”表示从 orderNo 的第 1 位开始取 10 位，写入 orderPrefix。

“截取 eventTime 从第 9 位开始取 8 位，按 yyyyMMdd 转为 yyyy-MM-dd 保存为 eventDate”表示先截取，再转换日期，最后写入 eventDate。

Forbidden:

- create DataPrepEventHandler
- replace DataPrepEventHandler
- modify handle signature
- delete existing field processing calls
- direct eventFields.put
- String.valueOf(null)
- unguarded substring
- overwrite source field when a new target field is specified
- write invalid or incomplete dates
- create Service
- create Controller
- create DTO
- create Config
- hardcode fake package
