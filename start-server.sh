#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$SCRIPT_DIR"
SERVER_DIR="$REPO_ROOT/server"

REQUIRED_VARS=(
    SPRING_DATASOURCE_PASSWORD
    APP_JWT_SECRET_BASE64
    APP_JWT_ISSUER
    APP_JWT_AUDIENCE
    APP_FILES_ROOT
    APP_AGENT_RETENTION_DELETE_LOG_ROOT
)

CHECK_ONLY=false
ENV_FILE=""

usage() {
    cat <<'EOF'
用法: start-server.sh [--check]

  --check  仅检查 JDK 17 与必填环境变量是否存在，不启动服务。
EOF
}

parse_arguments() {
    if [[ $# -gt 1 ]]; then
        echo "错误：最多只接受一个参数。" >&2
        usage >&2
        exit 2
    fi
    case "${1:-}" in
        "")
            ;;
        --check)
            CHECK_ONLY=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "错误：未知参数：$1" >&2
            usage >&2
            exit 2
            ;;
    esac
}

resolve_env_file() {
    if [[ -n "${BETTERLEARNING_ENV_FILE:-}" ]]; then
        ENV_FILE="$BETTERLEARNING_ENV_FILE"
        if [[ ! -e "$ENV_FILE" ]]; then
            echo "错误：BETTERLEARNING_ENV_FILE 指定的环境文件不存在。" >&2
            exit 1
        fi
        if [[ ! -f "$ENV_FILE" || ! -r "$ENV_FILE" ]]; then
            echo "错误：BETTERLEARNING_ENV_FILE 指定的环境文件不可读。" >&2
            exit 1
        fi
        return
    fi
    if [[ -e "$REPO_ROOT/.env.local" ]]; then
        if [[ ! -f "$REPO_ROOT/.env.local" || ! -r "$REPO_ROOT/.env.local" ]]; then
            echo "错误：$REPO_ROOT/.env.local 不是可读文件。" >&2
            exit 1
        fi
        ENV_FILE="$REPO_ROOT/.env.local"
    fi
}

load_env_file() {
    local file="$1"
    local line_number=0
    local line key value
    while IFS= read -r line || [[ -n "$line" ]]; do
        line_number=$((line_number + 1))
        line="${line%$'\r'}"
        if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
            continue
        fi
        line="${line#"${line%%[![:space:]]*}"}"
        if [[ "$line" == export[[:space:]]* ]]; then
            line="${line#export}"
            line="${line#"${line%%[![:space:]]*}"}"
        fi
        if [[ "$line" != *=* ]]; then
            echo "错误：环境文件第 ${line_number} 行不是 KEY=VALUE 格式。" >&2
            return 1
        fi
        key="${line%%=*}"
        value="${line#*=}"
        key="${key%"${key##*[![:space:]]}"}"
        if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
            echo "错误：环境文件第 ${line_number} 行的变量名不合法。" >&2
            return 1
        fi
        if [[ ${#value} -ge 2 ]]; then
            if [[ "$value" == \"*\" && "$value" == *\" ]]; then
                value="${value:1:${#value}-2}"
            elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
                value="${value:1:${#value}-2}"
            fi
        fi
        printf -v "$key" '%s' "$value"
        export "$key"
    done < "$file"
}

java_major_version() {
    local java_bin="$1"
    local version
    version="$("$java_bin" -version 2>&1 | awk -F'"' '/ version / { print $2; exit }')"
    if [[ -z "$version" ]]; then
        return 1
    fi
    printf '%s\n' "${version%%.*}"
}

detect_jdk17() {
    local candidate=""
    local detected_home=""
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        candidate="$JAVA_HOME/bin/java"
        if [[ "$(java_major_version "$candidate" 2>/dev/null || true)" == "17" ]]; then
            return 0
        fi
    fi
    if command -v java >/dev/null 2>&1; then
        candidate="$(command -v java)"
        if [[ "$(java_major_version "$candidate" 2>/dev/null || true)" == "17" ]]; then
            detected_home="$("$candidate" -XshowSettings:properties -version 2>&1 \
                | awk -F'= ' '/java.home =/ { print $2; exit }')"
            if [[ -n "$detected_home" && -x "$detected_home/bin/java" ]]; then
                export JAVA_HOME="$detected_home"
            fi
            return 0
        fi
    fi
    echo "错误：需要 JDK 17，但未在 JAVA_HOME 或 PATH 中找到。" >&2
    return 1
}

require_variables() {
    local name
    local missing=()
    for name in "${REQUIRED_VARS[@]}"; do
        if [[ -z "$(printenv "$name" 2>/dev/null || true)" ]]; then
            missing+=("$name")
        fi
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "错误：缺少必填环境变量：${missing[*]}" >&2
        return 1
    fi
}

parse_arguments "$@"
resolve_env_file
if [[ -n "$ENV_FILE" ]]; then
    load_env_file "$ENV_FILE"
fi
detect_jdk17
require_variables

if [[ "$CHECK_ONLY" == true ]]; then
    echo "检查通过：JDK 17 可用，必填环境变量存在。"
    echo "说明：本检查不验证数据库/Redis 连接、JWT 格式或 Spring 配置；真正启动时由 Spring Boot 完整校验。"
    exit 0
fi

if [[ ! -x "$SERVER_DIR/mvnw" ]]; then
    echo "错误：未找到可执行的 server/mvnw。" >&2
    exit 1
fi

cd "$SERVER_DIR"
# 先由 reactor 打包依赖，再只启动 app；不能对所有模块执行 spring-boot:run。
./mvnw -pl app -am -DskipTests package
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    exec "$JAVA_HOME/bin/java" -jar "$SERVER_DIR/app/target/server.jar"
fi
exec java -jar "$SERVER_DIR/app/target/server.jar"
