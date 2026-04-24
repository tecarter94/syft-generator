package org.jboss.sbomer.syft.generator.core.service;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.Test;
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

    @Test
    void testHappyPathScheduling() {
        String genId = "G123";
        GenerationRequestSpec spec = createDummySpec();

        // Accept the request - with Kueue, TaskRun is created immediately
        generatorService.acceptRequest(genId, spec, null);

        // Executor was called to create the TaskRun immediately
        Mockito.verify(executor, Mockito.times(1)).scheduleGeneration(ArgumentMatchers.argThat(task ->
                task.generationId().equals(genId) && task.retryCount() == 0
        ));

        // Notification sent (GENERATING) - Kueue handles queuing
        Mockito.verify(notifier).notifyStatus(
            ArgumentMatchers.eq(genId),
            ArgumentMatchers.eq(GenerationStatus.GENERATING),
            ArgumentMatchers.eq("Queued for execution"),
            ArgumentMatchers.isNull()
        );
    }

    @Test
    void testSchedulingFailure() {
        String genId = "G999";
        GenerationRequestSpec spec = createDummySpec();

        // Simulate executor throwing an exception
        Mockito.doThrow(new RuntimeException("Kubernetes API error"))
            .when(executor).scheduleGeneration(ArgumentMatchers.any());

        // Accept the request
        generatorService.acceptRequest(genId, spec, null);

        // Executor was called
        Mockito.verify(executor, Mockito.times(1)).scheduleGeneration(ArgumentMatchers.any());

        // Failure notification sent
        Mockito.verify(notifier).notifyStatus(
            ArgumentMatchers.eq(genId),
            ArgumentMatchers.eq(GenerationStatus.FAILED),
            ArgumentMatchers.contains("Failed to queue"),
            ArgumentMatchers.isNull()
        );

        // Failure notifier called
        Mockito.verify(failureNotifier).notify(ArgumentMatchers.any(), ArgumentMatchers.eq(genId), ArgumentMatchers.isNull());
    }

    private GenerationRequestSpec createDummySpec() {
        return GenerationRequestSpec.newBuilder()
                .setGenerationId("ignored-here")
                .setTarget(Target.newBuilder().setIdentifier("img:tag").setType("CONTAINER").build())
                .build();
    }
}
