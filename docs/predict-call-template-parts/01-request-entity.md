## 一、请求实体类（完整类示例）

> 固定结构，字段按业务实际增删。字段名 + `@JsonProperty` 的映射关系是**固定写法**，禁止改成小写驼峰直接发出去。

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
    private String slfReqAdr;
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

> 配套的请求外层结构 `AhiHttpBodyEntity`（含 `requestHeader` + `requestData`）与请求头 `AhiHttpBodyHeaderEntity`（含 `entityId`、`businessNo`、`requestTime`、`isTestEvent`）与此同理，字段按实际接口文档补齐。

---