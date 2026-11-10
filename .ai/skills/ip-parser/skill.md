name: ip-parser

Keywords:

- IP
- ipCity
- ipCountry
- ipPro
- userIpVal
- IP解析

Target:

class: DataPrepEventHandler
method: handle

Rules:

1. 先 search_code/read_file 读取真实 DataPrepEventHandler 的路径、package、handle 及已有 IP 加工方法；已有实现局部补齐，不重复调用或声明。
2. 保留 handle 既有逻辑，在 eventFields 获取之后调用一次 getIpInfo(eventData)，通过私有 getIpInfo 和静态 ipLocation 方法处理。
3. IP 来源固定为 eventData.getData() 中的 userIpVal；先判 null，再转换 String 和 trim，禁止 getOrDefault 后直接 toString 导致显式 null 异常。
4. IP_CITY、IP_COUNTRY、IP_PRO 分别代表 ipCity、ipCountry、ipPro；优先复用真实常量，无常量时在 Target 中增加对应 private static final String 常量，禁止引用未定义常量。
5. GeoInfo、GeoIpService 的 package、依赖和方法必须由实际源码/已声明 JAR API 确认，禁止猜测 import、坐标或伪造签名。findCity、getCityCode、getNation、getProvCode 按真实 API 核实后使用。
6. 缺失/空白 IP 不访问 GeoIpService；查询返回 null 或查询异常时三个结果保持空字符串。GeoInfo 个别字段为 null 时仅该字段回退空字符串，不能写入字符串 "null"。
7. 必须使用 eventData.putDataAndOriginData 分别写回三个字段 IP_CITY、IP_COUNTRY、IP_PRO，禁止只写城市，也禁止直接修改 eventFields。
8. Geo 查询异常在 getIpInfo 内按项目日志风格记录后继续写回默认值，不中断其它字段加工；不创建 Service、Controller 或额外辅助类。
9. 保留真实包名、入口签名、继承关系、构造器与生命周期，不创建或覆盖 Target。只补真实所需的模块 POM 依赖，先确认已有提供方。

Implementation:

Step1:
定位并读取完整 Target、已有 getIpInfo/ipLocation/常量，以及 Geo API 和依赖证据。

Step2:
存在已有 IP 调用时复用；没有时在保留其它调用的前提下新增一次 getIpInfo(eventData)。

Step3:
getIpInfo(EventData eventData) 用局部 Map 保存三个字段的空字符串默认值。安全读取 userIpVal，非空时调用 ipLocation(ip, result)，catch 仅围绕查询过程；随后统一通过 putDataAndOriginData 写回全部三个字段。

Step4:
private static void ipLocation(String ip, Map<String, Object> result) 调用真实 GeoIpService.getInstance().findCity(ip)。结果非 null 时分别读取城市、国家、省份，null 字段转为空字符串并更新 result。使用经确认的 import/签名。

Step5:
先补常量/import/真实依赖，再完成私有方法和 handle 接入；读取最新文件核对三字段写回后才结束 S1。

Acceptance:

- 正常 IP：Mock Geo 服务返回城市、国家、省份，调用真实 handle，逐项核对三个字段以及 data/originData 写回结果。
- 缺失、显式 null、空字符串、空白 IP：不调用 Geo 查询，三个字段按空字符串默认值写回，原有其它字段加工仍执行。
- Geo 查询返回 null、GeoInfo 部分属性为 null、查询抛异常：不传播查询异常，分别核对三个字段的默认值/有效值。
- 测试隔离 GeoIpService.getInstance 及其返回服务，不加载真实 Geo 数据库、不连接真实服务；静态 Mock 能力必须有当前 Mockito/MockMaker 依据。

Forbidden:

- create or replace DataPrepEventHandler
- modify Target signature or lifecycle
- direct eventFields.put
- write only IP_CITY
- toString on nullable IP
- invent Geo API or dependency coordinates
- real Geo database or network in unit tests
