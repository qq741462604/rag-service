## 一、请求实体类（完整类示例）

> 固定结构，字段按业务实际增删。字段名 + `@JsonProperty` 的映射关系是**固定写法**，禁止改成小写驼峰直接发出去。

自助设备地址统一使用 `slfEqpAdr` / `setSlfEqpAdr`，JSON 键固定为 `SLF_EQP_ADR`。复用已有 `slfReqAdr` 时可以保留旧字段名，但必须保留同一 JSON 键并让 Handler 调用真实存在的 setter；禁止新增 `SLF_REQ_ADR` 或两个字段映射同一协议键来迁就错误调用。

```java
package com.cmbchina.plugin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class AhiHttpBodyDataEntity {

    /**
     * 企业银行编号
     */
    @JsonProperty("Bsr_Ntb_Nbr")
    private String bsrNtbNbr;
    /**
     * 流程实例号
     */
    @JsonProperty("Bsr_Req_Nbr")
    private String bsrReqNbr;
    /**
     * 业务代码  流程代码
     */
    @JsonProperty("Bus_Cd_Prcs_Cd")
    private String busCdPrcsCd;
    /**
     * 币种
     */
    @JsonProperty("CCY_CD")
    private String ccyCd;
    /**
     * 金额
     */
    @JsonProperty("TRX_AMT")
    private String trxAmt;
    /**
     * 经办日
     */
    @JsonProperty("Bsr_Opr_Dat")
    private String bsrOprDat;
    /**
     * 期望日
     */
    @JsonProperty("Bsr_Ept_Dat")
    private String bsrEptDat;
    /**
     * 期望时间
     */
    @JsonProperty("Bsr_Ept_Tim")
    private String bsrEptTim;

    /**
     * 对方参考号
     */
    @JsonProperty("Bsr_Yur_Ref")
    private String bsrYurRef;
    /**
     * 转出帐号
     */
    @JsonProperty("TRX_EAC_ID")
    private String trxEacId;
    /**
     * 收方帐号
     */
    @JsonProperty("OPP_TRX_EAC_NBR")
    private String oppTrxEacNbr;
    /**
     * 收方户名
     */
    @JsonProperty("OPP_CUST_NM")
    private String oppCustNm;
    /**
     * 客户号
     */
    @JsonProperty("CUST_ID")
    private String custId;
    /**
     * 户口序号
     */
    @JsonProperty("EAC_SEQ_NBR")
    private String eacSeqNbr;
    /**
     * 户口名称
     */
    @JsonProperty("EAC_NM")
    private String eacNm;
    /**
     * 自助设备地址
     */
    @JsonProperty("SLF_EQP_ADR")
    private String slfEqpAdr;
    /**
     * 客户摘要
     */
    @JsonProperty("CUST_SMR")
    private String custSmr;
    /**
     * 对手交易文字摘要
     */
    @JsonProperty("OPP_TRX_TXT_SMR")
    private String oppTrxTxtSmr;
    /**
     * 对手客户类型代码
     */
    @JsonProperty("OPP_CUST_TYP_CD")
    private String oppCustTypCd;
    /**
     * 交易类型
     */
    @JsonProperty("Trx_Typ")
    private String trxTyp;


}
```

> 配套的请求外层结构 `AhiHttpBodyEntity` 包含 `AhiHttpBodyHeaderEntity requestHeader` 和 `AhiHttpBodyDataEntity requestData`，字段按实际接口文档补齐。

### 请求头固定字段类型

`AhiHttpBodyHeaderEntity` 的前三个字段为 `String`，但 `isTestEvent` **必须为包装类型 `Boolean`**，禁止一律生成 `String`，也不能改为基本类型 `boolean`。保持字段名 `isTestEvent` 及 Lombok 生成的 `getIsTestEvent()` / `setIsTestEvent(Boolean)`，调用 `setIsTestEvent(false)` 时自动装箱，JSON 输出为布尔值 `false`，不是字符串 `"false"`。已有实体也必须按此契约局部修正，不能靠把调用参数改成字符串来迁就错误类型。

```java
package com.cmbchina.plugin.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型接口请求头，package 按目标工程真实包名调整。
 *
 * @author summer
 * @date 2026-10-23 00:00:00
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AhiHttpBodyHeaderEntity {

    /**
     * 实体标识
     */
    @JsonProperty("entityId")
    private String entityId;

    /**
     * 业务流水号
     */
    @JsonProperty("businessNo")
    private String businessNo;

    /**
     * 请求时间，格式 yyyyMMddHHmmss
     */
    @JsonProperty("requestTime")
    private String requestTime;

    /**
     * 是否测试事件，必须作为 JSON 布尔值传输
     */
    @JsonProperty("isTestEvent")
    private Boolean isTestEvent;
}
```

> 字段类型与 setter 参数属于固定契约，不得按业务裁剪或改名。生成日期按实际创建日期填写，不照抄示例日期。

---
