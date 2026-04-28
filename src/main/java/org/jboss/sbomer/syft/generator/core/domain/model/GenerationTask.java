package org.jboss.sbomer.syft.generator.core.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

import org.jboss.sbomer.events.common.GenerationRequestSpec;

/**
 * Internal domain model representing a unit of work waiting in the queue.
 * It decouples the internal scheduling logic from the external Kafka event structure.
 */
public record GenerationTask(
    String generationId,
    GenerationRequestSpec spec,
    String memoryOverride, // i.e. 2Gi - optional override for large images
    String traceParent // W3C traceparent header (00-<traceId>-<spanId>-<traceFlags>)
) {
    private static final Pattern MEMORY_FORMAT_PATTERN = Pattern
            .compile("^\\d+(\\.\\d+)?(Ki|Mi|Gi|Ti|Pi|Ei|k|M|G|T|P|E)?$");

    /**
     * Compact constructor that validates all required fields.
     *
     * @throws NullPointerException if generationId or spec is null
     * @throws IllegalArgumentException if spec structure is invalid or memoryOverride format is invalid
     */
    public GenerationTask {
        // Validate required fields
        Objects.requireNonNull(generationId, "generationId cannot be null");
        Objects.requireNonNull(spec, "spec cannot be null");
        
        // Validate spec structure
        if (spec.getTarget() == null) {
            throw new IllegalArgumentException("spec.target cannot be null");
        }
        if (spec.getTarget().getIdentifier() == null || spec.getTarget().getIdentifier().trim().isEmpty()) {
            throw new IllegalArgumentException("spec.target.identifier cannot be null or empty");
        }
        
        // Validate memory format if present
        if (memoryOverride != null && !memoryOverride.isEmpty()) {
            if (!MEMORY_FORMAT_PATTERN.matcher(memoryOverride).matches()) {
                throw new IllegalArgumentException(
                        "Invalid memory format: " + memoryOverride +
                        ". Expected format: <number>[Ki|Mi|Gi|Ti|Pi|Ei]");
            }
        }
    }

    /**
     * Convenience constructor for creating a task without memory override.
     */
    public GenerationTask(String generationId, GenerationRequestSpec spec, String traceParent) {
        this(generationId, spec, null, traceParent);
    }
}