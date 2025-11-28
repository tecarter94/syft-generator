#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local podman-compose development environment.
#
# It is intended to be run from the root of the project.

set -e

PROFILE=sbomer

echo "--- Checking Minikube status (Profile: $PROFILE) ---"

if ! minikube -p "$PROFILE" status > /dev/null 2>&1; then
    error "Minikube cluster '$PROFILE' is NOT running."
    echo ""
    echo "Please run the setup script first to start the cluster and install dependencies:"
    echo "./hack/setup-minikube.sh (and please leave it running so that the cluster can be exposed to the host at port 8001)"
    echo "If window was already closed, please run: kubectl proxy --port=8001 --address='0.0.0.0' --accept-hosts='^.*$'"
    echo "This enables the system to connect to the minikube cluster"
    echo ""
    exit 1
fi


echo "--- Detecting Minikube Network Gateway ---"
# Get Minikube IP (e.g. 192.168.49.2)
MINIKUBE_IP=$(minikube -p $PROFILE ip)

# Calculate Gateway IP (Replace last octet with .1)
# This works for standard Minikube networking logic
GATEWAY_IP="${MINIKUBE_IP%.*}.1"

echo "Minikube IP: $MINIKUBE_IP"
echo "Host Gateway: $GATEWAY_IP"

# Export for podman-compose to pick up
export SBOMER_STORAGE_URL="http://${GATEWAY_IP}:8085"

# Path to compose file
COMPOSE_FILE="./podman/podman-compose.yml"

echo "--- Building the component with schemas ---"
bash ./hack/build-with-schemas.sh

echo "--- Switching to podman folder ---"
pushd podman

echo "--- Creating bindmounts --"
[ ! -d "./kafka-data" ] && mkdir ./kafka-data && podman unshare chown 1001:0 ./kafka-data
[ ! -d "./kafka-config" ] && mkdir ./kafka-config && podman unshare chown 1001:0 ./kafka-config

echo "--- Removing previous podman-compose and Kafka data --"
podman-compose down -v
rm -rf kafka-data/
mkdir -p kafka-data

echo "--- Starting podman-compose ---"
podman-compose up --build --force-recreate

echo "--- Local podman-compose is now running ---"