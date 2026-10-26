package com.cheraz.memcard.core.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Una partida con nombre dentro de un juego ("Cazador Edu", "Cuenta recuperada"). Es como una rama
 * de git: sus versiones van en linea, una detras de otra. Cada dispositivo elige cual tiene en curso.
 */
@Serializable
data class Partida(
    val id: String,
    val name: String,
    val nameAt: Long = 0,
    val createdAt: Long,
    val device: String,
)

/**
 * Una version guardada de un save. Nunca se edita salvo la etiqueta: el historial solo crece.
 *
 * @param partida a que partida pertenece.
 * @param parent version de la que salio (puede ser de otra partida: de ahi nacio esta).
 * @param hash hash de contenido de [com.cheraz.memcard.core.engine.SnapshotService]; el zip vive en
 *   `blobs/<hash>.zip`.
 * @param label "¿que paso en esta sesion?". Gana la de [labelAt] mas reciente al mezclar.
 */
@Serializable
data class SaveVersion(
    val id: String,
    val partida: String,
    val parent: String? = null,
    val hash: String,
    val zipSize: Long,
    val files: Int,
    val bytes: Long,
    val device: String,
    val createdAt: Long,
    val label: String = "",
    val labelAt: Long = 0,
)

/**
 * Manifiesto de un juego (`games/<gameId>.json` en el drive). Es append-only: dos copias se mezclan
 * uniendo partidas y versiones por id, sin perder nada, y por eso un 409 se resuelve solo.
 */
@Serializable
data class GameManifest(
    val format: Int = FORMAT,
    val gameId: String,
    val name: String,
    val nameAt: Long = 0,
    val partidas: List<Partida> = emptyList(),
    val versions: List<SaveVersion> = emptyList(),
) {
    fun find(id: String?): SaveVersion? = id?.let { i -> versions.firstOrNull { it.id == i } }

    fun partida(id: String?): Partida? = id?.let { i -> partidas.firstOrNull { it.id == i } }

    /** Versiones de una partida, la mas vieja primero. */
    fun versionsOf(partidaId: String): List<SaveVersion> = versions.filter { it.partida == partidaId }

    /** Ultima version de una partida (su "punta"). */
    fun tipOf(partidaId: String): SaveVersion? = versionsOf(partidaId).lastOrNull()

    fun withVersion(v: SaveVersion): GameManifest = merge(this, copy(partidas = emptyList(), versions = listOf(v)))!!

    fun withPartida(p: Partida): GameManifest = merge(this, copy(partidas = listOf(p), versions = emptyList()))!!

    fun withLabel(id: String, label: String, at: Long): GameManifest =
        copy(versions = versions.map { if (it.id == id) it.copy(label = label, labelAt = at) else it })

    fun withPartidaName(id: String, name: String, at: Long): GameManifest =
        copy(partidas = partidas.map { if (it.id == id) it.copy(name = name, nameAt = at) else it })

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 2

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(text: String): GameManifest {
            val m = json.decodeFromString(serializer(), text)
            require(m.format == FORMAT) { "Manifiesto formato ${m.format}; esta version de mem-card usa el $FORMAT" }
            return m
        }

        /** Union por id. Para la misma version gana la etiqueta mas reciente; nombres igual. */
        fun merge(a: GameManifest?, b: GameManifest?): GameManifest? {
            if (a == null) return b
            if (b == null) return a
            require(a.gameId == b.gameId) { "No se pueden mezclar juegos distintos" }
            val versions = LinkedHashMap<String, SaveVersion>()
            for (v in a.versions + b.versions) {
                val old = versions[v.id]
                versions[v.id] = if (old == null || v.labelAt > old.labelAt) v else old
            }
            val partidas = LinkedHashMap<String, Partida>()
            for (p in a.partidas + b.partidas) {
                val old = partidas[p.id]
                partidas[p.id] = if (old == null || p.nameAt > old.nameAt) p else old
            }
            val (name, nameAt) = if (b.nameAt > a.nameAt) b.name to b.nameAt else a.name to a.nameAt
            return a.copy(
                name = name,
                nameAt = nameAt,
                partidas = partidas.values.sortedWith(compareBy({ it.createdAt }, { it.id })),
                versions = versions.values.sortedWith(compareBy({ it.createdAt }, { it.id })),
            )
        }
    }
}
