# MemCard

Guarda las partidas de tus emuladores como un control de versiones (estilo git) y las sincroniza
entre dispositivos. Como las memory cards de PlayStation, pero con historial.

Estado: **W1, app de Windows con muve drive** (ver abajo). En Android siguen los laboratorios de M1:

- **Laboratorio SAF**: elegir carpeta de saves, listar, snapshot (hash + zip), restaurar a carpeta de prueba o (con el switch) a la carpeta real con respaldo previo.
- **Laboratorio Bluetooth**: un celular en «Esperar», el otro mide ping, throughput (1/5/20 MB) y estabilidad. Los celulares deben estar emparejados antes. Los logs se exportan con el boton «Exportar».

## Windows (W1)

El CI arma `mem-card-windows.zip` (artefacto `memcard-windows`): se descomprime y se abre `mem-card.exe`,
sin instalar nada; trae Java y `muve.exe` adentro.

1. **Ajustes**: servidor del drive, app (`memcard`, sin cifrado) y su llave. «Guardar y probar».
2. **Agregar juego**: nombre y la carpeta de saves de ese juego (acepta `%APPDATA%`).
3. Al guardar en el emulador, tras 6 s sin cambios se sube sola una version nueva y pregunta
   «¿que paso en esta sesion?». Cada 3 min revisa si otro equipo subio algo.
4. **Desde la nube**: enlaza en otro equipo un juego ya subido, eligiendo su carpeta local.

En el drive: `blobs/<hash>.zip` (snapshots inmutables) y `heads/<juego>.json` (historial, escrito con
`expected_version`). Si guardas partiendo de una version vieja pregunta antes de abrir otro camino;
si dices que no, esa partida se queda solo en ese equipo. Antes de restaurar siempre deja un respaldo
en `%APPDATA%\mem-card\cache\backups`.

## Linux: CachyOS (x86_64) y Switch con L4T (arm64)

Artefactos del CI: `memcard-linux-x64` (PC) y `memcard-linux-arm64` (Switch L4T Ubuntu). Cada uno trae
`mem-card-linux-<arch>.tar.gz` con Java y `muve` de esa arquitectura.

```bash
tar -xzf mem-card-linux-x64.tar.gz -C ~/Apps     # o -arm64 en la Switch
~/Apps/mem-card/install.sh                       # opcional: lo agrega al menu de aplicaciones
~/Apps/mem-card/bin/mem-card
```

Los datos van en `~/.config/mem-card` (o `$MEMCARD_HOME`). El tema claro/oscuro sigue a KDE o GNOME.
En la Switch, si la ventana sale negra o con fallas de dibujo: `SKIKO_RENDER_API=SOFTWARE ~/Apps/mem-card/bin/mem-card`.

## Versiones de la app Android

| Flavor | Para qué | Internet | Drive |
|---|---|---|---|
| `btOnly` | Play Store: sync solo por Bluetooth | **no pide el permiso** | no |
| `full` | Lo anterior + modo muve drive | sí | sí |

Se instalan lado a lado (`com.cheraz.memcard` y `com.cheraz.memcard.full`).

## Módulos

```
app/               UI Compose + flavors
core/              KMP (jvm + android): motor de versiones, protocolo, Transport. Sin Compose
transport-rfcomm/  Transport sobre Bluetooth clasico (BluetoothSocket)
backend-drive/     Sync contra muve drive (solo flavor full)
desktop/           App de Windows/Linux (Compose Desktop) que usa el CLI muve
```

## Plan

1. **M0** Esqueleto, flavors y CI (esto).
2. **M1** Laboratorio SAF (leer y escribir carpetas de saves) y Laboratorio BT (throughput, latencia, desconexiones).
3. **M2** Motor: merge de tres vias, hash de contenido, ULID, esquema SQLite.
4. **M3** Protocolo de sync sobre RFCOMM entre dos celulares.
5. **M4** UI real: juegos, historial, caminos.
6. **M5** Modo drive (necesita `expect` y `meta` en `sdk/mobile` de muve).
7. Despues: desktop Linux y Windows, y ficha de Play Store.

## Compilar

```bash
./gradlew :core:jvmTest
./gradlew :app:assembleBtOnlyDebug :app:assembleFullDebug
sh scripts/check-manifests.sh      # btOnly sin INTERNET, full con INTERNET
```

El CI de GitHub hace lo mismo en cada push y deja los APK debug como artefacto del run.
