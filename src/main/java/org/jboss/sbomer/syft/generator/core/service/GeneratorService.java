package main.java.org.jboss.sbomer.syft.generator.core.service;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.events.common.GenerationRequestSpec;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import main.java.org.jboss.sbomer.syft.generator.core.domain.GenerationStatus;
import main.java.org.jboss.sbomer.syft.generator.core.domain.model.GenerationTask;
import main.java.org.jboss.sbomer.syft.generator.core.port.api.GenerationOrchestrator;
import main.java.org.jboss.sbomer.syft.generator.core.port.spi.FailureNotifier;
import main.java.org.jboss.sbomer.syft.generator.core.port.spi.GenerationExecutor;
import main.java.org.jboss.sbomer.syft.generator.core.port.spi.StatusNotifier;
import main.java.org.jboss.sbomer.syft.generator.core.utility.FailureUtility;

@ApplicationScoped
@Slf4j
public class GeneratorService implements GenerationOrchestrator {

    @Inject
    GenerationExecutor executor;

    @Inject
    StatusNotifier notifier;

    @Inject
    FailureNotifier failureNotifier;

    @ConfigProperty(name = "sbomer.generator.max-concurrent", defaultValue = "20")
    int maxConcurrent;

    // Config: How many times to retry OOM?
    @ConfigProperty(name = "sbomer.generator.oom-retries", defaultValue = "3")
    int maxOomRetries;

    // Config: Multiplier (e.g. 2.0 = double memory each time)
    @ConfigProperty(name = "sbomer.generator.memory-multiplier", defaultValue = "1.5")
    double memoryMultiplier;

    // Default memory to start multiplying from (if not defined in original request)
    @ConfigProperty(name = "sbomer.generator.default-memory", defaultValue = "1Gi")
    String defaultMemory;

    // In-memory buffer (FOR NOW - SHOULD LATER BE PERSISTENT)
    private final Queue<GenerationTask> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, GenerationTask> activeTasks = new ConcurrentHashMap<>();

    @Override
    public void acceptRequest(String generationId, GenerationRequestSpec request) {
        log.info("Accepted request for generation: {}", generationId);
        // We don't execute immediately, we queue it to respect the throttling limit
        pendingQueue.add(new GenerationTask(generationId, request));
    }

    @Override
    public void handleUpdate(String generationId, GenerationStatus status, String reason, List<String> resultUrls) {
        log.info("Handling update for generation {}: {}", generationId, status);

        // If we hit OOM, we retry with more resources
        if (status == GenerationStatus.FAILED && "OOMKilled".equals(reason)) {
            handleOomRetry(generationId);
            return; // Stop here. Method will do its own notification if needed
        }

        // Notify the status (sbom-service will listen to this)
        notifier.notifyStatus(generationId, status, reason, resultUrls);

        // If it was a running job that finished, trigger a cleanup
        // via the executor (e.g. delete the TaskRun)
        doCleanupIfFinished(generationId, status);
    }

    @Scheduled(every = "{sbomer.generator.poll-interval:10s}")
    void processQueue() {
        if (pendingQueue.isEmpty()) {
            return;
        }

        int activeCount = executor.countActiveExecutions();
        int slots = maxConcurrent - activeCount;

        if (slots <= 0) {
            log.debug("Cluster at capacity ({}/{})", activeCount, maxConcurrent);
            return;
        }

        log.info("Cluster has capacity. Scheduling {} tasks...", slots);

        for (int i = 0; i < slots; i++) {
            GenerationTask task = pendingQueue.poll();
            if (task == null) {
                break;
            }

            try {
                // Put into active tasks
                activeTasks.put(task.generationId(), task);

                executor.scheduleGeneration(task);

                // Send an event out to declare it has started generating
                notifier.notifyStatus(
                        task.generationId(),
                        GenerationStatus.GENERATING,
                        "Scheduled in execution environment",
                        null
                );

            } catch (Exception e) {
                log.error("Failed to schedule generation {}", task.generationId(), e);
                notifier.notifyStatus(task.generationId(), GenerationStatus.FAILED, e.getMessage(), null);
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), task.generationId(), null);
            }
        }
    }

    private void handleOomRetry(String generationId) {
        GenerationTask task = activeTasks.get(generationId);
        if (task == null) {
            log.warn("Cannot retry OOM for {}, task state lost.", generationId);
            notifier.notifyStatus(generationId, GenerationStatus.FAILED, "OOMKilled (Retry failed - state lost)", null);
            return;
        }

        if (task.retryCount() >= maxOomRetries) {
            log.warn("Max OOM retries reached for {}. Giving up.", generationId);
            // We fail the task, notify it failed, and do cleanup
            GenerationStatus newStatus = GenerationStatus.FAILED;
            notifier.notifyStatus(generationId, newStatus, "OOMKilled (Max retries exceeded)", null);
            doCleanupIfFinished(generationId, newStatus);
            return;
        }

        // Calculate new memory
        String currentMemory = task.memoryOverride() != null ? task.memoryOverride() : defaultMemory;
        String newMemory = calculateNewMemory(currentMemory);

        log.info("Retrying {} due to OOM. Attempt {}/{}. Increasing memory: {} -> {}",
                generationId, task.retryCount() + 1, maxOomRetries, currentMemory, newMemory);

        // Create new task with incremented count and new memory
        GenerationTask retryTask = new GenerationTask(
                task.generationId(),
                task.spec(),
                task.retryCount() + 1,
                newMemory
        );

        // Update state and re-queue
        activeTasks.put(generationId, retryTask);
        pendingQueue.add(retryTask);
    }

    private String calculateNewMemory(String current) {
        // Simple parser assuming "Gi" or "Mi"
        // For robust parsing, use Fabric8 Quantity class or regex
        // Logic: 1Gi -> 2Gi
        try {
            // Quick hack for PoC: assume Gi
            double val = Double.parseDouble(current.replace("Gi", "").replace("Mi", ""));
            // If Mi, convert to Gi for simplicity or just multiply
            if (current.endsWith("Mi")) val = val / 1024.0;

            double newVal = val * memoryMultiplier;
            return (int)Math.ceil(newVal) + "Gi";
        } catch (Exception e) {
            return "2Gi"; // Fallback
        }
    }

    private void doCleanupIfFinished(String generationId, GenerationStatus status) {
        if (status == GenerationStatus.FINISHED || status == GenerationStatus.FAILED) {
            activeTasks.remove(generationId);
            executor.cleanupGeneration(generationId);
        }
    }

}
