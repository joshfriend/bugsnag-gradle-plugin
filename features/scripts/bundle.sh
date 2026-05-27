#!/usr/bin/env bash
set -e

# Build test app
cd features/fixtures/app
./gradlew :app:clean :app:bundle$1 :app:bugsnagUpload${1}Bundle -x :app:lint --stacktrace $CUSTOM_JVM_ARGS
