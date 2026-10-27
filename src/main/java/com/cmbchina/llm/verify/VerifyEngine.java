package com.cmbchina.llm.verify;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.planner.S2TestCoordinator;
import com.cmbchina.llm.tool.RunTestTool;
import com.cmbchina.llm.tool.ToolInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyEngine {

    private final CodeValidator validator;
    private final VerifyFileLoader loader;
    private final RunTestTool runTestTool;
    private final S2TestCoordinator testCoordinator;

    @Value("${codex.agent.test-generation-enabled:false}")
    private boolean testGenerationEnabled;

    public VerifyResult verify(AgentContext context) {
        try {
            loader.loadChangedFiles(context);
            List<String> errors = validator.validate(context);
            if (!errors.isEmpty()) {
                return VerifyResult.failed(errors);
            }
            if (!testGenerationEnabled) {
                log.info("static verify passed; S2 disabled");
                return VerifyResult.success();
            }
            errors = testCoordinator.validateAll(context);
            if (!errors.isEmpty()) {
                return VerifyResult.failed(errors);
            }
            RunTestTool.TestRunResult test = (RunTestTool.TestRunResult) runTestTool.execute(
                    ToolInput.builder().path(testCoordinator.modulePom(context))
                            .keyword(testCoordinator.testClassNames(context)).build(), context);
            VerifyResult result = test.isSuccess() ? VerifyResult.testSuccess(test.getOutput())
                    : VerifyResult.testFailed(test.getErrorMessages(), test.getExitCode(), test.getOutput());
            result.setBlocked(test.isBlocked());
            // Maven 已启动不代表测试已启动，POM/编译失败时不能记录为测试执行过。
            result.setTestExecuted(test.getTestsRun() > 0 || test.getSkipped() > 0);
            result.setTestsRun(test.getTestsRun());
            result.setSkipped(test.getSkipped());
            result.setReports(test.getReports());
            return result;
        } catch (Exception exception) {
            log.warn("verify blocked", exception);
            VerifyResult result = VerifyResult.failed(Collections.singletonList("验证读取或环境异常: " + exception.getMessage()));
            result.setBlocked(true);
            return result;
        }
    }
}
