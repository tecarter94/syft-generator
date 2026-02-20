package org.jboss.sbomer.syft.generator.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;

import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.tekton.v1beta1.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TaskRunFactory {

    // The name of the Tekton Task applied in the cluster (e.g., "generator-syft")
    @ConfigProperty(name = "sbomer.generator.syft.task-name", defaultValue = "generator-syft")
    String taskName;

    // The Service Account that has permissions to run the pod
    @ConfigProperty(name = "sbomer.generator.service-account", defaultValue = "sbomer-sa")
    String serviceAccount;

    // The URL of manifest-storage-service
    // This is injected into the TaskRun so the pod knows where to upload results.
    @ConfigProperty(name = "sbomer.storage.url")
    String storageUrl;

    private static final String LABEL_GENERATION_ID = "sbomer.jboss.org/generation-id";
    private static final String LABEL_GENERATOR_TYPE = "sbomer.jboss.org/generator-type";
    private static final String GENERATOR_TYPE_VALUE = "syft";
    private static final String ANNOTATION_RETRY_COUNT = "sbomer.jboss.org/retry-count";
    private static final String ANNOTATION_TRACEPARENT = "sbomer.jboss.org/traceparent";

    public TaskRun createTaskRun(GenerationTask generationTask) {
        String generationId = generationTask.generationId();
        GenerationRequestSpec request = generationTask.spec();

        // 1. Prepare Parameters
        List<Param> params = new ArrayList<>();
        params.add(new ParamBuilder().withName("image").withNewValue(request.getTarget().getIdentifier()).build());
        params.add(new ParamBuilder().withName("generation-id").withNewValue(generationId).build());
        params.add(new ParamBuilder().withName("storage-service-url").withNewValue(storageUrl).build());
        if (generationTask.traceParent() != null) {
            params.add(new ParamBuilder().withName("trace-parent").withNewValue(generationTask.traceParent()).build());
        }

        // 2. Prepare Labels
        Map<String, String> labels = Map.of(
                LABEL_GENERATION_ID, generationId,
                LABEL_GENERATOR_TYPE, GENERATOR_TYPE_VALUE,
                "app.kubernetes.io/managed-by", "sbomer-syft-generator"
        );

        // 3. Build the SPEC separately (This fixes the fluent chain issues)
        TaskRunSpecBuilder specBuilder = new TaskRunSpecBuilder()
                .withServiceAccountName(serviceAccount)
                .withParams(params)
                .withTaskRef(new TaskRefBuilder().withName(taskName).build())
                .withWorkspaces(
                        Collections.singletonList(
                                new WorkspaceBindingBuilder()
                                        .withName("data")
                                        .withEmptyDir(new EmptyDirVolumeSource())
                                        .build()
                        )
                );

        // 4. Handle Memory Override (Conditional Logic)
        if (generationTask.memoryOverride() != null) {
            // We add to the existing spec builder
            specBuilder.addToStepOverrides(
                    new TaskRunStepOverrideBuilder()
                            .withName("generate") // Must match the step name in YAML
                            .withNewResources()
                            .withRequests(Map.of("memory", new Quantity(generationTask.memoryOverride())))
                            .withLimits(Map.of("memory", new Quantity(generationTask.memoryOverride())))
                            .endResources()
                            .build()
            );
        }

        // 5. Build annotations map
        Map<String, String> annotations = new java.util.HashMap<>();
        annotations.put(ANNOTATION_RETRY_COUNT, String.valueOf(generationTask.retryCount()));
        if (generationTask.traceParent() != null) {
            annotations.put(ANNOTATION_TRACEPARENT, generationTask.traceParent());
        }

        // 6. Combine into Final TaskRun
        return new TaskRunBuilder()
                .withNewMetadata()
                .withGenerateName("syft-gen-" + shortenId(generationId) + "-")
                .withLabels(labels)
                .withAnnotations(annotations)
                .endMetadata()
                .withSpec(specBuilder.build()) // Attach the separately built spec
                .build();
    }

    /**
     * Helper to shorten UUIDs for K8s resource naming limits (63 chars)
     */
    private String shortenId(String id) {
        if (id == null) return "unknown";
        String safeId = id.toLowerCase();
        return safeId.length() > 8 ? safeId.substring(0, 8) : safeId;
    }
}
