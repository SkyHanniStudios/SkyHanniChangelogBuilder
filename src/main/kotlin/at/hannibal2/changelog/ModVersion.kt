package at.hannibal2.changelog

data class ModVersion(val stable: Int, val beta: Int, val bugfix: Int) {

    companion object {
        fun fromString(version: String): ModVersion {

            val parts = version.split('.')
            return ModVersion(
                parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
    }

    val isBeta = beta != 0

    val asString: String
        get() = toString()

    override fun toString(): String {
        return "$stable.$beta.$bugfix"
    }

    val asTitle = "Version $asString"
    val asTag = asString

    operator fun compareTo(other: ModVersion): Int {
        return when {
            stable != other.stable -> stable.compareTo(other.stable)
            beta != other.beta -> beta.compareTo(other.beta)
            else -> bugfix.compareTo(other.bugfix)
        }
    }
}
