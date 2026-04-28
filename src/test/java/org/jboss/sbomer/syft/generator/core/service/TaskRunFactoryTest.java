package org.jboss.sbomer.syft.generator.core.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import org.junit.jupiter.api.Test;

import io.fabric8.tekton.v1beta1.TaskRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class TaskRunFactoryTest {

    @Inject
    TaskRunFactory factory;

    @Test
    void testCreateTaskRun_HappyPath() {
        // Given
        GenerationTask task = createValidTask("test-gen-id", "quay.io/test/image:latest");

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertNotNull(result.getMetadata().getGenerateName());
        assertTrue(result.getMetadata().getGenerateName().startsWith("syft-gen-"));

        // Verify labels
        assertEquals("test-gen-id",
                result.getMetadata().getLabels().get("sbomer.jboss.org/generation-id"));
        assertEquals("syft",
                result.getMetadata().getLabels().get("sbomer.jboss.org/generator-type"));
        assertEquals("sbomer-syft-generator",
                result.getMetadata().getLabels().get("app.kubernetes.io/managed-by"));

        // Verify spec
        assertNotNull(result.getSpec());
        assertNotNull(result.getSpec().getParams());
        assertEquals(3, result.getSpec().getParams().size());

        // Verify parameters
        assertEquals("quay.io/test/image:latest",
                result.getSpec().getParams().get(0).getValue().getStringVal());
        assertEquals("test-gen-id",
                result.getSpec().getParams().get(1).getValue().getStringVal());
    }

    @Test
    void testCreateTaskRun_NullGenerationTask() {
        // When/Then - Only test null parameter, not GenerationTask internal validation
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            factory.createTaskRun(null);
        });
        assertTrue(ex.getMessage().contains("generationTask"));
    }

    @Test
    void testCreateTaskRun_WithMemoryOverride() {
        // Given
        GenerationTask task = new GenerationTask(
                "test-id",
                createValidSpec("test-id"),
                "8Gi",
                null);

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertNotNull(result.getSpec().getStepOverrides());
        assertEquals(1, result.getSpec().getStepOverrides().size());
        assertEquals("generate", result.getSpec().getStepOverrides().get(0).getName());
        assertNotNull(result.getSpec().getStepOverrides().get(0).getResources());
        assertEquals("8Gi",
                result.getSpec().getStepOverrides().get(0).getResources().getLimits().get("memory").toString());
    }

    @Test
    void testCreateTaskRun_WithTraceParent() {
        // Given
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        GenerationTask task = new GenerationTask(
                "test-id",
                createValidSpec("test-id"),
                traceParent);

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertEquals(4, result.getSpec().getParams().size());
        assertEquals("trace-parent", result.getSpec().getParams().get(3).getName());
        assertEquals(traceParent, result.getSpec().getParams().get(3).getValue().getStringVal());

        // Verify annotation
        assertEquals(traceParent,
                result.getMetadata().getAnnotations().get("sbomer.jboss.org/traceparent"));
    }

    @Test
    void testCreateTaskRun_LongGenerationId() {
        // Given - ID longer than 63 chars
        String longId = "very-long-generation-id-that-exceeds-kubernetes-naming-limits-1234567890";
        GenerationTask task = createValidTask(longId, "quay.io/test/image:latest");

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertNotNull(result);
        // GenerateName should be shortened
        String generateName = result.getMetadata().getGenerateName();
        assertTrue(generateName.length() < 63, "GenerateName should be less than 63 chars");
        assertTrue(generateName.startsWith("syft-gen-"));
    }

    // Note: retryCount test removed - field no longer exists (Tekton Task handles retries)

    @Test
    void testCreateTaskRun_VerifyWorkspaces() {
        // Given
        GenerationTask task = createValidTask("test-id", "quay.io/test/image:latest");

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertNotNull(result.getSpec().getWorkspaces());
        assertEquals(1, result.getSpec().getWorkspaces().size());
        assertEquals("data", result.getSpec().getWorkspaces().get(0).getName());
        assertNotNull(result.getSpec().getWorkspaces().get(0).getEmptyDir());
    }

    @Test
    void testCreateTaskRun_VerifyTaskRef() {
        // Given
        GenerationTask task = createValidTask("test-id", "quay.io/test/image:latest");

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertNotNull(result.getSpec().getTaskRef());
        assertEquals("generator-syft", result.getSpec().getTaskRef().getName());
    }

    @Test
    void testCreateTaskRun_VerifyServiceAccount() {
        // Given
        GenerationTask task = createValidTask("test-id", "quay.io/test/image:latest");

        // When
        TaskRun result = factory.createTaskRun(task);

        // Then
        assertEquals("sbomer-sa", result.getSpec().getServiceAccountName());
    }

    // Helper methods

    private GenerationTask createValidTask(String generationId, String imageIdentifier) {
        return new GenerationTask(
                generationId,
                createValidSpec(generationId, imageIdentifier),
                null);
    }

    private GenerationRequestSpec createValidSpec(String generationId) {
        return createValidSpec(generationId, "quay.io/test/image:latest");
    }

    private GenerationRequestSpec createValidSpec(String generationId, String imageIdentifier) {
        return GenerationRequestSpec.newBuilder()
                .setGenerationId(generationId)
                .setTarget(Target.newBuilder()
                        .setIdentifier(imageIdentifier)
                        .setType("CONTAINER")
                        .build())
                .build();
    }
}

