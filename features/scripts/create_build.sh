#!/usr/bin/env bash
set -e

# Build test app
cd features/fixtures/app
./gradlew :app:clean :app:bugsnagCreate${1}Build -x :app:lint --stacktrace $CUSTOM_JVM_ARGS
