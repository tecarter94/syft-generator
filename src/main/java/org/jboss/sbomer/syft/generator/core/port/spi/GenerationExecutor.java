package org.jboss.sbomer.syft.generator.core.port.spi;

import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;

/**
 * Driven Port (SPI) for executing the actual generation work.
 * <p>
 * Adapters implementing this interface handle the interaction with the
 * execution environment (e.g., Kubernetes, Tekton, Jenkins, Local Process).
 * </p>
 */
public interface GenerationExecutor {

    /**
     * Schedules the generation payload for execution.
     * <p>
     * In a Kubernetes/Tekton implementation, this creates the TaskRun resource.
     * Kueue manages the TaskRun lifecycle, including queuing, admission control,
     * and cleanup after completion.
     * </p>
     *
     * @param generationTask The object carrying information about a generation task
     */
    void scheduleGeneration(GenerationTask generationTask);
}
