package org.jboss.sbomer.syft.generator.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.Test;

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
        verify(executor, times(1)).scheduleGeneration(argThat(task -> task.generationId().equals(genId)));

        // Notification sent (GENERATING) - Kueue handles queuing
        verify(notifier).notifyStatus(
                eq(genId),
                eq(GenerationStatus.GENERATING),
                eq("Queued for execution"),
                isNull());
    }

    @Test
    void testSchedulingFailure() {
        String genId = "G999";
        GenerationRequestSpec spec = createDummySpec();

        // Simulate executor throwing an exception
        doThrow(new RuntimeException("Kubernetes API error"))
                .when(executor).scheduleGeneration(any());

        // Accept the request
        generatorService.acceptRequest(genId, spec, null);

        // Executor was called
        verify(executor, times(1)).scheduleGeneration(any());

        // Failure notification sent
        verify(notifier).notifyStatus(
                eq(genId),
                eq(GenerationStatus.FAILED),
                contains("Failed to queue"),
                isNull());

        // Failure notifier called
        verify(failureNotifier).notify(any(), eq(genId), isNull());
    }

    @Test
    void testAcceptRequest_NullGenerationId() {
        // Given
        GenerationRequestSpec spec = createDummySpec();

        // When/Then
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            generatorService.acceptRequest(null, spec, null);
        });
        assertTrue(ex.getMessage().contains("generationId"));
    }

    @Test
    void testAcceptRequest_NullRequest() {
        // Given
        String genId = "G123";

        // When/Then
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            generatorService.acceptRequest(genId, null, null);
        });
        assertTrue(ex.getMessage().contains("request"));
    }

    @Test
    void testAcceptRequest_WithTraceParent() {
        // Given
        String genId = "G123";
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        GenerationRequestSpec spec = createDummySpec();

        // When
        generatorService.acceptRequest(genId, spec, traceParent);

        // Then
        verify(executor).scheduleGeneration(argThat(task -> task.traceParent().equals(traceParent)));
        verify(notifier).notifyStatus(eq(genId), eq(GenerationStatus.GENERATING), any(), isNull());
    }

    @Test
    void testHandleUpdate_FinishedStatus() {
        // Given
        String genId = "G123";
        List<String> urls = List.of("http://storage/sbom1.json", "http://storage/sbom2.json");

        // When
        generatorService.handleUpdate(genId, GenerationStatus.FINISHED, "TaskRun Succeeded", urls);

        // Then
        verify(notifier).notifyStatus(genId, GenerationStatus.FINISHED, "TaskRun Succeeded", urls);
        // Note: No cleanup should be called - Kueue handles it
        verifyNoMoreInteractions(executor);
    }

    @Test
    void testHandleUpdate_FailedStatus() {
        // Given
        String genId = "G123";

        // When
        generatorService.handleUpdate(genId, GenerationStatus.FAILED, "TaskRun Failed", null);

        // Then
        verify(notifier).notifyStatus(genId, GenerationStatus.FAILED, "TaskRun Failed", null);
        // Note: No cleanup should be called - Kueue handles it
        verifyNoMoreInteractions(executor);
    }

    @Test
    void testHandleUpdate_GeneratingStatus() {
        // Given
        String genId = "G123";

        // When
        generatorService.handleUpdate(genId, GenerationStatus.GENERATING, "TaskRun Running", null);

        // Then
        verify(notifier).notifyStatus(genId, GenerationStatus.GENERATING, "TaskRun Running", null);
        // No cleanup for GENERATING status
        verifyNoMoreInteractions(executor);
    }

    @Test
    void testHandleUpdate_NullGenerationId() {
        // When/Then
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            generatorService.handleUpdate(null, GenerationStatus.FINISHED, "reason", null);
        });
        assertTrue(ex.getMessage().contains("generationId"));
    }

    @Test
    void testHandleUpdate_NullStatus() {
        // When/Then
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            generatorService.handleUpdate("G123", null, "reason", null);
        });
        assertTrue(ex.getMessage().contains("status"));
    }

    @Test
    void testHandleUpdate_NullReasonIsAcceptable() {
        // Given
        String genId = "G123";

        // When - Should not throw exception
        generatorService.handleUpdate(genId, GenerationStatus.FINISHED, null, null);

        // Then
        verify(notifier).notifyStatus(genId, GenerationStatus.FINISHED, null, null);
    }

    @Test
    void testHandleUpdate_NullResultUrlsIsAcceptable() {
        // Given
        String genId = "G123";

        // When - Should not throw exception
        generatorService.handleUpdate(genId, GenerationStatus.FINISHED, "Success", null);

        // Then
        verify(notifier).notifyStatus(genId, GenerationStatus.FINISHED, "Success", null);
    }

    @Test
    void testAcceptRequest_NotifierFailureDoesNotBlockExecution() {
        // Given
        String genId = "G123";
        GenerationRequestSpec spec = createDummySpec();
        
        // Notifier throws exception after executor succeeds
        doNothing().when(executor).scheduleGeneration(any());
        doThrow(new RuntimeException("Notifier error"))
                .when(notifier).notifyStatus(any(), any(), any(), any());

        // When - Notifier exception will propagate since it's not caught
        // The current implementation doesn't swallow notifier exceptions
        assertThrows(RuntimeException.class, () -> {
            generatorService.acceptRequest(genId, spec, null);
        });

        // Then - Executor should still have been called before notifier failed
        verify(executor).scheduleGeneration(any());
    }

    private GenerationRequestSpec createDummySpec() {
        return GenerationRequestSpec.newBuilder()
                .setGenerationId("ignored-here")
                .setTarget(Target.newBuilder().setIdentifier("img:tag").setType("CONTAINER").build())
                .build();
    }
}
