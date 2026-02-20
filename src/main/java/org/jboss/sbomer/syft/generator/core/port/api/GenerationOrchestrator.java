package org.jboss.sbomer.syft.generator.core.port.api;

import java.util.List;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;

/**
 * Driving Port (API) for the Generator Core Domain.
 * <p>
 * This defines the primary use cases supported by the generator service:
 * 1. Accepting new requests (Ingress).
 * 2. Handling updates from the execution environment (Feedback loop).
 * </p>
 */
public interface GenerationOrchestrator {

    /**
     * Ingress Point: Accepts a new generation request.
     * <p>
     * Implementations should handle buffering, throttling, or immediate execution.
     * </p>
     *
     * @param generationId The unique ID of the generation.
     * @param request      The payload describing what to generate.
     * @param traceParent  W3C traceparent header (00-<traceId>-<spanId>-<traceFlags>) captured from inbound Kafka span.
     */
    void acceptRequest(String generationId, GenerationRequestSpec request, String traceParent);

    /**
     * Feedback Point: Processes a status update from the execution environment
     * (e.g., a TaskRun finished or failed).
     *
     * @param generationId The unique ID of the generation.
     * @param status       The new status detected (FINISHED / FAILED).
     * @param reason       Human-readable reason.
     * @param resultUrls   List of result URLs (if successful).
     */
    void handleUpdate(String generationId, GenerationStatus status, String reason, List<String> resultUrls);
}
