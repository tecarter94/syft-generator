#!/bin/bash

set -e
set -o pipefail

SYFT_VERSION="1.27.1"

function install_syft() {
    local version=${1}

    mkdir -p ${HOME}/syft
    curl -s -L https://github.com/anchore/syft/releases/download/v${version}/syft_${version}_linux_amd64.tar.gz | tar xvz -C "${HOME}/syft"
}

install_syft "${SYFT_VERSION}"

chown -R 65532:0 "${HOME}"
chmod -R g=u "${HOME}"
