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

We can run this component in a **Minikube Environment** by injecting it as part of the sbomer-platform helm chart and installing it into our cluster.

We provide helper scripts in the `hack/` directory to automate the networking and configuration between these two environments.

### 1. Prerequisites
* **Podman**
* **Minikube**
* **Helm**
* **Maven** & **Java 17+**
* **Kubectl**

### 2. Prepare the Cluster
First, we need to ensure we have the `sbomer` Minikube profile running with Tekton installed. 

To do this we have a dedicated repository and script:

```bash
./hack/setup-local-dev.sh
```
Running this script will prepare the minikube cluster with `sbomer` profile and Tekton installed.

### 3. Run the Component with Helm in Minikube
Use the `./hack/run-helm-with-local-build.sh` script to start the system. This script performs several critical steps:

- Clones sbomer-platform to component repo
- Builds component images (syft-generator, syft-agent) and puts them into minikube
- Injects the locally built component values to sbomer-platform helm chart
- Installs the sbomer-platform helm chart with our locally built component
