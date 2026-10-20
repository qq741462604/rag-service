package com.cmbchina.llm.tool;

public abstract class BaseTool implements AgentTool {

    protected void logExecute(Object input) {
        // 预留扩展点，默认不打印工具输入，避免频繁文件操作刷日志。
    }
}
