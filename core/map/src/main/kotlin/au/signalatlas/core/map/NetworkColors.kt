package au.signalatlas.core.map

import au.signalatlas.core.model.Network

/** One fixed colour per network, used for tower dots and legends. */
object NetworkColors {
    fun argb(n: Network): Int = when (n) {
        Network.TELSTRA -> 0xFF1E88E5.toInt()
        Network.OPTUS -> 0xFF00A39A.toInt()
        Network.TPG -> 0xFFE53935.toInt()
        Network.OPTUS_TPG_JV -> 0xFF8E6CD8.toInt()
        Network.NBN -> 0xFFFF8F00.toInt()
    }
}
