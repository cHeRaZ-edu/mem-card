package com.cheraz.memcard.core.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Una version guardada de un save. Nunca se edita salvo la etiqueta: el historial solo crece.
 *
 * @param parent version de la que salio (null = primera de su camino).
 * @param hash hash de contenido de [com.cheraz.memcard.core.engine.SnapshotService]; el zip vive en
 *   `blobs/<hash>.zip`.
 * @param label "¿que paso en esta sesion?". Gana la de [labelAt] mas reciente al mezclar.
 */
@Serializable
data class SaveVersion(
    val id: String,
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
 * Manifiesto de un juego (`heads/<gameId>.json` en el drive). Es append-only: dos copias se mezclan
 * uniendo versiones por id, sin perder nada, y por eso un 409 se resuelve solo.
 */
@Serializable
data class GameManifest(
    val format: Int = FORMAT,
    val gameId: String,
    val name: String,
    val nameAt: Long = 0,
    val versions: List<SaveVersion> = emptyList(),
) {
    fun find(id: String?): SaveVersion? = id?.let { i -> versions.firstOrNull { it.id == i } }

    fun withVersion(v: SaveVersion): GameManifest = merge(this, copy(versions = listOf(v)))!!

    fun withLabel(id: String, label: String, at: Long): GameManifest =
        copy(versions = versions.map { if (it.id == id) it.copy(label = label, labelAt = at) else it })

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(text: String): GameManifest {
            val m = json.decodeFromString(serializer(), text)
            require(m.format <= FORMAT) { "Manifiesto formato ${m.format}: actualiza mem-card" }
            return m
        }

        /** Union por id. Para la misma version gana la etiqueta mas reciente; el nombre igual. */
        fun merge(a: GameManifest?, b: GameManifest?): GameManifest? {
            if (a == null) return b
            if (b == null) return a
            require(a.gameId == b.gameId) { "No se pueden mezclar juegos distintos" }
            val byId = LinkedHashMap<String, SaveVersion>()
            for (v in a.versions + b.versions) {
                val old = byId[v.id]
                byId[v.id] = if (old == null || v.labelAt > old.labelAt) v else old
            }
            val (name, nameAt) = if (b.nameAt > a.nameAt) b.name to b.nameAt else a.name to a.nameAt
            return a.copy(
                name = name,
                nameAt = nameAt,
                versions = byId.values.sortedWith(compareBy({ it.createdAt }, { it.id })),
            )
        }
    }
}
