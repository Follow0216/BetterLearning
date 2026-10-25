#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
server_dir="$(cd "$script_dir/.." && pwd)"
app_jar="${SERVER_APP_JAR:-$server_dir/app/target/server.jar}"

if [[ ! -f "$app_jar" ]]; then
    printf '%s\n' 'JAR_NOT_FOUND: 请先打包 server/app/target/server.jar' >&2
    exit 4
fi

exec java -Dloader.main=com.betterlearning.server.retrieval.RetrievalReindexCommand \
    -cp "$app_jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
