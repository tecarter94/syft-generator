package org.jboss.sbomer.syft.generator.adapter.out;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import org.jboss.sbomer.syft.generator.core.service.TaskRunFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
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

    private static final String GENERATION_ID_LABEL = "sbomer.jboss.org/generation-id";
    private static final String GENERATOR_TYPE_LABEL = "sbomer.jboss.org/generator-type";
    private static final String GENERATOR_TYPE_VALUE = "syft";

    @WithSpan
    @Override
    public void scheduleGeneration(GenerationTask generationTask) {
        log.info("Scheduling TaskRun for generation: {}", generationTask.generationId());

        // Use the Factory (in the Core Domain Logic) to build the object
        TaskRun taskRun = taskRunFactory.createTaskRun(generationTask);

        // Execute against the cluster
        kubernetesClient.resources(TaskRun.class).inNamespace(namespace).resource(taskRun).create();
    }

    @WithSpan
    @Override
    public void abortGeneration(@SpanAttribute("generation.id") String generationId) {
        log.info("Aborting generation: {}", generationId);
        kubernetesClient.resources(TaskRun.class)
                .inNamespace(namespace)
                .withLabel(GENERATION_ID_LABEL, generationId)
                .delete();
    }

    // In this specific implementation, basically same logic as abortGeneration
    @WithSpan
    @Override
    public void cleanupGeneration(@SpanAttribute("generation.id") String generationId) {
        log.info("Cleaning up generation: {}", generationId);
        kubernetesClient.resources(TaskRun.class)
                .inNamespace(namespace)
                .withLabel(GENERATION_ID_LABEL, generationId)
                .delete();
    }

    @Override
    public int countActiveExecutions() {
        // Count TaskRuns for THIS generator that are NOT finished.
        // This is the input for the Throttling logic.
        return (int) kubernetesClient.resources(TaskRun.class).inNamespace(namespace)
                .withLabel(GENERATOR_TYPE_LABEL, GENERATOR_TYPE_VALUE)
                .list()
                .getItems()
                .stream()
                .filter(tr -> !isFinished(tr))
                .count();
    }

    /**
     * Helper to check Tekton Status Conditions
     */
    private boolean isFinished(TaskRun taskRun) {
        if (taskRun.getStatus() == null || taskRun.getStatus().getConditions() == null) {
            return false; // No status means it's initializing/running
        }

        // Check for "Succeeded" condition with Status "True" or "False" (False means failed, but it is still 'finished')
        return taskRun.getStatus().getConditions().stream()
                .anyMatch(c -> "Succeeded".equals(c.getType()) &&
                        ("True".equals(c.getStatus()) || "False".equals(c.getStatus())));
    }
}
