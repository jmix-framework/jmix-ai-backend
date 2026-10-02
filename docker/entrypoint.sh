#!/bin/sh
set -e

missing=""
for name in OPENAI_API_KEY \
    MAIN_DATASOURCE_URL MAIN_DATASOURCE_USERNAME MAIN_DATASOURCE_PASSWORD \
    PGVECTOR_DATASOURCE_URL PGVECTOR_DATASOURCE_USERNAME PGVECTOR_DATASOURCE_PASSWORD; do
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        missing="$missing $name"
    fi
done

if [ -n "$missing" ]; then
    echo "Required environment variables are not set:$missing" >&2
    exit 1
fi

exec java org.springframework.boot.loader.launch.JarLauncher "$@"
