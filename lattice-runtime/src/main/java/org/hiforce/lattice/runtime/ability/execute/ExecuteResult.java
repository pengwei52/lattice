package org.hiforce.lattice.runtime.ability.execute;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.hifforce.lattice.annotation.model.ReduceType;
import org.hifforce.lattice.model.register.TemplateSpec;
import org.hiforce.lattice.runtime.ability.execute.runner.ExtensionRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Rocky Yu
 * @since 2022/9/18
 */
public class ExecuteResult<R> {

    @Getter
    @Setter
    private R result;

    @Getter
    private String reduceName;

    /**
     * The detail multi-runner execution result of the extension point.
     */
    @Getter
    private final List<RunnerExecutionStatus> detailResults = new ArrayList<>(10);

    private static final ExtensionRunner.CollectionRunnerExecuteResult DUMMY = new ExtensionRunner.CollectionRunnerExecuteResult();

    static {
        DUMMY.setResults(Collections.emptyList());
    }


    public static <T> ExecuteResult<T> success(
            String reduceName,
            T model, List<TemplateSpec> runners,
            List<ExtensionRunner.CollectionRunnerExecuteResult> executeResults) {

        ExecuteResult<T> result = new ExecuteResult<>();
        result.reduceName = reduceName;
        result.setResult(model);
        if (CollectionUtils.isNotEmpty(runners)) {
            int totalLen = executeResults.size();
            for (int i = 0; i < runners.size(); i++) {
                if (i < totalLen) {
                    result.detailResults.add(toRunnerExecutionStatus(runners.get(i), executeResults.get(i)));
                } else {
                    result.detailResults.add(toRunnerExecutionStatus(runners.get(i), DUMMY));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("all")
    private static RunnerExecutionStatus toRunnerExecutionStatus(TemplateSpec runner, ExtensionRunner.CollectionRunnerExecuteResult executeResult) {
        RunnerExecutionStatus status = new RunnerExecutionStatus();
        if (null != runner) {
            status.setTemplateCode(runner.getCode());
            status.setTemplateType(runner.getType());
        }

        status.setExecuted(executeResult.isExecute());
        status.setInvokeResults(executeResult.getResults());
        status.setType(executeResult.getRunnerType());
        return status;
    }
}
