package org.jboss.sbomer.syft.generator.core.service;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class GeneratorServiceTest {

    @Inject
    GeneratorService generatorService;

    @InjectMock
    GenerationExecutor executor;

    @InjectMock
    StatusNotifier notifier;

    @InjectMock
    FailureNotifier failureNotifier;

    @BeforeEach
    void setup() {
        // Default behavior: Cluster is empty (0 active executions)
        Mockito.when(executor.countActiveExecutions()).thenReturn(0);
    }

    @Test
    void testHappyPathScheduling() {

        String genId = "G123";
        GenerationRequestSpec spec = createDummySpec();

        // Queue the request
        generatorService.acceptRequest(genId, spec, null);

        // Trigger the scheduler manually
        generatorService.processQueue();

        // Executor was called to create the TaskRun
        Mockito.verify(executor, Mockito.times(1)).scheduleGeneration(ArgumentMatchers.argThat(task ->
                task.generationId().equals(genId) && task.retryCount() == 0
        ));

        // Notification sent (GENERATING)
        Mockito.verify(notifier).notifyStatus(ArgumentMatchers.eq(genId), ArgumentMatchers.eq(GenerationStatus.GENERATING), ArgumentMatchers.any(), ArgumentMatchers.isNull());
    }

    @Test
    void testThrottling() {
        // Simulate cluster is FULL (Max is 20 by default)
        Mockito.when(executor.countActiveExecutions()).thenReturn(20);

        // Queue a request
        generatorService.acceptRequest("G999", createDummySpec(), null);

        // Trigger scheduler
        generatorService.processQueue();

        // NOTHING should happen because cluster is full
        Mockito.verify(executor, Mockito.never()).scheduleGeneration(ArgumentMatchers.any());
        Mockito.verify(notifier, Mockito.never()).notifyStatus(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void testOomRetryLogic() {
        // We have an active task running
        String genId = "G-OOM";
        GenerationRequestSpec spec = createDummySpec();

        // Put it in the active map by "scheduling" it first
        generatorService.acceptRequest(genId, spec, null);
        generatorService.processQueue(); // Now it is "Active"

        // Reset mocks to clear the initial interactions
        Mockito.clearInvocations(executor, notifier);

        // Simulate the Reconciler reporting an OOM Failure
        generatorService.handleUpdate(genId, GenerationStatus.FAILED, "OOMKilled", null);

        // It should NOT notify the core system (Silent Retry)
        Mockito.verify(notifier, Mockito.never()).notifyStatus(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        // It SHOULD re-queue the task internally
        // We trigger the scheduler again to pick up the "Retry Task"
        generatorService.processQueue();

        // Capture the task passed to the executor to verify logic
        var taskCaptor = ArgumentCaptor.forClass(org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask.class);
        Mockito.verify(executor).scheduleGeneration(taskCaptor.capture());

        var retryTask = taskCaptor.getValue();
        Assertions.assertEquals(1, retryTask.retryCount());
        // Default 1Gi * 1.5 (default multiplier) = 2Gi (Ceiling)
        Assertions.assertEquals("2Gi", retryTask.memoryOverride());
    }

    private GenerationRequestSpec createDummySpec() {
        return GenerationRequestSpec.newBuilder()
                .setGenerationId("ignored-here")
                .setTarget(Target.newBuilder().setIdentifier("img:tag").setType("CONTAINER").build())
                .build();
    }
}
