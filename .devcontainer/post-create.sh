#!/usr/bin/env bash
# Installs the tools needed to develop, run and deploy the app that are not provided by dev container features.
set -euo pipefail

# AWS SAM CLI
pipx install aws-sam-cli || pip install --user aws-sam-cli

# smee.io client, used to forward GitHub webhooks to the local app
npm install --global smee-client

# direnv, used to load the environment variables from .envrc
sudo apt-get update
sudo apt-get install -y --no-install-recommends direnv
grep -q 'direnv hook bash' ~/.bashrc || echo 'eval "$(direnv hook bash)"' >> ~/.bashrc

# Pre-fetch the Maven dependencies
./mvnw -B -q dependency:go-offline || true
