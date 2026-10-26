#!/usr/bin/env bash
# muve falso para pruebas: guarda en @STORE@/<ruta> y la version en <ruta>.ver
S="@STORE@"
while [ $# -gt 0 ]; do
  case "$1" in
    --config|--app) shift 2 ;;
    -q) shift ;;
    *) break ;;
  esac
done
cmd="$1"; shift
over=0; expect=0; ver=0; pos=()
while [ $# -gt 0 ]; do
  case "$1" in
    --overwrite) over=1; shift ;;
    --expect) expect="$2"; shift 2 ;;
    --version) ver="$2"; shift 2 ;;
    *) pos+=("$1"); shift ;;
  esac
done
case "$cmd" in
  version) echo "muve fake" ;;
  info)
    f="$S/${pos[0]}"
    [ -f "$f" ] || { echo "muve: No existe ese archivo." >&2; exit 1; }
    printf 'ruta:     %s\nid:       1\nestado:   active\nversion:  %s\nsha256:   x\n' "${pos[0]}" "$(cat "$f.ver")" ;;
  get)
    f="$S/${pos[0]}"
    [ -f "$f" ] || { echo "muve: No existe ese archivo." >&2; exit 1; }
    if [ "$ver" != 0 ] && [ "$ver" != "$(cat "$f.ver")" ]; then echo "muve: No existe la version $ver de ese archivo." >&2; exit 1; fi
    cp "$f" "${pos[1]}" ;;
  put)
    src="${pos[0]}"; f="$S/${pos[1]}"
    mkdir -p "$(dirname "$f")"
    if [ -f "$f" ]; then
      [ "$over" = 1 ] || { echo "muve: ${pos[1]} ya existe en la nube; usa --overwrite" >&2; exit 1; }
      cur=$(cat "$f.ver")
      if [ "$expect" != 0 ] && [ "$expect" != "$cur" ]; then
        echo "muve: La version actual es la $cur, no la $expect. No se piso nada." >&2
        echo "      (version actual en la nube: $cur)" >&2; exit 1
      fi
      cp "$src" "$f"; echo $((cur + 1)) > "$f.ver"
    else
      [ "$over" = 0 ] || { echo "muve: No existe ese archivo." >&2; exit 1; }
      cp "$src" "$f"; echo 1 > "$f.ver"
    fi ;;
  ls)
    p="${pos[0]}"
    [ -d "$S/$p" ] || exit 0
    for f in "$S/$p"*; do
      case "$f" in *.ver) continue ;; esac
      [ -f "$f" ] || continue
      printf '   1.0 KB  v%-3s 26 oct 10:00  %s\n' "$(cat "$f.ver")" "${f#$S/}"
    done ;;
  *) echo "muve: comando desconocido" >&2; exit 2 ;;
esac
