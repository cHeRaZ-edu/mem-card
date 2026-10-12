#!/bin/sh
# Verifica que el flavor btOnly NO pida INTERNET y que full si.
# Correr despues de: ./gradlew :app:assembleBtOnlyDebug :app:assembleFullDebug
set -eu

find_manifest() {
    find app/build/intermediates -ipath '*merged_manifest*' -ipath "*$1*" -name AndroidManifest.xml | head -1
}

bt="$(find_manifest btOnlyDebug)"
full="$(find_manifest fullDebug)"

if [ -z "$bt" ] || [ -z "$full" ]; then
    echo "no encontre los manifiestos combinados (btOnly='$bt' full='$full')" >&2
    exit 1
fi

if grep -q 'android.permission.INTERNET' "$bt"; then
    echo "btOnly pide INTERNET: $bt" >&2
    exit 1
fi

if ! grep -q 'android.permission.INTERNET' "$full"; then
    echo "full deberia pedir INTERNET: $full" >&2
    exit 1
fi

echo "ok: btOnly sin INTERNET, full con INTERNET"
