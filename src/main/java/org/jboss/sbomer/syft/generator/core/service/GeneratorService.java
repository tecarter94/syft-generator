package org.jboss.sbomer.syft.generator.core.service;

import java.util.List;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;
import org.jboss.sbomer.syft.generator.core.utility.FailureUtility;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class GeneratorService implements GenerationOrchestrator {

    @Inject
    GenerationExecutor executor;

    @Inject
    StatusNotifier notifier;

    @Inject
    FailureNotifier failureNotifier;


    @Override
    public void acceptRequest(String generationId, GenerationRequestSpec request, String traceParent) {
        log.info("Accepted request for generation: {}", generationId);
        
        try {
            GenerationTask task = new GenerationTask(generationId, request, traceParent);
            executor.scheduleGeneration(task);
            
            // Kueue handles queuing and admission control
            notifier.notifyStatus(generationId, GenerationStatus.GENERATING,
                "Queued for execution", null);
                
        } catch (Exception e) {
            log.error("Failed to schedule generation {}", generationId, e);
            notifier.notifyStatus(generationId, GenerationStatus.FAILED,
                "Failed to queue: " + e.getMessage(), null);
            failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e),
                generationId, null);
        }
    }

    @WithSpan
    @Override
    public void handleUpdate(@SpanAttribute("generation.id") String generationId, GenerationStatus status, String reason, List<String> resultUrls) {
        log.info("Handling update for generation {}: {}", generationId, status);

        // Notify the status (sbom-service will listen to this)
        notifier.notifyStatus(generationId, status, reason, resultUrls);

        // If it was a running job that finished, trigger a cleanup
        // via the executor (e.g. delete the TaskRun)
        doCleanupIfFinished(generationId, status);
    }



    private void doCleanupIfFinished(String generationId, GenerationStatus status) {
        if (status == GenerationStatus.FINISHED || status == GenerationStatus.FAILED) {
            executor.cleanupGeneration(generationId);
        }
    }

}
