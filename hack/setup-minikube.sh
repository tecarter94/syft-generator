#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and syft-agent
SYFT_AGENT_IMAGE="syft-agent:latest"
PROFILE="sbomer"
TAR_FILE="syft-agent.tar"

echo "--- Starting Minikube (Profile: sbomer)"
# 'start' will create the cluster if it doesn't exist, or start it if it's stopped.
minikube start -p $PROFILE --addons=ingress --driver=podman

# Ensure kubectl context is set to this cluster
minikube profile $PROFILE

echo "--- Installing Tekton Pipelines & Dashboard ---"
# Install Pipelines
kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
# Install Dashboard
kubectl apply --filename https://infra.tekton.dev/tekton-releases/dashboard/latest/release.yaml

echo "Waiting for Tekton to be ready..."
sleep 20

echo "--- Creating Dependencies (Secret & SA) ---"
# Create 'sbomer-storage-secret'
kubectl create secret generic sbomer-storage-secret \
    --from-literal=api-key="sbomer-secret-key" \
    --dry-run=client -o yaml | kubectl apply -f -

# Create 'sbomer-sa' Service Account
kubectl create sa sbomer-sa \
    --dry-run=client -o yaml | kubectl apply -f -

echo "Waiting for resources to be created..."
sleep 20

echo "--- Applying Generator Task ---"
kubectl apply -f k8s/tekton/syft-generation-task.yaml

echo "--- Building and inserting syft-agent image into Minikube registry ---"

podman build --format docker -t "$SYFT_AGENT_IMAGE" -f podman/syft-agent/Containerfile .

echo "--- Exporting syft-agent image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$SYFT_AGENT_IMAGE"

echo "--- Loading syft-agent into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$SYFT_AGENT_IMAGE' is ready in cluster '$PROFILE'."

echo "--- Minikube Setup Done! ---"
echo "Now exposing the Minikube cluster to the host. Please don't close this window and run ./hack/run-compose.sh on another terminal"

kubectl proxy --port=8001 --address='0.0.0.0' --accept-hosts='^.*$'