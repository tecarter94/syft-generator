package org.jboss.sbomer.syft.generator.adapter.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.sbomer.events.common.ContextSpec;
import org.jboss.sbomer.events.generator.GenerationUpdate;
import org.jboss.sbomer.events.generator.GenerationUpdateData;
import org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class KafkaStatusNotifier implements StatusNotifier {

    @Inject
    @Channel("generation-update")
    Emitter<GenerationUpdate> emitter;

    @Override
    public void notifyStatus(String generationId, GenerationStatus status, String reason, List<String> resultUrls) {

        log.info("Preparing to send status update: ID={} Status={}", generationId, status);

        GenerationUpdateData data = GenerationUpdateData.newBuilder()
                .setGenerationId(generationId)
                .setStatus(status.name())
                .setReason(reason)
                .setResultCode(status == GenerationStatus.FAILED ? 1 : 0)
                .setBaseSbomUrls(resultUrls)
                .build();

        GenerationUpdate event = GenerationUpdate.newBuilder()
                .setContext(createContext())
                .setData(data)
                .build();

        emitter.send(event).whenComplete((success, error) -> {
            if (error != null) {
                log.error("FAILED to send status update for generation {}", generationId, error);
            } else {
                log.debug("Successfully sent status update for generation {}", generationId);
            }
        });
    }

    private ContextSpec createContext() {
        return ContextSpec.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setSource("syft-generator")
                .setType("GenerationUpdate")
                .setTimestamp(Instant.now())
                .setEventVersion("1.0")
                .build();
    }
}
