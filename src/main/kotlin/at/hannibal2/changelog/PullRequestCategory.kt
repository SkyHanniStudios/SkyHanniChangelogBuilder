package at.hannibal2.changelog

enum class PullRequestCategory(val changelogName: String, val prPrefix: String) {
    FEATURE("New Features", "Feature"),
    IMPROVEMENT("Improvements", "Improvement"),
    FIX("Fixes", "Fix"),
    INTERNAL("Technical Details", "Backend"),
    REMOVAL("Removed Features", "Remove"),
    ;

    companion object {
        fun fromChangelogName(changelogName: String) = entries.firstOrNull { it.changelogName == changelogName }
        fun fromPrPrefix(prPrefix: String) = entries.firstOrNull { it.prPrefix == prPrefix }

        fun validCategories(): List<String> = entries.map { it.prPrefix }
    }
}