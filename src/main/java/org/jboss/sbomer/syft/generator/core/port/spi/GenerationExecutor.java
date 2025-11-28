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
     * </p>
     *
     * @param generationTask The object carrying information about a generation task
     */
    void scheduleGeneration(GenerationTask generationTask);

    /**
     * Aborts resources associated with a specific generation.
     * <p>
     * Used for manual cancellation.
     * </p>
     *
     * @param generationId The unique ID to identify the resources.
     */
    void abortGeneration(String generationId);

    /**
     * Cleans up resources associated with a specific generation.
     * <p>
     * Used for cleaning up the environment after the generation has ended.
     * </p>
     *
     * @param generationId The unique ID to identify the resources.
     */
    void cleanupGeneration(String generationId);

    /**
     * Returns the number of currently active/running executions managed by this generator.
     * <p>
     * This is critical for the Core Domain's "Throttling" logic.
     * </p>
     *
     * @return count of active jobs.
     */
    int countActiveExecutions();
}
