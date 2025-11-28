#!/bin/bash
set -e

# Apply TaskRun for Syft generation
# To do after making a change
# Please run from project root folder

echo "--- Setting Minikube profile to sbomer ---"
minikube profile sbomer

echo "--- Applying TaskRun for Syft generation to Minikube cluster ---"
kubectl apply -f k8s/tekton/syft-generation-task.yaml

echo "--- Done! Applied Syft generation TaskRun to Minikube ---"