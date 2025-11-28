package main.java.org.jboss.sbomer.syft.generator.adapter.in;

import static main.java.org.jboss.sbomer.syft.generator.core.ApplicationConstants.COMPONENT_NAME;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.sbomer.events.orchestration.GenerationCreated;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import main.java.org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import main.java.org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import main.java.org.jboss.sbomer.syft.generator.core.utility.FailureUtility;

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

                orchestrator.acceptRequest(
                        event.getData().getGenerationRequest().getGenerationId(),
                        event.getData().getGenerationRequest()
                );
            }
        } catch (Exception e) {
            // Catch exceptions so we don't crash the consumer loop.
            log.error("Skipping malformed or incompatible event: {}", event, e);
            if (event != null) {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), event.getContext().getCorrelationId(), event);
            } else {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), null, null);
            }

        }
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
