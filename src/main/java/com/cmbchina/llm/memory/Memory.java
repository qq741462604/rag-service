package com.cmbchina.llm.memory;


import lombok.Builder;
import lombok.Data;


import java.util.Date;



@Data
@Builder
public class Memory {



    /**
     * 唯一key
     */
    private String key;



    /**
     * 记忆内容
     */
    private String content;



    /**
     * 类型
     *
     * TASK
     * PROJECT_RULE
     * VERIFY_ERROR
     * SUCCESS_PATTERN
     */
    private MemoryType type;


    /**
     * 创建时间
     */

    @Builder.Default
    private Date createTime =
            new Date();


}