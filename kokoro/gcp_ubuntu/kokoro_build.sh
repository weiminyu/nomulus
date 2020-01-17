#!/bin/bash

set -e

cd ${KOKORO_ARTIFACTS_DIR}/github/nomulus

#gcloud auth activate-service-account \
#    --key-file="$KOKORO_GFILE_DIR/kokoro-gcs-by-gsutil.json"

#export \
#    GOOGLE_APPLICATION_CREDENTIALS="$KOKORO_GFILE_DIR/kokoro-gcs-by-gsutil.json"

./integration/runCompatibilityTests.sh -p domain-registry-dev -s sql
./integration/runCompatibilityTests.sh -p domain-registry-dev -s nomulus
