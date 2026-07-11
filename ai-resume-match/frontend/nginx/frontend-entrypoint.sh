#!/bin/sh
set -eu

if [ "${1:-}" = "nginx" ] || [ "${1:-}" = "nginx-debug" ]; then
    if [ -z "${API_TOKEN+x}" ] || [ -z "$API_TOKEN" ]; then
        echo "frontend-entrypoint: API_TOKEN is required" >&2
        exit 1
    fi

    token_without_newlines=$(printf '%s' "$API_TOKEN" | tr -d '\r\n')
    if [ "$token_without_newlines" != "$API_TOKEN" ]; then
        echo "frontend-entrypoint: API_TOKEN must not contain CR or LF" >&2
        exit 1
    fi

    API_TOKEN_NGINX=$(printf '%s' "$API_TOKEN" | sed \
        -e 's/\\/\\\\/g' \
        -e 's/"/\\"/g' \
        -e 's/\$/${api_token_dollar}/g')
    export API_TOKEN_NGINX
    NGINX_ENVSUBST_FILTER='^API_TOKEN_NGINX$'
    export NGINX_ENVSUBST_FILTER
    unset API_TOKEN
fi

exec /docker-entrypoint.sh "$@"
