package org.jboss.sbomer.syft.generator.adapter.in;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.tekton.v1beta1.*;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests for {@link TaskReconciler}.
 * 
 * <p>Tests cover various TaskRun states and edge cases in the reconciliation logic.</p>
 */
@QuarkusTest
class TaskReconcilerTest {

    @Inject
    TaskReconciler reconciler;

    @InjectMock
    GenerationOrchestrator orchestrator;

    @InjectMock
    FailureNotifier failureNotifier;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Tracer tracer;

    private Context<TaskRun> mockContext;

    @BeforeEach
    void setUp() {
        mockContext = mock(Context.class);
    }

    @Test
    void testReconcile_SuccessfulTaskRun() throws Exception {
        // Given
        String generationId = "test-gen-123";
        Map<String, String> urlMap = Map.of(
                "bom-linux-amd64.json", "https://storage/gen-123/bom-linux-amd64.json",
                "bom-linux-arm64.json", "https://storage/gen-123/bom-linux-arm64.json");
        String jsonResult = objectMapper.writeValueAsString(urlMap);

        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", jsonResult);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FINISHED),
                eq("TaskRun Succeeded"),
                argThat(urls -> urls != null && urls.size() == 2));
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReconcile_FailedTaskRun() {
        // Given
        String generationId = "test-gen-456";
        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "False", "Failed", null);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FAILED),
                eq("Failed"),
                isNull());
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReconcile_MissingGenerationIdLabel() {
        // Given
        TaskRun taskRun = new TaskRunBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("test-taskrun")
                        .withLabels(Map.of()) // No generation-id label
                        .build())
                .build();

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, never()).handleUpdate(any(), any(), any(), any());
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReconcile_RunningTaskRun() {
        // Given
        String generationId = "test-gen-789";
        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "Unknown", "Running", null);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        // Should not call orchestrator for running tasks
        verify(orchestrator, never()).handleUpdate(any(), any(), any(), any());
        verify(failureNotifier, never()).notify(any(), any(), any());
    }

    @Test
    void testReconcile_NullStatus() {
        // Given
        String generationId = "test-gen-null";
        TaskRun taskRun = new TaskRunBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("test-taskrun")
                        .withLabels(Map.of("sbomer.jboss.org/generation-id", generationId))
                        .build())
                .withStatus(null) // Null status
                .build();

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        // Should not crash, just treat as running/pending
        verify(orchestrator, never()).handleUpdate(any(), any(), any(), any());
    }

    @Test
    void testReconcile_NullConditions() {
        // Given
        String generationId = "test-gen-no-cond";
        TaskRun taskRun = new TaskRunBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("test-taskrun")
                        .withLabels(Map.of("sbomer.jboss.org/generation-id", generationId))
                        .build())
                .withNewStatus()
                .withConditions() // Empty conditions
                .endStatus()
                .build();

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, never()).handleUpdate(any(), any(), any(), any());
    }

    @Test
    void testReconcile_MissingResult() {
        // Given
        String generationId = "test-gen-no-result";
        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", null);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FAILED),
                contains("Result 'sbom-url' not found"),
                isNull());
        verify(failureNotifier, times(1)).notify(any(), eq(generationId), isNull());
    }

    @Test
    void testReconcile_EmptyResult() {
        // Given
        String generationId = "test-gen-empty-result";
        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", "   ");

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FAILED),
                contains("Result 'sbom-url' not found"),
                isNull());
        verify(failureNotifier, times(1)).notify(any(), eq(generationId), isNull());
    }

    @Test
    void testReconcile_InvalidJsonResult() {
        // Given
        String generationId = "test-gen-bad-json";
        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", "{invalid json");

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FAILED),
                contains("Result parsing failed"),
                isNull());
        verify(failureNotifier, times(1)).notify(any(), eq(generationId), isNull());
    }

    @Test
    void testReconcile_EmptyUrlMap() throws Exception {
        // Given
        String generationId = "test-gen-empty-map";
        String jsonResult = objectMapper.writeValueAsString(Map.of());

        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", jsonResult);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        // Should still call orchestrator with empty list
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FINISHED),
                eq("TaskRun Succeeded"),
                argThat(urls -> urls != null && urls.isEmpty()));
    }

    @Test
    void testReconcile_WithTraceParent() throws Exception {
        // Given
        String generationId = "test-gen-trace";
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Map<String, String> urlMap = Map.of("bom.json", "https://storage/gen/bom.json");
        String jsonResult = objectMapper.writeValueAsString(urlMap);

        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", jsonResult);
        // Add trace parent annotation
        Map<String, String> annotations = new HashMap<>();
        annotations.put("sbomer.jboss.org/traceparent", traceParent);
        taskRun.getMetadata().setAnnotations(annotations);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(
                eq(generationId),
                eq(GenerationStatus.FINISHED),
                any(),
                any());
    }

    @Test
    void testReconcile_NullAnnotations() throws Exception {
        // Given
        String generationId = "test-gen-no-annotations";
        Map<String, String> urlMap = Map.of("bom.json", "https://storage/gen/bom.json");
        String jsonResult = objectMapper.writeValueAsString(urlMap);

        TaskRun taskRun = createTaskRun(generationId, "Succeeded", "True", "Succeeded", jsonResult);
        // Set annotations to null
        taskRun.getMetadata().setAnnotations(null);

        // When
        UpdateControl<TaskRun> result = reconciler.reconcile(taskRun, mockContext);

        // Then
        assertNotNull(result);
        verify(orchestrator, times(1)).handleUpdate(any(), any(), any(), any());
    }

    // Helper methods

    private TaskRun createTaskRun(String generationId, String conditionType, String conditionStatus,
            String conditionReason, String resultValue) {
        // Build TaskRun with metadata
        TaskRun taskRun = new TaskRunBuilder()
                .withNewMetadata()
                .withName("test-taskrun-" + generationId)
                .withLabels(Map.of("sbomer.jboss.org/generation-id", generationId))
                .endMetadata()
                .build();
        
        // Manually create and set status (builders don't exist for these nested objects)
        TaskRunStatus status = new TaskRunStatus();
        
        // Create condition (using Knative Condition type)
        io.fabric8.knative.pkg.apis.Condition condition = new io.fabric8.knative.pkg.apis.Condition();
        condition.setType(conditionType);
        condition.setStatus(conditionStatus);
        condition.setReason(conditionReason);
        status.setConditions(List.of(condition));
        
        // Add result if provided
        if (resultValue != null) {
            TaskRunResult result = new TaskRunResult();
            result.setName("sbom-url");
            
            ParamValue value = new ParamValue();
            value.setStringVal(resultValue);
            result.setValue(value);
            
            status.setTaskResults(List.of(result));
        }
        
        taskRun.setStatus(status);
        return taskRun;
    }
}
