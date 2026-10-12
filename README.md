# MemCard

Guarda las partidas de tus emuladores como un control de versiones (estilo git) y las sincroniza
entre dispositivos. Como las memory cards de PlayStation, pero con historial.

Estado: **M0, esqueleto**. Todavia no sincroniza nada.

## Versiones de la app

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
