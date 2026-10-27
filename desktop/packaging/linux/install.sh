#!/bin/sh
# Agrega mem-card al menu de aplicaciones de tu usuario (KDE, GNOME...). No pide root ni copia nada:
# apunta a esta carpeta, asi que si la mueves vuelve a correr este script.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
chmod +x "$here/bin/mem-card" 2>/dev/null || true
chmod +x "$here"/lib/app/resources/muve 2>/dev/null || true
dir="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$dir"
cat > "$dir/mem-card.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=mem-card
Comment=Historial y sync de partidas guardadas
Exec="$here/bin/mem-card"
Terminal=false
Categories=Game;Utility;
DESKTOP
echo "Listo: mem-card ya aparece en el menu de aplicaciones."
