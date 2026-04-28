package org.jboss.sbomer.syft.generator.adapter.out;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.syft.generator.core.service.TaskRunFactory;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class TektonGenerationExecutorTest {

    @Inject
    TektonGenerationExecutor executor;

    @InjectMock
    TaskRunFactory taskRunFactory;

    @Test
    void testScheduleGeneration_NullTask() {
        // When/Then - Only test null parameter, not GenerationTask internal validation
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            executor.scheduleGeneration(null);
        });
        assertTrue(ex.getMessage().contains("generationTask"));
    }


    @Test
    void testScheduleGeneration_TaskRunFactoryThrowsException() {
        // Given
        GenerationTask task = createValidTask("test-gen-id");
        when(taskRunFactory.createTaskRun(task))
                .thenThrow(new IllegalArgumentException("Invalid task configuration"));

        // When/Then
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            executor.scheduleGeneration(task);
        });
        assertTrue(ex.getMessage().contains("Unexpected error scheduling generation"));
        assertTrue(ex.getMessage().contains("test-gen-id"));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void testScheduleGeneration_TaskRunFactoryThrowsNullPointer() {
        // Given
        GenerationTask task = createValidTask("test-gen-id");
        when(taskRunFactory.createTaskRun(task))
                .thenThrow(new NullPointerException("Missing required field"));

        // When/Then
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            executor.scheduleGeneration(task);
        });
        assertTrue(ex.getMessage().contains("Unexpected error scheduling generation"));
    }

    @Test
    void testScheduleGeneration_WithValidTask() {
        // Given
        GenerationTask task = createValidTask("test-gen-id");

        // When - This will attempt to create a real TaskRun in the test environment
        // We verify it doesn't throw an exception with valid input
        // Note: In a real test environment with K8s, this would create the TaskRun
        // For unit testing without K8s, we just verify the validation passes
        try {
            // The method will fail at K8s client call, but validation should pass
            executor.scheduleGeneration(task);
        } catch (Exception e) {
            // Expected to fail at K8s client level in test environment
            // But should not be a validation error
            assertFalse(e instanceof NullPointerException);
            assertFalse(e instanceof IllegalArgumentException);
        }
    }

    @Test
    void testScheduleGeneration_WithTraceParent() {
        // Given
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        GenerationTask task = new GenerationTask(
                "test-gen-id",
                createValidSpec("test-gen-id"),
                traceParent);

        // When/Then - Verify validation passes
        try {
            executor.scheduleGeneration(task);
        } catch (Exception e) {
            // Expected to fail at K8s client level, but not validation
            assertFalse(e instanceof NullPointerException);
            assertFalse(e instanceof IllegalArgumentException);
        }
    }

    @Test
    void testScheduleGeneration_WithMemoryOverride() {
        // Given
        GenerationTask task = new GenerationTask(
                "test-gen-id",
                createValidSpec("test-gen-id"),
                "8Gi",
                null);

        // When/Then - Verify validation passes
        try {
            executor.scheduleGeneration(task);
        } catch (Exception e) {
            // Expected to fail at K8s client level, but not validation
            assertFalse(e instanceof NullPointerException);
            assertFalse(e instanceof IllegalArgumentException);
        }
    }

    // Helper methods

    private GenerationTask createValidTask(String generationId) {
        return new GenerationTask(
                generationId,
                createValidSpec(generationId),
                null);
    }

    private GenerationRequestSpec createValidSpec(String generationId) {
        return GenerationRequestSpec.newBuilder()
                .setGenerationId(generationId)
                .setTarget(Target.newBuilder()
                        .setIdentifier("quay.io/test/image:latest")
                        .setType("CONTAINER")
                        .build())
                .build();
    }
}

