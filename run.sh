#!/bin/sh

export ES_ANDREWAZOR_CRYOSTAT_CRYOSTATSERVICE_AUTHORIZATION="Basic $(echo -n user:pass | base64)"
exec java -jar target/quarkus-agent-*-runner.jar
