package com.cheraz.memcard.lab

object LabStats {
    data class Summary(val count: Int, val min: Long, val avg: Double, val p95: Long, val max: Long)

    fun summarize(values: List<Long>): Summary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        // percentil 95 por rango mas cercano, con enteros para no depender de decimales
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.size - 1)
        return Summary(
            count = sorted.size,
            min = sorted.first(),
            avg = sorted.average(),
            p95 = sorted[index],
            max = sorted.last(),
        )
    }

    /** KB/s (1 KB = 1024 bytes) para [bytes] en [nanos] nanosegundos. */
    fun kbPerSec(bytes: Long, nanos: Long): Double =
        if (nanos <= 0) 0.0 else bytes / 1024.0 / (nanos / 1_000_000_000.0)
}
