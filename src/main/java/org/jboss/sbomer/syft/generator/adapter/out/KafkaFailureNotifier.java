package org.jboss.sbomer.syft.generator.adapter.out;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.sbomer.events.common.ContextSpec;
import org.jboss.sbomer.events.common.FailureSpec;
import org.jboss.sbomer.events.error.ErrorData;
import org.jboss.sbomer.events.error.ProcessingFailed;
import org.jboss.sbomer.syft.generator.core.ApplicationConstants;
import org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class KafkaFailureNotifier implements FailureNotifier {

    @Inject
    @Channel("sbomer-errors")
    Emitter<ProcessingFailed> emitter;

    /**
     * Notifies of a processing failure by building and sending a ProcessingFailed event to Kafka.
     *
     * @param failure The standardized FailureSpec object describing the error.
     * @param correlationId The correlation ID from the source event, passed in.
     * @param sourceEvent The original object (e.g. Avro event) that triggered the failure.
     */
    @Override
    public void notify(FailureSpec failure, String correlationId, Object sourceEvent) {

        // Serialize the sourceEvent to bytes, as required by the schema
        ByteBuffer sourceEventBytes = serializeSourceEvent(sourceEvent);

        // Build the event context using the Avro builder
        ContextSpec context = ContextSpec.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setType("ProcessingFailed") // This is a required field in the new schema
                .setSource(ApplicationConstants.COMPONENT_NAME)
                .setCorrelationId(correlationId) // Use the passed-in correlationId
                .setTimestamp(Instant.now())
                .build();

        // Build the error data payload
        ErrorData errorData = ErrorData.newBuilder()
                .setFailure(failure) // Pass the received FailureSpec object directly
                .setSourceEvent(sourceEventBytes) // Pass the serialized ByteBuffer
                .build();

        // Create the top-level event object
        ProcessingFailed pf = ProcessingFailed.newBuilder()
                .setContext(context)
                .setErrorData(errorData)
                .build();

        // Log the action for observability
        String eventType = (sourceEvent != null) ? sourceEvent.getClass().getSimpleName() : "N/A (initial trigger)";
        log.error("Publishing a failure notification for event of type '{}' with correlationId '{}'. Reason: {}", eventType, correlationId, failure.getReason());

        // Send the event to the Kafka topic
        emitter.send(pf);

        log.error("Failure notification sent successfully to Kafka topic 'sbomer.errors'.");
    }

    /**
     * TODO This could be rethought. Main reason to do it this way was to decouple source event types from the schema definitions
     * Serializes the source event object to a ByteBuffer as required by the ProcessingFailed schema.
     * The schema expects `["null", "bytes"]`.
     */
    private ByteBuffer serializeSourceEvent(Object sourceEvent) {
        if (sourceEvent == null) {
            return null;
        }

        if (sourceEvent instanceof byte[]) {
            log.debug("Source event is already a byte array, wrapping in ByteBuffer.");
            return ByteBuffer.wrap((byte[]) sourceEvent);
        }

        if (sourceEvent instanceof ByteBuffer) {
            log.debug("Source event is already a ByteBuffer, passing through.");
            return (ByteBuffer) sourceEvent;
        }

        if (sourceEvent instanceof SpecificRecordBase) {
            SpecificRecordBase record = (SpecificRecordBase) sourceEvent;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                // Create a writer for that specific schema
                SpecificDatumWriter<SpecificRecordBase> writer = new SpecificDatumWriter<>(record.getSchema());

                // Create an Avro binary encoder that writes to our output stream
                BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
                writer.write(record, encoder);
                encoder.flush();

                // Get the raw bytes and wrap them in a ByteBuffer
                byte[] bytes = out.toByteArray();
                log.debug("Successfully serialized Avro event '{}' to {} bytes.", record.getClass().getSimpleName(), bytes.length);
                return ByteBuffer.wrap(bytes);

            } catch (Exception e) {
                log.warn("Failed to serialize Avro SpecificRecordBase '{}' to ByteBuffer, sending null.", record.getClass().getSimpleName(), e);
                return null;
            }
        }

        log.warn("Source event of type '{}' is not a recognized Avro record or byte array, sending null for sourceEvent.", sourceEvent.getClass().getSimpleName());
        return null; // Default to null as allowed by schema
    }
}
