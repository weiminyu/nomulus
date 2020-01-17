#!/bin/bash

set -e

cd ${KOKORO_ARTIFACTS_DIR}/github/nomulus

#gcloud auth activate-service-account \
#    --key-file="$KOKORO_GFILE_DIR/kokoro-gcs-by-gsutil.json"

#export \
#    GOOGLE_APPLICATION_CREDENTIALS="$KOKORO_GFILE_DIR/kokoro-gcs-by-gsutil.json"

gcloud auth list

./integration/runCompatibilityTests.sh -p domain-registry-dev -s sql
./integration/runCompatibilityTests.sh -p domain-registry-dev -s nomulus

./gradlew :integration:sqlIntegrationTest -PdevProject=domain-registry-dev -Pnomulus_version=nomulus-20200113-RC00 -Pschema_version=nomulus-20200113-RC00 -Ppublish_repo=https://storage.googleapis.com/domain-registry-dev-deployed-tags/maven

