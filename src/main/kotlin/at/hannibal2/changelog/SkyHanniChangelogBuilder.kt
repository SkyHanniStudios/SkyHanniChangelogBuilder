package at.hannibal2.changelog

import at.hannibal2.changelog.Utils.matchMatcher
import com.google.gson.GsonBuilder
import java.util.Date

object SkyHanniChangelogBuilder {

    private const val GITHUB_API_URL = "https://api.github.com/repos/hannibal002/SkyHanni"
    private const val GITHUB_URL = "https://github.com/hannibal002/SkyHanni"
    private const val BORDER = "================================================================================="
    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    private val categoryPattern = "## Changelog (?<category>.+)".toPattern()
    private val changePattern = "\\+ (?<text>.+) - (?<author>.+)".toPattern()
    private val changePatternNoAuthor = "\\+ (?<text>.+)".toPattern()
    private val extraInfoPattern = " {4}\\* (?<text>.+)".toPattern()
    private val prTitlePattern = "(?<prefix>.+): (?<title>.+)".toPattern()
    private val illegalStartPattern = "^[-=*+ ].*".toPattern()

    private fun fetchPullRequests(whatToFetch: WhatToFetch): List<PullRequest> {
        val url = "$GITHUB_API_URL/pulls?${whatToFetch.url}"
        val jsonString = Utils.getTextFromUrl(url).joinToString("\n")
        return gson.fromJson(jsonString, Array<PullRequest>::class.java).toList()
    }

    private fun getDateOfMostRecentTag(): Date {
        val jsonString = Utils.getTextFromUrl("$GITHUB_API_URL/tags").joinToString("\n")
        val tags = gson.fromJson(jsonString, Array<Tag>::class.java)
        val mostRecentTag = tags.first()

        val tagCommitUrl = mostRecentTag.commit.url

        val commitJsonString = Utils.getTextFromUrl(tagCommitUrl).joinToString("\n")
        val commit = gson.fromJson(commitJsonString, Commit::class.java)

        return commit.commit.author.date
    }

    private fun filterOnlyRelevantPrs(prs: List<PullRequest>): List<PullRequest> {
        val dateOfMostRecentTag = getDateOfMostRecentTag()
        return prs.filter { it.mergedAt != null && it.mergedAt > dateOfMostRecentTag }
    }

    fun generateChangelog(whatToFetch: WhatToFetch, version: UpdateVersion) {
        val foundPrs = fetchPullRequests(whatToFetch)
        val relevantPrs = if (whatToFetch == WhatToFetch.ALREADY_MERGED) {
            filterOnlyRelevantPrs(foundPrs)
        } else {
            foundPrs.filter { !it.draft }
        }

        val sortedPrs = relevantPrs.sortedBy(whatToFetch.sort)

        val allChanges = mutableListOf<CodeChange>()

        var wrongPrNames = 0
        var wrongPrDescription = 0
        var donePrs = 0
        val excludedPrs = mutableListOf<String>()

        println()

        for (pullRequest in sortedPrs) {
            val prBody = pullRequest.body?.lines() ?: emptyList()
            if (prBody.any { it == "exclude_from_changelog" }) {
                excludedPrs.add(pullRequest.prInfo())
                continue
            }

            val (changes, changeErrors) = findChanges(prBody, pullRequest.htmlUrl)
            val titleErrors = findPullRequestNameErrors(pullRequest.title, changes)

            if (titleErrors.isNotEmpty()) {
                println("PR has incorrect name: ${pullRequest.prInfo()}")
                titleErrors.forEach { println("  - ${it.message}") }
                println()
                wrongPrNames++
            }

            if (changeErrors.isNotEmpty()) {
                println("PR has errors: ${pullRequest.prInfo()}")
                changeErrors.forEach { println("  - ${it.formatLine()}") }
                println()
                wrongPrDescription++
                continue
            }

            allChanges.addAll(changes)
            donePrs++
        }

        println()
        excludedPrs.forEach { println("Excluded PR: $it") }

        TextOutputType.entries.forEach { type ->
            printChangelog(allChanges, version, type)
        }

        println()
        println("${sortedPrs.count()} valid PRs found")
        println("Excluded PRs: ${excludedPrs.size}")
        println("PRs with wrong names: $wrongPrNames")
        println("PRs with wrong descriptions: $wrongPrDescription")
        println("Done PRs: $donePrs")
        println("Total changes found: ${allChanges.size}")
    }

    // todo implement tests for this
    fun findChanges(prBody: List<String>, prLink: String): Pair<List<CodeChange>, List<ChangelogError>> {
        val changes = mutableListOf<CodeChange>()
        val errors = mutableListOf<ChangelogError>()

        var currentCategory: PullRequestCategory? = null
        var currentChange: CodeChange? = null

        loop@ for (line in prBody) {
            if (line.isBlank()) {
                currentCategory = null
                currentChange = null
                continue
            }

            categoryPattern.matchMatcher(line) {
                val categoryName = group("category")

                currentCategory = PullRequestCategory.fromChangelogName(categoryName)
                if (currentCategory == null) {
                    errors.add(ChangelogError("Unknown category: $categoryName", line))
                }

                continue@loop
            }

            val category = currentCategory ?: continue

            changePattern.matchMatcher(line) {
                val text = group("text")
                val author = group("author")

                if (author == "your_name_here") {
                    errors.add(ChangelogError("Author is not set", line))
                }

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of change line", line))
                }
                errors.addAll(checkWording(text))

                currentChange = CodeChange(text, category, prLink, author).also { changes.add(it) }
                continue@loop
            }
            changePatternNoAuthor.matchMatcher(line) {
                val text = group("text")
                errors.add(ChangelogError("Author is not set", line))

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of change line", line))
                }
                errors.addAll(checkWording(text))

                continue@loop
            }

            extraInfoPattern.matchMatcher(line) {
                if (currentChange == null) {
                    errors.add(ChangelogError("Extra info without a change", line))
                }
                val change = currentChange ?: continue@loop
                val text = group("text")
                if (text == "Extra info.") {
                    errors.add(ChangelogError("Extra info is not filled out", line))
                }

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of extra info line", line))
                }
                errors.addAll(checkWording(text))

                change.extraInfo.add(text)
                continue@loop
            }

            errors.add(ChangelogError("Unknown line after changes started being declared", line))
        }

        if (changes.isEmpty() && errors.isEmpty()) {
            errors.add(ChangelogError("No changes detected in this pull request", ""))
        }
        return changes to errors
    }

    private fun checkWording(text: String): List<ChangelogError> {
        val errors = mutableListOf<ChangelogError>()
        val firstChar = text.first()
        if (firstChar.isLowerCase()) {
            errors.add(ChangelogError("Change should start with a capital letter", text))
        }
        if (!firstChar.isLetter()) {
            errors.add(
                ChangelogError(
                    "Change should start with a letter instead of a number or special character",
                    text
                )
            )
        }
        val low = text.lowercase()
        if (low.startsWith("add ") || low.startsWith("adds ")) {
            errors.add(ChangelogError("Change should start with 'Added' instead of 'Add'", text))
        }
        if (low.startsWith("fix ") || low.startsWith("fixes ")) {
            errors.add(ChangelogError("Change should start with 'Fixed' instead of 'Fix'", text))
        }
        if (low.startsWith("improve ") || low.startsWith("improves ")) {
            errors.add(ChangelogError("Change should start with 'Improved' instead of 'Improve'", text))
        }
        if (low.startsWith("remove ") || low.startsWith("removes ")) {
            errors.add(ChangelogError("Change should start with 'Removed' instead of 'Remove'", text))
        }
        if (!text.endsWith('.')) {
            errors.add(ChangelogError("Change should end with a full stop", text))
        }

        return errors
    }

    fun findPullRequestNameErrors(prTitle: String, changes: List<CodeChange>): List<PullRequestNameError> {
        val errors = mutableListOf<PullRequestNameError>()
        if (changes.isEmpty()) {
            return errors
        }

        prTitlePattern.matchMatcher(prTitle) {
            val prefixText = group("prefix")

            if (prefixText.contains("/") || prefixText.contains("&")) {
                errors.add(PullRequestNameError("PR categories shouldn't be separated by '/' or '&', use ' + ' instead"))
            }

            val prPrefixes = prefixText.split(Regex("[+&/]")).map { it.trim() }
            val expectedCategories = changes.map { it.category }.toSet()
            val expectedOptions = expectedCategories.joinToString { it.prPrefix }

            val foundCategories = prPrefixes.mapNotNull { prefix ->
                PullRequestCategory.fromPrPrefix(prefix) ?: run {
                    errors.add(PullRequestNameError("Unknown category: '$prefix', valid categories are: ${PullRequestCategory.validCategories()} " +
                            "and expected categories based on your changes are: $expectedOptions"))
                    null
                }
            }

            foundCategories.forEach { category ->
                if (category !in expectedCategories) {
                    errors.add(PullRequestNameError("PR has category '${category.prPrefix}' which is not in the changelog. Expected categories: $expectedOptions"))
                }
            }
            return errors
        }

        errors.add(PullRequestNameError("PR title does not match the expected format of 'Category: Title'"))
        return errors
    }

    // todo add indicators of where to copy paste if over 2000 characters
    private fun printChangelog(changes: List<CodeChange>, version: UpdateVersion, type: TextOutputType) {
        val text = generateChangelogText(changes, version, type)

        println("")
        println("Output type: $type")

        if (type != TextOutputType.GITHUB) {
            val totalLength = text.sumOf { it.length } + text.size - 1
            println("$totalLength/2000 characters used")
        }
        println(BORDER)
        text.forEach { println(it) }
        println(BORDER)
    }

    private fun generateChangelogText(
        changes: List<CodeChange>,
        version: UpdateVersion,
        type: TextOutputType
    ): List<String> {
        val list = mutableListOf<String>()
        list.add("## ${version.asTitle}")

        for (category in PullRequestCategory.entries) {
            if (type == TextOutputType.DISCORD_PUBLIC && category == PullRequestCategory.INTERNAL) continue

            val relevantChanges = changes.filter { it.category == category }
            if (relevantChanges.isEmpty()) continue
            list.add("### " + category.changelogName)
            if (type == TextOutputType.DISCORD_PUBLIC) {
                list.add("```diff")
            }
            for (change in relevantChanges) {
                val changePrefix = getPrefix(category, type)
                list.add("$changePrefix ${change.text} - ${change.author} ${type.prReference(change)}")
                for (extraInfo in change.extraInfo) {
                    list.add("${type.extraInfoPrefix} $extraInfo")
                }
            }
            if (type == TextOutputType.DISCORD_PUBLIC) {
                list.add("```")
            }
        }

        if (type == TextOutputType.DISCORD_PUBLIC) {
            val releaseLink = "$GITHUB_URL/releases/tag/${version.asTag}"
            list.add("For more details, see the [full changelog](<$releaseLink>)")
            val downloadLink = "$GITHUB_URL/releases/download/${version.asTag}/SkyHanni-${version.asTag}.jar"
            list.add("Download link: $downloadLink")
        }

        return list
    }

    private fun getPrefix(category: PullRequestCategory, type: TextOutputType): String = when (type) {
        TextOutputType.DISCORD_INTERNAL -> "-"
        TextOutputType.GITHUB -> "+"
        TextOutputType.DISCORD_PUBLIC -> when (category) {
            PullRequestCategory.FEATURE -> "+"
            PullRequestCategory.IMPROVEMENT -> "+"
            PullRequestCategory.FIX -> "~"
            PullRequestCategory.INTERNAL -> ""
            PullRequestCategory.REMOVAL -> "-"
        }
    }
}

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

        fun validCategories() = entries.joinToString { it.prPrefix }
    }
}

enum class WhatToFetch(val url: String, val sort: (PullRequest) -> Date) {
    ALREADY_MERGED("state=closed&sort=updated&direction=desc&per_page=150", { it.mergedAt ?: Date(0) }),
    OPEN_PRS("state=open&sort=updated&direction=desc&per_page=150", { it.updatedAt }),
}

enum class TextOutputType(val extraInfoPrefix: String, val prReference: (CodeChange) -> String) {
    DISCORD_INTERNAL(" = ", { "[PR](<${it.prLink}>)" }),
    GITHUB("  + ", { " (${it.prLink})" }),
    DISCORD_PUBLIC(" - ", { "" }),
}

class CodeChange(val text: String, val category: PullRequestCategory, val prLink: String, val author: String) {
    val extraInfo = mutableListOf<String>()
}

class ChangelogError(val message: String, private val relevantLine: String) {
    fun formatLine(): String {
        val lineText = if (relevantLine.isBlank()) "" else " in text: `$relevantLine`"
        return "$message$lineText"
    }
}

class PullRequestNameError(val message: String)

// Not having a full version can be used for creating the changelog between the final beta and the full version
class UpdateVersion(fullVersion: String, betaVersion: String?) {
    val asTitle = "Version $fullVersion${betaVersion?.let { " Beta $it" } ?: ""}"
    val asTag = "$fullVersion${betaVersion?.let { ".Beta.$it" } ?: ""}"

    constructor(versionString: String) : this(extractVersion(versionString).first, extractVersion(versionString).second)

    companion object {
        private fun extractVersion(versionString: String): Pair<String, String?> {
            val split = versionString.split(",").map { it.trim() }
            val fullVersion = if (split[0].startsWith("0.")) split[0] else "0.${split[0]}"
            val betaVersion = split.getOrNull(1)
            return fullVersion to betaVersion
        }
    }
}

fun main() {
    // todo maybe change the way version is handled
    val version = UpdateVersion("0.28", "1")
    SkyHanniChangelogBuilder.generateChangelog(WhatToFetch.ALREADY_MERGED, version)
}

// smart AI prompt for formatting
// keep the formatting. just find typos and fix them in this changelog. also suggest slightly better wording if applicable. send me the whole text in one code block as output
