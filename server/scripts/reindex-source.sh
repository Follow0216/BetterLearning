#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
server_dir="$(cd "$script_dir/.." && pwd)"
app_jar="${SERVER_APP_JAR:-$server_dir/app/target/server.jar}"

if [[ ! -f "$app_jar" ]]; then
    printf '%s\n' 'JAR_NOT_FOUND: 请先打包 server/app/target/server.jar' >&2
    exit 4
fi

if [[ -z "${APP_AGENT_RETENTION_DELETE_LOG_ROOT:-}" ]]; then
    printf '%s\n' 'JOURNAL_ROOT_REQUIRED: 请配置独立删除日志目录' >&2
    exit 2
fi
# 维护 CLI 只验证并重放既有日志；不创建日志，也不启动清理任务。
export APP_AGENT_RETENTION_INITIALIZE_JOURNAL=false
export APP_AGENT_RETENTION_SWEEP_ENABLED=false

exec java -Dloader.main=com.betterlearning.server.retrieval.RetrievalReindexCommand \
    -cp "$app_jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
