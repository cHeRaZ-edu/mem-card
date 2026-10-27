#!/usr/bin/env bash
# Corre gradlew y, si falla, junta los errores de compilacion y de pruebas en anotaciones
# de GitHub (::error::), que se pueden leer por la API sin bajar el log completo.
set -o pipefail
log=$(mktemp)
if [ "${OS:-}" = "Windows_NT" ]; then gw=./gradlew.bat; else gw=./gradlew; fi
"$gw" "$@" 2>&1 | tee "$log"
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  emit() { # $1 = lineas; una anotacion con saltos de linea codificados
    msg=$(printf '%s' "$1" | head -c 3500 | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}')
    [ -n "$msg" ] && echo "::error::$msg"
  }
  emit "$(grep -E '^e: ' "$log" | head -25)"
  emit "$(grep -A12 'What went wrong' "$log" | head -40)"
  emit "$(grep -E 'FAILED|AssertionError|Exception:|expected:' "$log" | grep -v '^\s*at ' | head -25)"
fi
exit "$code"
