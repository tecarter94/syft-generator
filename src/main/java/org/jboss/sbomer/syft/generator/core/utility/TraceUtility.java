package org.jboss.sbomer.syft.generator.core.utility;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TraceUtility {

    private TraceUtility() {}

    /**
     * Returns a SpanBuilder configured as the child of given traceparent.
     * Callers can add their own attributes before calling startSpan().
     *
     * @param tracer       the OTel tracer.
     * @param spanName     the span operation name.
     * @param traceParent  the W3C traceparent header.
     * @param generationId the generation ID to set as a span attribute.
     * @return a configured {@link SpanBuilder}.
     */
    public static SpanBuilder childSpanBuilder(Tracer tracer, String spanName, String traceParent, String generationId) {
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("generation.id", generationId != null ? generationId : "unknown");
        SpanContext parentContext = parseTraceParent(traceParent);
        if (parentContext != null && parentContext.isValid()) {
            spanBuilder.setParent(Context.root().with(Span.wrap(parentContext)));
        }
        return spanBuilder;
    }

    /**
     * Parses W3C traceparent header into SpanContext.
     * Expected format: 00-<traceId>-<spanId>-<traceFlags>
     *
     * @param traceParent the traceparent header value.
     * @return the parsed SpanContext, or null if input is absent or malformed.
     */
    public static SpanContext parseTraceParent(String traceParent) {
        if (traceParent == null || traceParent.isEmpty()) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4) {
            log.warn("Invalid traceparent header format: {}", traceParent);
            return null;
        }
        try {
            return SpanContext.createFromRemoteParent(
                    parts[1], // traceId
                    parts[2], // spanId
                    TraceFlags.fromHex(parts[3], 0), // traceFlags
                    TraceState.getDefault()
            );
        } catch (Exception e) {
            log.warn("Failed to parse traceparent header '{}': {}", traceParent, e.getMessage());
            return null;
        }
    }
}