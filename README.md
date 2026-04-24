# Syft Generator Service

The **Syft Generator Service** is a specialized microservice within the SBOMer architecture responsible for executing SBOM generation requests using [Syft](https://github.com/anchore/syft).

It acts as a **Kubernetes Operator** that listens for generation events, manages a queue of work, and reconciles Tekton TaskRuns to produce SBOMs.

## Architecture

This service follows **Hexagonal Architecture (Ports and Adapters)** to decouple the scheduling logic from the execution infrastructure. It uses **Kubernetes Kueue** for persistent, cloud-native queue management.

### 1. Core Domain (Business Logic)
* **`GeneratorService`:** The "Brain". It accepts generation requests and creates TaskRuns directly. Kueue handles queuing and admission control.
* **`TaskRunFactory`:** Translates generic `GenerationRequestSpec` objects into specific Tekton `TaskRun` definitions (YAML) with Kueue annotations, applying resource limits and parameters.

### 2. Driving Adapters (Input)
* **`KafkaRequestConsumer`:** Listens to the `generation.created` topic. If the request matches `sbomer.generator.name=syft-generator`, it triggers TaskRun creation.
* **`TaskReconciler`:** A Kubernetes Controller (using Java Operator SDK) that watches for `TaskRun` completion. It updates the core domain when a task succeeds or fails.

### 3. Driven Adapters (Output)
* **`TektonGenerationExecutor`:** Uses the Fabric8 Kubernetes Client to create/delete TaskRuns in the cluster.
* **`KafkaStatusNotifier`:** Sends `generation.update` events (GENERATING, FINISHED, FAILED) back to the `sbom-service` control plane.

### 4. Queue Management (Kueue)
* **Kueue:** Kubernetes-native queuing system that manages TaskRun admission based on resource quotas
* **LocalQueue:** Namespace-scoped queue for syft-generator TaskRuns
* **ClusterQueue:** Cluster-scoped queue with resource quotas (CPU, memory, pods)
* **ResourceFlavor:** Defines the resource characteristics for workloads

---

## Features

### 1. Kubernetes-Native Queueing with Kueue
TaskRuns are queued and admitted using **Kueue**, a Kubernetes-native queuing system.
* **Persistent Queue**: Queue state stored in etcd (survives pod restarts)
* **Automatic Admission Control**: Kueue manages TaskRun admission based on resource quotas
* **`kueue.clusterQueue.quotas.pods`**: Controls max concurrent TaskRuns (replaces old `maxConcurrent`)
* **No Manual Throttling**: Kueue automatically queues TaskRuns when quotas are exhausted

### 2. Generous Memory Limits
TaskRuns are configured with generous memory limits (4Gi) to minimize OOM (Out of Memory) failures.
* **Memory Allocation:** 2Gi request / 4Gi limit for the generation step
* **Rationale:** With 4Gi limit, OOM should be rare for typical container images
* **Failure Handling:** If OOM occurs, the task fails and requires manual investigation
* **Note:** OOM retry logic has been removed for simplicity. If a workload needs more than 4Gi, it likely indicates an exceptional case requiring analysis.

### 3. Atomic Batch Uploads
The generated SBOMs are uploaded directly from the TaskRun pod to the [Manifest Storage Service](https://github.com/sbomer-project/manifest-storage-service) using an atomic batch transaction. The Generator Service receives the resulting URLs via the TaskRun results.

---

## Configuration

### Application Properties

| Property | Description | Default                         |
| :--- | :--- |:--------------------------------|
| `sbomer.generator.name` | The name used to filter incoming Kafka events. | `syft`                          |
| `sbomer.generator.syft.task-name` | The Tekton Task name to instantiate. | `generator-syft`                |
| `sbomer.generator.kueue.queue-name` | The Kueue LocalQueue name for TaskRuns. | `syft-local-queue`              |
| `sbomer.generator.oom-retries` | Number of times to retry on OOM. | `3`                             |
| `sbomer.generator.memory-multiplier` | Factor to increase memory by on retry (e.g. 1.5x). | `1.5`                           |
| `sbomer.storage.url` | Internal URL of the storage service reachable by Pods. | `http://<get-minikube-ip>:8085` |
| `quarkus.kubernetes-client.namespace` | The namespace where TaskRuns are created. | `default`                       |

### Kueue Configuration (Helm Values)

| Property | Description | Default |
| :--- | :--- | :--- |
| `kueue.localQueue.name` | LocalQueue name (namespace-scoped) | `syft-local-queue` |
| `kueue.clusterQueue.name` | ClusterQueue name (cluster-scoped) | `syft-cluster-queue` |
| `kueue.clusterQueue.quotas.pods` | Max concurrent TaskRuns | `20` |
| `kueue.clusterQueue.quotas.cpu` | CPU quota for queue | `100` |
| `kueue.clusterQueue.quotas.memory` | Memory quota for queue | `200Gi` |
| `kueue.clusterQueue.queueingStrategy` | Queuing strategy (StrictFIFO or BestEffortFIFO) | `StrictFIFO` |
| `kueue.resourceFlavor.name` | ResourceFlavor name | `default-flavor` |

---

## Prerequisites

### Required Components

1. **Kubernetes Cluster** with:
   - Tekton Pipelines installed
   - **Kueue** installed (required for queue management)

2. **Install Kueue:**
   ```bash
   kubectl apply --server-side -f https://github.com/kubernetes-sigs/kueue/releases/download/v0.17.1/manifests.yaml
   ```

3. **Verify Kueue Installation:**
   ```bash
   kubectl get pods -n kueue-system
   kubectl get crd taskruns.tekton.dev
   ```

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
