package main.java.org.jboss.sbomer.syft.generator.core.domain.model;

import org.jboss.sbomer.events.common.GenerationRequestSpec;

/**
 * Internal domain model representing a unit of work waiting in the queue.
 * It decouples the internal scheduling logic from the external Kafka event structure.
 */
public record GenerationTask(
    String generationId,
    GenerationRequestSpec spec,
    int retryCount, // NOT max retries, the number of it retries it's currently on
    String memoryOverride // i.e. 2Gi
) {
    public GenerationTask(String generationId, GenerationRequestSpec spec) {
        this(generationId, spec, 0, null);
    }
}