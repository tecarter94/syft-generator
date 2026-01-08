#!/usr/bin/env bash

# Usage: ./test-syft-image-gen.sh [API_URL] [IMAGE_TO_SCAN]

set -e

# Configuration
API_URL="${1:-http://localhost:8080}"
IMAGE="${2:-quay.io/pct-security/mequal:latest}"
ENDPOINT="${API_URL}/api/v1/generations"

echo "Target: $ENDPOINT"
echo "Image:  $IMAGE"

# Construct JSON Payload
PAYLOAD=$(cat <<EOF
{
  "generationRequests": [
    {
      "target": {
        "type": "CONTAINER_IMAGE",
        "identifier": "${IMAGE}"
      }
    }
  ]
}
EOF
)

# Execute Request
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  "$ENDPOINT")

# Process Response
HTTP_BODY=$(echo "$RESPONSE" | sed '$d')
HTTP_STATUS=$(echo "$RESPONSE" | tail -n 1)

if [[ "$HTTP_STATUS" == "202" ]]; then
    echo "Status: Success ($HTTP_STATUS)"

    if command -v jq &> /dev/null; then
        ID=$(echo "$HTTP_BODY" | jq -r '.id')
        echo "Generation Request ID: $ID"

        echo "Waiting for system to initialize generation request..."
        sleep 2

        echo "--- Initial Status ---"
        curl -s "$API_URL/api/v1/admin/requests/$ID/generations" | jq .

        echo ""
        echo "Status can be tracked via the command below:"
        echo "curl -s $API_URL/api/v1/admin/requests/$ID/generations | jq"
    else
        echo "Response: $HTTP_BODY"
    fi
else
    echo "Status: Failed ($HTTP_STATUS)"
    echo "Response: $HTTP_BODY"
    exit 1
fi