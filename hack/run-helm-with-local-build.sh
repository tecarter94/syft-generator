#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local podman-compose development environment.
#
# It is intended to be run from the root of the project.

set -e

PROFILE=sbomer
NAMESPACE=sbomer-test
PLATFORM_REPO="https://github.com/sbomer-project/sbomer-platform.git"
PLATFORM_DIR="sbomer-platform"
LOCAL_CHART_PATH="helm/syft-generator-chart"

echo "--- Checking Minikube status (Profile: $PROFILE) ---"

if ! minikube -p "$PROFILE" status > /dev/null 2>&1; then
    echo "Error: Minikube cluster '$PROFILE' is NOT running."
    echo ""
    echo "Please run the setup script first to start the cluster and install dependencies:"
    echo "./hack/setup-minikube.sh (and please leave it running so that the cluster can be exposed to the host at port 8001)"
    echo ""
    exit 1
fi

echo "--- Building local syft-agent image inside of Minikube ---"
# Will have local image syft-agent:latest in minikube
bash ./hack/build-local-syft-agent-into-minikube.sh

echo "--- Building local syft-generator image inside of Minikube ---"
# Will have local image syft-generator:latest in minikube
bash ./hack/build-local-syft-generator-into-minikube.sh

echo "--- Setting up SBOMer Platform Chart ---"

# Clone the platform chart if it doesn't exist
if [ ! -d "$PLATFORM_DIR" ]; then
    echo "Cloning sbomer-platform..."
    git clone "$PLATFORM_REPO" "$PLATFORM_DIR"
else
    echo "sbomer-platform directory exists, updating..."
    git -C "$PLATFORM_DIR" pull
fi

echo "--- Patching Chart.yaml to use Local Dependency ---"

# Setup Paths
ABS_CHART_PATH="file://$(pwd)/$LOCAL_CHART_PATH"
TARGET_CHART_FILE="$PLATFORM_DIR/Chart.yaml"
LOCAL_SOURCE_FILE="$LOCAL_CHART_PATH/Chart.yaml"

# Extract the Version from the LOCAL component chart
# We grab the line starting with 'version:', take the second word, and strip quotes/spaces
NEW_VERSION=$(grep "^version:" "$LOCAL_SOURCE_FILE" | head -n 1 | awk '{print $2}' | tr -d '"')

if [ -z "$NEW_VERSION" ]; then
    echo "Error: Could not detect version from $LOCAL_SOURCE_FILE"
    exit 1
fi

echo "Detected Local Version: $NEW_VERSION"
echo "Targeting Chart file:   $TARGET_CHART_FILE"

# Apply the Patch using Python (Reliable & Cross-Platform)
# This script reads the file line-by-line.
# It sets a flag 'in_block' when it sees our specific chart name.
# It turns off 'in_block' when it sees a different chart name.
# It only replaces 'repository' and 'version' when 'in_block' is True.

python3 -c "
import sys

chart_file = '$TARGET_CHART_FILE'
target_name = 'syft-generator-chart'
new_repo = '$ABS_CHART_PATH'
new_version = '$NEW_VERSION'

with open(chart_file, 'r') as f:
    lines = f.readlines()

in_block = False
output_lines = []

for line in lines:
    # Detect start of a dependency item
    if '- name:' in line:
        if target_name in line:
            in_block = True
        else:
            in_block = False
    
    # If we are inside the syft-generator-chart block, replace values
    if in_block:
        if 'repository:' in line:
            # Keep original indentation
            indent = line.split('repository:')[0]
            line = f'{indent}repository: \"{new_repo}\"\n'
        elif 'version:' in line:
            indent = line.split('version:')[0]
            line = f'{indent}version: {new_version}\n'

    output_lines.append(line)

with open(chart_file, 'w') as f:
    f.writelines(output_lines)
"

# Verify the patch
if grep -q "$ABS_CHART_PATH" "$TARGET_CHART_FILE"; then
    echo "SUCCESS: Chart.yaml was patched correctly."
else
    echo "ERROR: Failed to patch Chart.yaml!"
    echo "   Dumping file content for debugging:"
    cat "$TARGET_CHART_FILE"
    exit 1
fi

# Update dependencies to pull the local chart
echo "Updating Helm dependencies..."
helm dependency update "$PLATFORM_DIR"

# Install/Upgrade with overrides
echo "--- Deploying to Minikube ---"
# We override the repository to just the image name (no quay.io prefix) 
# and set pullPolicy to Never so K8s uses the image we just built in Minikube.

helm upgrade --install sbomer-release "./$PLATFORM_DIR" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set global.includeKafka=true \
    --set global.includeApicurio=true \
    --set global.includeApiGateway=true \
    --set global.includeOtelLgtm=true \
    --set syft-generator-chart.image.repository=localhost/syft-generator \
    --set syft-generator-chart.image.tag=latest \
    --set syft-generator-chart.image.pullPolicy=Never \
    --set syft-generator-chart.task.agent.image=localhost/syft-agent \
    --set syft-generator-chart.task.agent.tag=latest \
    --set syft-generator-chart.task.agent.pullPolicy=Never

echo "--- Forcing Rolling Restart to pick up new local image ---"
# We ignore "not found" errors in case it's the very first install
kubectl rollout restart deployment -n $NAMESPACE -l app.kubernetes.io/name=syft-generator-chart || true

echo "--- Deployment Complete ---"
echo "You can check status with: kubectl get pods -n $NAMESPACE"
echo "You can port-forward with: kubectl port-forward svc/sbomer-release-gateway 8080:8080 -n $NAMESPACE"