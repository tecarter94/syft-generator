# Syft Generator Service

The **Syft Generator Service** is a specialized microservice within the SBOMer architecture responsible for executing SBOM generation requests using [Syft](https://github.com/anchore/syft).

It acts as a **Kubernetes Operator** that listens for generation events, manages a queue of work, and reconciles Tekton TaskRuns to produce SBOMs.

## Architecture

This service follows **Hexagonal Architecture (Ports and Adapters)** to decouple the scheduling logic from the execution infrastructure.

### 1. Core Domain (Business Logic)
* **`GeneratorService`:** The "Brain". It manages the internal work queue (Leaky Bucket pattern), enforces concurrency limits, and handles retry policies (e.g., OOM handling).
* **`TaskRunFactory`:** Translates generic `GenerationRequestSpec` objects into specific Tekton `TaskRun` definitions (YAML), applying resource limits and parameters.

### 2. Driving Adapters (Input)
* **`KafkaRequestConsumer`:** Listens to the `generation.created` topic. If the request matches `sbomer.generator.name=syft-generator`, it queues it for execution.
* **`TaskReconciler`:** A Kubernetes Controller (using Java Operator SDK) that watches for `TaskRun` completion. It updates the core domain when a task succeeds or fails.

### 3. Driven Adapters (Output)
* **`TektonGenerationExecutor`:** Uses the Fabric8 Kubernetes Client to create/delete TaskRuns in the cluster.
* **`KafkaStatusNotifier`:** Sends `generation.update` events (GENERATING, FINISHED, FAILED) back to the `sbom-service` control plane.

---

## Features

### 1. Throttling & Queueing
To prevent overwhelming the Kubernetes cluster, this service maintains an internal **Priority Queue**.
* **`sbomer.generator.max-concurrent`**: Controls how many TaskRuns can exist simultaneously.
* New requests are queued in memory.
* A scheduler runs every 10s to drain the queue into the cluster as slots become available.

### 2. Self-Healing (OOM Retries)
The service detects if a TaskRun was killed due to **Out Of Memory (OOM)** issues.
* **Detection:** The Reconciler parses the container termination reason.
* **Reaction:** Instead of failing immediately, the service calculates a new memory limit (compounding multiplier) and re-schedules the task transparently.
* **Result:** The `sbom-service` only sees `GENERATING` -> `FINISHED`, unaware of the retries happening in the background.

### 3. Atomic Batch Uploads
The generated SBOMs are uploaded directly from the TaskRun pod to the [Manifest Storage Service](https://github.com/sbomer-project/manifest-storage-service) using an atomic batch transaction. The Generator Service receives the resulting URLs via the TaskRun results.

---

## Configuration

| Property | Description | Default                         |
| :--- | :--- |:--------------------------------|
| `sbomer.generator.name` | The name used to filter incoming Kafka events. | `syft`                          |
| `sbomer.generator.syft.task-name` | The Tekton Task name to instantiate. | `generator-syft`                |
| `sbomer.generator.max-concurrent` | Max active TaskRuns allowed. | `20`                            |
| `sbomer.generator.oom-retries` | Number of times to retry on OOM. | `3`                             |
| `sbomer.generator.memory-multiplier` | Factor to increase memory by on retry (e.g. 1.5x). | `1.5`                           |
| `sbomer.storage.url` | internal URL of the storage service reachable by Pods. | `http://<get-minikube-ip>:8085` |
| `quarkus.kubernetes-client.namespace` | The namespace where TaskRuns are created. | `default`                       |

---

## Development Environment Setup

This component runs in a **Hybrid Environment**: the Service runs in Podman (or locally), while the actual generation work (TaskRuns) executes inside a Minikube cluster.

We provide helper scripts in the `hack/` directory to automate the networking and configuration between these two environments.

### 1. Prerequisites
* **Podman** (with `podman-compose`)
* **Minikube**
* **Helm** (Required to render the Task templates)
* **Maven** & **Java 17+**
* **Kubectl**

### 2. Prepare the Cluster
First, ensure you have the `sbomer` Minikube profile running with Tekton installed. We have a dedicated repository and script for this infrastructure.

```bash
# Clone the infra repo and run setup-minikube.sh
./hack/setup-local-dev.sh
```
Running this script will at the end expose the minikube cluster at port 8081, please don't close the terminal to maintain this connection.

### 3. Run the Service
Use the `./hack/run-compose-with-local-build.sh` script to start the system. This script performs several critical configuration steps:

- Detects the Minikube Gateway IP (to allow Pods to talk back to your Host).
- Builds the syft-agent image locally and pushes it into the Minikube registry.
- Renders the Helm Chart Task template using local values (localhost image, Never pull policy) to push the Syft generation Task yaml to minikube.
- Builds the syft-generator component itself and uses it in the podman-compose

### 4. Apply individual components to the system after updating them

#### syft-generator (all-in-one, applies changes also to syfy-agent and tekton task)

If changes are made to the syft-generator component in general, just rerun the script below:

```bash
./hack/run-compose-with-local-build.sh
```


#### syft-agent

If changes to the `tekton/syft-generation-task.yaml` is made, to apply it to Minikube, run the script below:

```bash
./hack/apply-local-syft-generator-task-to-minikube.sh
```

#### Tekton Task for Syft generation

The Tekton Task also requires a specific container image (syft-agent) containing the Syft tool. Since we are running locally, we must build this image and sideload it directly into the Minikube cache so Kubernetes can find it without pulling from a registry.

```bash
./hack/build-local-syft-agent-into-minikube.sh
```
Note: Run this whenever you modify the Dockerfile or the internal scripts in podman/syft-agent.
