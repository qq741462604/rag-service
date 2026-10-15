package com.cmbchina.llm.agent;

/**
 * CODING 阶段内的子行程标记。
 * <p>
 * 为降低单次 LLM 调用的 token 与超时压力，业务代码生成与测试类生成分离（两次 QwenPlanner 调用），
 * 但二者仍处于同一个 AgentLoop 业务链内驱动。该枚举用于标识当前 CODING 循环应生成哪一类代码：
 * <ul>
 *   <li>S1_BUSINESS：生成业务代码（目标改造点）</li>
 *   <li>S2_TEST：基于已落盘的业务代码真实签名生成测试类</li>
 * </ul>
 *
 * @author IT006901
 * @date 2026-09-21 10:14:43
 */
public enum CodingTrack {

    /**
     * 业务代码子行程：仅生成目标业务代码，不生成测试类。
     */
    S1_BUSINESS,

    /**
     * 测试类子行程：基于已落盘的业务代码真实签名，仅生成/修改测试类。
     */
    S2_TEST
}
