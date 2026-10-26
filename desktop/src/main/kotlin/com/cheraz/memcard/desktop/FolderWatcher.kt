package com.cheraz.memcard.desktop

import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

/**
 * Avisa cuando algo cambia dentro de una carpeta de saves (recursivo). En Windows usa el modo
 * FILE_TREE (una sola suscripcion a todo el arbol); en Linux registra cada subcarpeta.
 *
 * No decide nada: solo marca la carpeta como "sucia" con la hora del ultimo evento. Quien lo usa
 * espera a que pasen unos segundos sin eventos (el emulador termino de escribir) antes de hacer el snapshot.
 */
class FolderWatcher(private val onEvent: (gameId: String) -> Unit) {
    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = ConcurrentHashMap<WatchKey, Pair<String, Path>>()
    private val thread = Thread(::loop, "memcard-watcher").apply { isDaemon = true }

    @Volatile
    private var closed = false

    fun start() = thread.start()

    @Synchronized
    fun watch(gameId: String, folder: File) {
        unwatch(gameId)
        val root = folder.toPath()
        if (!Files.isDirectory(root)) return
        val tree = fileTreeModifier()
        if (tree != null) {
            runCatching { keys[root.register(service, KINDS, tree)] = gameId to root }.onSuccess { return }
        }
        registerAll(gameId, root)
    }

    @Synchronized
    fun unwatch(gameId: String) {
        keys.entries.filter { it.value.first == gameId }.forEach { (k, _) ->
            k.cancel()
            keys.remove(k)
        }
    }

    fun close() {
        closed = true
        runCatching { service.close() }
    }

    private fun registerAll(gameId: String, root: Path) {
        Files.walk(root).use { s ->
            s.filter { Files.isDirectory(it) }.forEach { dir ->
                runCatching { keys[dir.register(service, *KINDS)] = gameId to dir }
            }
        }
    }

    private fun loop() {
        while (!closed) {
            val key = try {
                service.take()
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            val entry = keys[key]
            if (entry == null) {
                key.reset()
                continue
            }
            val (gameId, dir) = entry
            for (ev in key.pollEvents()) {
                if (ev.kind() == OVERFLOW) continue
                val name = (ev.context() as? Path)?.toString() ?: continue
                if (name.endsWith(com.cheraz.memcard.core.source.FolderSource.TMP_SUFFIX)) continue
                // Linux: una carpeta nueva tambien hay que vigilarla.
                if (ev.kind() == ENTRY_CREATE && fileTreeModifier() == null) {
                    val child = dir.resolve(name)
                    if (Files.isDirectory(child)) synchronized(this) { registerAll(gameId, child) }
                }
                onEvent(gameId)
            }
            if (!key.reset()) keys.remove(key)
        }
    }

    private companion object {
        val KINDS: Array<WatchEvent.Kind<*>> = arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

        /** com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE, solo existe/funciona en Windows. */
        fun fileTreeModifier(): WatchEvent.Modifier? {
            if (!System.getProperty("os.name").lowercase().contains("win")) return null
            return runCatching {
                val cls = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier")
                cls.enumConstants.first { (it as Enum<*>).name == "FILE_TREE" } as WatchEvent.Modifier
            }.getOrNull()
        }
    }
}
