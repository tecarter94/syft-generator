package org.jboss.sbomer.syft.generator.adapter.in;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.utility.FailureUtility;
import org.jboss.sbomer.syft.generator.core.utility.TraceUtility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.tekton.v1beta1.TaskRun;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ControllerConfiguration(name = "syft-task-reconciler", generationAwareEventProcessing = false)
@Slf4j
public class TaskReconciler implements Reconciler<TaskRun> {

    @Inject
    GenerationOrchestrator orchestrator;

    @Inject
    FailureNotifier failureNotifier;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Tracer tracer;

    private static final String REASON_OOM_KILLED = "OOMKilled";

    private static final String GENERATION_ID_LABEL = "sbomer.jboss.org/generation-id";
    private static final String RESULT_NAME_SBOM_URL = "sbom-url";
    private static final String TRACEPARENT_ANNOTATION = "sbomer.jboss.org/traceparent";

    @Override
    public UpdateControl<TaskRun> reconcile(TaskRun taskRun, Context<TaskRun> context) {
        String taskName = taskRun.getMetadata().getName();
        String generationId = taskRun.getMetadata().getLabels().get(GENERATION_ID_LABEL);

        // Read trace context from TaskRun annotations
        Map<String, String> annotations = taskRun.getMetadata().getAnnotations();
        String traceParent = annotations != null ? annotations.get(TRACEPARENT_ANNOTATION) : null;

        // Extract status for span attributes and logging
        String taskRunStatus = getConditionStatus(taskRun);
        String taskRunReason = getConditionReason(taskRun);

        // Create a child span under the original trace from the Kafka consumer
        Span span = TraceUtility.childSpanBuilder(tracer, "TaskReconciler.reconcile", traceParent, generationId)
                .setAttribute("taskrun.name", taskName != null ? taskName : "unknown")
                .setAttribute("taskrun.status", taskRunStatus)
                .setAttribute("taskrun.reason", taskRunReason)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return doReconcile(taskRun, taskName, generationId, taskRunReason);
        } finally {
            span.end();
        }
    }

    private UpdateControl<TaskRun> doReconcile(TaskRun taskRun, String taskName, String generationId, String statusReason) {
        // --- VISIBILITY LOG ---
        // This shows if the Reconciler is running, even if the task isn't done yet.
        log.info("Reconciling TaskRun '{}' (GenID: {}) - State: {}", taskName, generationId, statusReason);
        // ----------------------

        if (generationId == null) {
            log.warn("TaskRun '{}' is missing generation-id label", taskName);
            return UpdateControl.noUpdate();
        }

        // Success Case
        if (isSuccessful(taskRun)) {
            log.info("TaskRun '{}' SUCCEEDED for generation {}", taskName, generationId);

            try {
                String jsonResult = getTaskRunResult(taskRun, RESULT_NAME_SBOM_URL);
                if (jsonResult == null) {
                    throw new RuntimeException("Result '" + RESULT_NAME_SBOM_URL + "' not found in TaskRun");
                }

                Map<String, String> urlMap = objectMapper.readValue(jsonResult, new TypeReference<>() {});
                List<String> urls = new ArrayList<>(urlMap.values());

                orchestrator.handleUpdate(generationId, GenerationStatus.FINISHED, "TaskRun Succeeded", urls);

            } catch (Exception e) {
                log.error("Failed to parse results from TaskRun '{}'", taskName, e);
                Span span = Span.current();
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, e.getMessage());
                orchestrator.handleUpdate(generationId, GenerationStatus.FAILED, "Result parsing failed: " + e.getMessage(), null);
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), generationId, null);
            }
            return UpdateControl.noUpdate();
        }

        // Failure Case
        if (isFailed(taskRun)) {
            String reason;
            // Check specifically for OOM
            if (isOomKilled(taskRun)) {
                log.warn("TaskRun '{}' OOMKilled for generation {}", taskName, generationId);
                reason = "OOMKilled";
            } else {
                log.warn("TaskRun '{}' FAILED. Reason: TaskRun Failed", taskName);
                reason = "TaskRun Failed";
            }
            Span.current().setStatus(StatusCode.ERROR, reason);
            // Notify core with specific status or reason string
            orchestrator.handleUpdate(generationId, GenerationStatus.FAILED, reason, null);

            return UpdateControl.noUpdate();
        }

        // Running/Pending Case
        log.debug("TaskRun '{}' is still running/pending...", taskName);
        return UpdateControl.noUpdate();
    }

    // --- Helpers ---

    private String getConditionStatus(TaskRun tr) {
        if (tr.getStatus() == null || tr.getStatus().getConditions() == null
                || tr.getStatus().getConditions().isEmpty()) {
            return "Unknown";
        }
        return tr.getStatus().getConditions().get(0).getStatus();
    }

    private String getConditionReason(TaskRun tr) {
        if (tr.getStatus() == null || tr.getStatus().getConditions() == null
                || tr.getStatus().getConditions().isEmpty()) {
            return "Unknown";
        }
        return tr.getStatus().getConditions().get(0).getReason();
    }

    private boolean isSuccessful(TaskRun tr) {
        return hasCondition(tr, "Succeeded", "True");
    }

    private boolean isFailed(TaskRun tr) {
        return hasCondition(tr, "Succeeded", "False");
    }

    private boolean hasCondition(TaskRun tr, String type, String status) {
        if (tr.getStatus() == null || tr.getStatus().getConditions() == null) {
            return false;
        }
        return tr.getStatus().getConditions().stream()
                .anyMatch(c -> type.equals(c.getType()) && status.equals(c.getStatus()));
    }

    private String getTaskRunResult(TaskRun tr, String resultName) {
        if (tr.getStatus() == null || tr.getStatus().getTaskResults() == null) {
            return null;
        }
        return tr.getStatus().getTaskResults().stream()
                .filter(r -> resultName.equals(r.getName()))
                .findFirst()
                .map(r -> r.getValue().getStringVal())
                .orElse(null);
    }

    /**
     * Checks if any container in the pod was killed due to OutOfMemory.
     */
    private boolean isOomKilled(TaskRun taskRun) {
        if (taskRun.getStatus() == null || taskRun.getStatus().getSteps() == null) {
            return false;
        }
        // Iterate over all steps to see if any were terminated by OOM
        return taskRun.getStatus().getSteps().stream()
                .filter(step -> step.getTerminated() != null)
                .anyMatch(step -> REASON_OOM_KILLED.equals(step.getTerminated().getReason()));
    }

}
