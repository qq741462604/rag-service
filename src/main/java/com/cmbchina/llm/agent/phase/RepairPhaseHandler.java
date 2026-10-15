package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import org.springframework.stereotype.Component;

/**
 * REPAIR 阶段处理器。
 *
 * <p>REPAIR 阶段修复验证错误后，直接回到 VERIFY 阶段重新执行验证（编译 + 测试），
 * 形成 VERIFY → REPAIR → VERIFY → REPAIR → ... 的测试-修复闭环，
 * 直到验证通过才进入 FINISH。</p >
 *
 * <p>注意：不再返回 CODING，避免修复后回到 CODING 阶段重新生成代码计划，
 * 导致已修复的代码被覆盖或因双子行程状态异常而无法正确流转。</p >
 *
 * @author IT006901
 * @date 2026-10-14 12:00:00
 */
@Component
public class RepairPhaseHandler
        implements AgentPhaseHandler {


    @Override
    public AgentPhase phase(){

        return AgentPhase.REPAIR;

    }



    @Override
    public AgentPhase next(
            AgentContext context
    ){

        // REPAIR 阶段执行完毕后，无论 verifyErrors 是否清空，都回到 VERIFY 重新验证。
        // AgentLoop 主循环会在 REPAIR 阶段执行 createPlan + toolExecutor.execute 完成修复写入，
        // 修复完成后由 PhaseManager.next() 返回 VERIFY，下一轮 AgentLoop 进入 handleVerifyPhase
        // 重新执行静态检查 + 测试，形成"测试-修复-重新测试"闭环。
        //
        // 如果 verifyErrors 已清空（说明 REPAIR 阶段没有需要修复的错误），也回到 VERIFY 做最终确认，
        // VERIFY 通过后自然进入 FINISH。
        return AgentPhase.VERIFY;

    }

}
