package org.jboss.sbomer.syft.generator.adapter.in;

import static org.jboss.sbomer.syft.generator.core.ApplicationConstants.COMPONENT_NAME;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.sbomer.events.orchestration.GenerationCreated;
import org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import org.jboss.sbomer.syft.generator.core.utility.FailureUtility;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class KafkaRequestConsumer {

    @Inject
    GenerationOrchestrator orchestrator;
    @Inject
    FailureNotifier failureNotifier;

    @Incoming("generation-created")
    public void receive(GenerationCreated event) {
        try {
            log.debug("Received event ID: {}", event.getContext().getEventId());

            if (isMyGenerator(event)) {
                log.info("{} received task for generation: {}", COMPONENT_NAME,
                        event.getData().getGenerationRequest().getGenerationId());

                // Capture OTel trace context from the current span (propagated via Kafka headers)
                String traceParent = buildTraceParent(Span.current().getSpanContext());

                orchestrator.acceptRequest(
                        event.getData().getGenerationRequest().getGenerationId(),
                        event.getData().getGenerationRequest(),
                        traceParent
                );
            }
        } catch (Exception e) {
            // Catch exceptions so we don't crash the consumer loop.
            log.error("Skipping malformed or incompatible event: {}", event, e);
            Span span = Span.current();
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            if (event != null) {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), event.getContext().getCorrelationId(), event);
            } else {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), null, null);
            }

        }
    }

    /**
     * Builds W3C traceparent header from current OTel SpanContext.
     * Format: 00-<traceId>-<spanId>-<traceFlags>
     */
    private String buildTraceParent(SpanContext spanContext) {
        if (spanContext == null || !spanContext.isValid()) {
            return null;
        }
        return String.format("00-%s-%s-%s",
                spanContext.getTraceId(),
                spanContext.getSpanId(),
                spanContext.getTraceFlags().asHex());
    }

    private boolean isMyGenerator(GenerationCreated event) {
        // Safety checks to prevent NPEs
        if (event.getData() == null
                || event.getData().getRecipe() == null
                || event.getData().getRecipe().getGenerator() == null) {
            return false;
        }
        return COMPONENT_NAME.equals(event.getData().getRecipe().getGenerator().getName());
    }
}
