package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.history.GameManifest
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [DriveStore] sobre el CLI `muve`. Cada llamada es un proceso: los saves pesan poco y asi no hay que
 * reimplementar el cliente del API. La config de muve queda aislada en [workDir] (no toca la tuya) y
 * servidor, app y llave van por variables de entorno.
 *
 * muve no tiene salida JSON, asi que se leen sus textos: `version:  N` de `info`, y los errores
 * "No existe ese archivo", "version actual en la nube"/"No se piso nada" (409) y "ya existe en la nube".
 *
 * Lo usan desktop (muve.exe incluido) y Android (binario de muve dentro del APK).
 */
class MuveCli(
    private val config: () -> MuveConfig,
    private val workDir: File,
    private val timeoutSeconds: Long = 300,
) : DriveStore {

    /** @param env variables extra para el proceso (en Android: HOME, proxy, certificados). */
    data class MuveConfig(
        val executable: String,
        val server: String,
        val apiKey: String,
        val app: String = "memcard",
        val env: Map<String, String> = emptyMap(),
    ) {
        val configured get() = server.isNotBlank() && apiKey.isNotBlank()
    }

    class Result(val code: Int, val out: String, val err: String)

    fun run(vararg args: String, stdoutFile: File? = null): Result {
        val s = config()
        if (!s.configured) throw DriveException("Falta configurar el servidor y la llave del drive")
        workDir.mkdirs()
        val cmd = listOf(s.executable, "--config", File(workDir, "muve-config.json").absolutePath, "--app", s.app, "-q") + args
        val pb = ProcessBuilder(cmd).directory(workDir)
        pb.environment().apply {
            remove("MUVE_SECRET")
            remove("MUVE_CONFIG")
            putAll(s.env)
            put("MUVE_SERVER", s.server.trim().trimEnd('/'))
            put("MUVE_APP", s.app)
            put("MUVE_API_KEY", s.apiKey.trim())
        }
        val errFile = File.createTempFile("muve", ".err", workDir)
        val outFile = stdoutFile ?: File.createTempFile("muve", ".out", workDir)
        try {
            pb.redirectOutput(outFile).redirectError(errFile).redirectInput(ProcessBuilder.Redirect.from(nullFile()))
            val p = try {
                pb.start()
            } catch (e: java.io.IOException) {
                throw DriveException("No se pudo ejecutar muve (${s.executable}): ${e.message}")
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw DriveException("muve tardo mas de $timeoutSeconds s en '${args.firstOrNull()}'")
            }
            val out = if (stdoutFile == null) outFile.readText() else ""
            return Result(p.exitValue(), out, errFile.readText().trim())
        } finally {
            errFile.delete()
            if (stdoutFile == null) outFile.delete()
        }
    }

    private fun nullFile() = File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")

    /** Prueba la conexion: lista los manifiestos. Regresa cuantos juegos hay. */
    fun check(): Int = listGameIds().size

    fun version(): String = run("version").let { if (it.code == 0) it.out.trim() else it.err }

    override fun readManifest(gameId: String): RemoteManifest? {
        val path = DriveStore.manifestPath(gameId)
        val info = run("info", path)
        if (info.code != 0) {
            if (isNotFound(info.err)) return null
            throw DriveException(clean(info.err))
        }
        val version = VERSION_RE.find(info.out)?.groupValues?.get(1)?.toInt()
            ?: throw DriveException("No entendi la salida de 'muve info': ${info.out.take(200)}")
        // Se baja exactamente esa version: si alguien escribe en medio, el expected_version lo detecta.
        val tmp = File.createTempFile("head", ".json", workDir)
        try {
            val get = run("get", path, tmp.absolutePath, "--version", version.toString())
            if (get.code != 0) throw DriveException(clean(get.err))
            return RemoteManifest(GameManifest.fromJson(tmp.readText()), version)
        } finally {
            tmp.delete()
        }
    }

    override fun writeManifest(gameId: String, manifest: GameManifest, expectVersion: Int?) {
        val dir = File(workDir, "out").apply { mkdirs() }
        val tmp = File(dir, "$gameId.json")
        tmp.writeText(manifest.toJson())
        try {
            val path = DriveStore.manifestPath(gameId)
            val res = if (expectVersion == null) {
                run("put", tmp.absolutePath, path)
            } else {
                run("put", "--overwrite", "--expect", expectVersion.toString(), tmp.absolutePath, path)
            }
            if (res.code != 0) {
                if (isConflict(res.err)) throw DriveConflictException(clean(res.err))
                throw DriveException(clean(res.err))
            }
        } finally {
            tmp.delete()
        }
    }

    override fun uploadBlob(hash: String, file: File) {
        val res = run("put", file.absolutePath, DriveStore.blobPath(hash))
        // Ya existe (o alguien lo subio al mismo tiempo): es el mismo contenido, no es error.
        if (res.code != 0 && !res.err.contains(EXISTS) && !res.err.contains(RACE)) throw DriveException(clean(res.err))
    }

    override fun downloadBlob(hash: String, dest: File) {
        val res = run("get", DriveStore.blobPath(hash), dest.absolutePath)
        if (res.code != 0) throw DriveException(clean(res.err))
    }

    override fun listGameIds(): List<String> {
        val res = run("ls", "heads/")
        if (res.code != 0) throw DriveException(clean(res.err))
        return parseLs(res.out)
            .filter { it.startsWith("heads/") && it.endsWith(".json") }
            .map { it.removePrefix("heads/").removeSuffix(".json") }
    }

    companion object {
        private val VERSION_RE = Regex("""(?m)^version:\s+(\d+)\s*$""")
        private const val EXISTS = "ya existe en la nube"
        private const val RACE = "alguien creo un archivo en esa ruta"

        fun isNotFound(err: String) = err.contains("No existe ese archivo")

        fun isConflict(err: String) =
            err.contains("version actual en la nube") || err.contains("No se piso nada") ||
                err.contains(EXISTS) || err.contains(RACE)

        /** Ultima columna de cada linea de `muve ls` (la ruta). Las rutas de mem-card no llevan espacios. */
        fun parseLs(out: String): List<String> =
            out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringAfterLast(' ') }.toList()

        fun clean(err: String) = err.lines().joinToString(" ") { it.trim().removePrefix("muve: ") }.trim()
            .ifEmpty { "muve fallo sin mensaje" }
    }
}
