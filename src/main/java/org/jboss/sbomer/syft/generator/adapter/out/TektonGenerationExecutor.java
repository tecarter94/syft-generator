package org.jboss.sbomer.syft.generator.adapter.out;

import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.service.TaskRunFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class TektonGenerationExecutor implements GenerationExecutor {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    TaskRunFactory taskRunFactory;

    @ConfigProperty(name = "quarkus.kubernetes-client.namespace")
    String namespace;

    @WithSpan
    @Override
    public void scheduleGeneration(GenerationTask generationTask) {
        Objects.requireNonNull(generationTask, "generationTask cannot be null");
        Objects.requireNonNull(generationTask.generationId(), "generationId cannot be null");

        String generationId = generationTask.generationId();
        log.info("Scheduling TaskRun for generation: {}", generationId);

        try {
            // Use the Factory (in the Core Domain Logic) to build the object
            TaskRun taskRun = taskRunFactory.createTaskRun(generationTask);

            // Execute against the cluster
            // Kueue will handle queuing, admission control, and cleanup after completion
            kubernetesClient.resources(TaskRun.class)
                    .inNamespace(namespace)
                    .resource(taskRun)
                    .create();

            log.info("Successfully created TaskRun for generation: {}", generationId);

        } catch (KubernetesClientException e) {
            log.error("Failed to create TaskRun for generation {}: {}", generationId, e.getMessage(), e);
            throw new RuntimeException("Failed to create TaskRun for generation: " + generationId, e);
        } catch (Exception e) {
            log.error("Unexpected error scheduling generation {}: {}", generationId, e.getMessage(), e);
            throw new RuntimeException("Unexpected error scheduling generation: " + generationId, e);
        }
    }
}
