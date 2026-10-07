#!/bin/sh
set -eu

CONNECT_URL="${DEBEZIUM_URL:-http://debezium:8083}"

echo "Waiting for Debezium Connect at ${CONNECT_URL}..."
until curl -sf "${CONNECT_URL}/connectors" >/dev/null; do
    sleep 2
done

register_connector() {
    name="$1"
    template_file="$2"

    existing_code=$(curl -s -o /dev/null -w "%{http_code}" "${CONNECT_URL}/connectors/${name}")
    if [ "${existing_code}" = "200" ]; then
        echo "Connector '${name}' already exists; skipping creation."
        return 0
    fi

    rendered=$(sed \
        -e "s|__POSTGRES_USER__|${POSTGRES_USER}|g" \
        -e "s|__POSTGRES_PASSWORD__|${POSTGRES_PASSWORD}|g" \
        -e "s|__POSTGRES_DB__|${POSTGRES_DB}|g" \
        "${template_file}")

    response_file="/tmp/${name}.resp"
    status_code=$(printf '%s' "${rendered}" | curl -s -o "${response_file}" -w "%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -d @- \
        "${CONNECT_URL}/connectors")

    if [ "${status_code}" = "200" ] || [ "${status_code}" = "201" ] || [ "${status_code}" = "409" ]; then
        echo "Connector '${name}' registered (HTTP ${status_code})."
        return 0
    fi

    echo "Failed to register connector '${name}' (HTTP ${status_code}):"
    cat "${response_file}"
    echo ""
    echo "Hint: ensure console or admin has run Flyway migrations before starting the pipeline profile."
    return 1
}

register_connector "hyperllm-config-connector" "/etc/hyperllm/debezium/hyperllm-config-connector.json"

if [ "${ENABLE_OUTBOX_CONNECTOR:-false}" = "true" ]; then
    register_connector "hyperllm-outbox-connector" "/etc/hyperllm/debezium/hyperllm-outbox-connector.json"
else
    echo "Skipping hyperllm-outbox-connector (ENABLE_OUTBOX_CONNECTOR=false; enable after V4 outbox migration)."
fi
