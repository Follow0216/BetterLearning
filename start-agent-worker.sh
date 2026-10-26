#!/usr/bin/env bash
set -euo pipefail

TASK_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
TASK_WORKER_JAR="$TASK_REPO_ROOT/server/app/target/server.jar"

if [[ ! -f "$TASK_WORKER_JAR" ]]; then
    echo "请先在 server/ 执行 ./mvnw -pl app -am -DskipTests package。" >&2
    exit 1
fi

for required in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD APP_FILES_ROOT APP_AGENT_RETENTION_DELETE_LOG_ROOT; do
    if [[ -z "${!required:-}" ]]; then
        echo "缺少 worker 必需环境变量：$required" >&2
        exit 1
    fi
done

# This process gets its own injected environment; no implicit loading of the API's .env.local.
export APP_AGENT_WORKFLOW_ENABLED=true
export APP_AGENT_WORKFLOW_WORKER_ENABLED=true
export APP_AGENT_WORKFLOW_DISPATCHER_ENABLED=false

TASK_JAVA_BINARY=java
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    TASK_JAVA_BINARY="$JAVA_HOME/bin/java"
fi

exec "$TASK_JAVA_BINARY" -Dloader.main=com.betterlearning.worker.AgentWorkerApplication \
    -cp "$TASK_WORKER_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
