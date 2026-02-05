#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and syft-generator
SYFT_GENERATOR_IMAGE="syft-generator:latest"
PROFILE="sbomer"
TAR_FILE="syft-generator.tar"

echo "--- Building and inserting syft-generator image into Minikube registry ---"

bash ./hack/build-with-schemas.sh prod

podman build --format docker -t "$SYFT_GENERATOR_IMAGE" -f src/main/docker/Dockerfile.jvm .

echo "--- Exporting syft-generator image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$SYFT_GENERATOR_IMAGE"

echo "--- Loading syft-generator into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$SYFT_GENERATOR_IMAGE' is ready in cluster '$PROFILE'."