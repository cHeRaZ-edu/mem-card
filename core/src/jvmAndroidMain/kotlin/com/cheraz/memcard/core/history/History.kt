package com.cheraz.memcard.core.history

/**
 * Consultas sobre el arbol de versiones de un juego. Cada "camino" termina en una punta (tip):
 * una version sin hijos.
 */
class History(val versions: List<SaveVersion>) {
    private val byId = versions.associateBy { it.id }
    private val children: Map<String, List<SaveVersion>> =
        versions.filter { it.parent != null }.groupBy { it.parent!! }

    fun get(id: String?): SaveVersion? = id?.let { byId[it] }

    fun childrenOf(id: String): List<SaveVersion> = children[id].orEmpty()

    /** Puntas de todos los caminos, la mas nueva primero. */
    fun tips(): List<SaveVersion> = versions.filter { childrenOf(it.id).isEmpty() }.newestFirst()

    /** ¿[ancestor] esta en la cadena de padres de [id] (o es el mismo)? */
    fun isAncestorOrSelf(ancestor: String, id: String): Boolean {
        var cur: String? = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            if (cur == ancestor) return true
            cur = byId[cur]?.parent
        }
        return false
    }

    /** Cadena desde [id] hasta la raiz de su camino. */
    fun lineage(id: String): List<SaveVersion> {
        val out = ArrayList<SaveVersion>()
        var cur = byId[id]
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parent?.let { byId[it] }
        }
        return out
    }

    private fun List<SaveVersion>.newestFirst() =
        sortedWith(compareByDescending<SaveVersion> { it.createdAt }.thenByDescending { it.id })
}
