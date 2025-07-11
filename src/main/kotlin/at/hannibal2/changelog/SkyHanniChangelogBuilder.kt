package at.hannibal2.changelog

import at.hannibal2.changelog.Utils.charactersSinceSplit
import at.hannibal2.changelog.Utils.countCharacters
import at.hannibal2.changelog.Utils.matchMatcher
import at.hannibal2.changelog.Utils.offsetMinute
import com.google.gson.GsonBuilder
import java.util.*

object SkyHanniChangelogBuilder {

    private const val GITHUB_API_URL = "https://api.github.com/repos/hannibal002/SkyHanni"
    private const val GITHUB_URL = "https://github.com/hannibal002/SkyHanni"
    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    private val categoryPattern = "## Changelog (?<category>.+)".toPattern()
    private val changePattern = "\\+ (?<text>.+?) - (?<author>.+)".toPattern()
    private val changePatternNoAuthor = "\\+ (?<text>.+)".toPattern()
    private val extraInfoPattern = " {4}\\* (?<text>.+)".toPattern()
    private val prTitlePattern = "(?<prefix>.+): (?<title>.+)".toPattern()
    private val illegalStartPattern = "^[-=*+ ].*".toPattern()

    private var lastFetchParams: Pair<WhatToFetch, ModVersion?>? = null
    private var lastFetchResult: List<PullRequest>? = null
    private var currentTags: Array<Tag>? = null

    private fun fetchPullRequests(whatToFetch: WhatToFetch): List<PullRequest> {
        val url = "$GITHUB_API_URL/pulls?${whatToFetch.url}"
        val jsonString = Utils.getTextFromUrl(url).joinToString("\n")
        return gson.fromJson(jsonString, Array<PullRequest>::class.java).toList()
    }

    private fun getTags(): Array<Tag> {
        return currentTags ?: run {
            val jsonString = Utils.getTextFromUrl("$GITHUB_API_URL/tags").joinToString("\n")
            val tags = gson.fromJson(jsonString, Array<Tag>::class.java)
            currentTags = tags
            tags
        }
    }

    private fun doesTagExist(tag: String): Boolean {
        return getTags().any { it.name == tag }
    }

    private fun getDateOfMostRecentTag(specificPreviousVersion: ModVersion?): Pair<Date, Date> {
        val tags = getTags()

        val (targetTag, previousTag) = if (specificPreviousVersion != null) {
            val tag = tags.firstOrNull { it.name == specificPreviousVersion.asTag }
            if (tag == null) {
                throw IllegalArgumentException(
                    "Tag ${specificPreviousVersion.asTag} not found. " +
                            "Possible tags: ${tags.joinToString { it.name }}"
                )
            }
            val index = tags.indexOf(tag)
            tag to tags.getOrNull(index + 1)
        } else {
            tags.first() to null
        }

        val tagCommitUrl = targetTag.commit.url

        val commitJsonString = Utils.getTextFromUrl(tagCommitUrl).joinToString("\n")
        val commit = gson.fromJson(commitJsonString, Commit::class.java)

        return if (previousTag != null) {
            val nextTagCommitUrl = previousTag.commit.url
            val nextTagCommitJsonString = Utils.getTextFromUrl(nextTagCommitUrl).joinToString("\n")
            val nextTagCommit = gson.fromJson(nextTagCommitJsonString, Commit::class.java)
            commit.commit.author.date.offsetMinute() to nextTagCommit.commit.author.date.offsetMinute()
        } else {
            Date().offsetMinute() to commit.commit.author.date.offsetMinute()
        }
    }

    private fun getRelevantPrs(whatToFetch: WhatToFetch, specificPreviousVersion: ModVersion?): List<PullRequest> {
        val currentParams = whatToFetch to specificPreviousVersion

        if (currentParams == lastFetchParams) {
            return lastFetchResult ?: emptyList()
        }

        val foundPrs = fetchPullRequests(whatToFetch)

        val relevantPrs = if (whatToFetch == WhatToFetch.ALREADY_MERGED) {
            val (dateOfTargetTag, dateOfPreviousTag) = getDateOfMostRecentTag(specificPreviousVersion)
            foundPrs.filter { it.mergedAt != null && it.mergedAt < dateOfTargetTag && it.mergedAt > dateOfPreviousTag }
        } else {
            foundPrs.filter { !it.draft }
        }

        lastFetchParams = currentParams
        lastFetchResult = relevantPrs

        return relevantPrs
    }

    private data class ChangesResult(
        val changes: List<CodeChange>,
        val previousText: List<String>,
        val summaryText: List<String>
    )

    private fun getAllChanges(prs: List<PullRequest>): ChangesResult {
        val previousText = mutableListOf<String>()
        val summaryText = mutableListOf<String>()

        val allChanges = mutableListOf<CodeChange>()
        var wrongPrNames = 0
        var wrongPrDescription = 0
        var donePrs = 0
        val excludedPrs = mutableListOf<String>()

        previousText.add("")

        for (pullRequest in prs) {
            val prBody = pullRequest.body?.lines() ?: emptyList()
            if (prBody.any { it == "exclude_from_changelog" || it == "ignore_from_changelog" }) {
                excludedPrs.add(pullRequest.prInfo())
                continue
            }

            val (changes, changeErrors) = findChanges(prBody, pullRequest.htmlUrl)
            val titleErrors = findPullRequestNameErrors(pullRequest.title, changes)

            if (titleErrors.isNotEmpty()) {
                previousText.add("PR has incorrect name: ${pullRequest.prInfo()}")
                titleErrors.forEach { previousText.add("  - ${it.message}") }
                previousText.add("")
                wrongPrNames++
            }

            if (changeErrors.isNotEmpty()) {
                previousText.add("PR has errors: ${pullRequest.prInfo()}")
                changeErrors.forEach { previousText.add("  - ${it.formatLine()}") }
                previousText.add("")
                wrongPrDescription++
                continue
            }

            allChanges.addAll(changes)
            donePrs++
        }

        previousText.add("")

        excludedPrs.forEach { previousText.add("Excluded PR: $it") }

        summaryText.add("")
        val total = prs.size
        summaryText.add("Loaded $total PRs to be processed for changelog")
        if (excludedPrs.size > 0) {
            summaryText.add("Excluded ${excludedPrs.size} of these PRs because they were marked as `exclude_from_changelog`")
        }
        if (wrongPrNames > 0) summaryText.add("$wrongPrNames PRs had a wrong name")
        if (wrongPrDescription > 0) summaryText.add("$wrongPrDescription PRs had a wrong description")
        val procesName = if (total == donePrs) "all" else "$donePrs/$total"
        summaryText.add("Processed $procesName PRs for changelog")
        summaryText.add("Total changes found in these PRs: ${allChanges.size}")

        return ChangesResult(allChanges, previousText, summaryText)
    }

    fun generateChangelog(whatToFetch: WhatToFetch, version: ModVersion, specificPreviousVersion: ModVersion?) {
        val relevantPrs = getRelevantPrs(whatToFetch, specificPreviousVersion)
        val sortedPrs = relevantPrs.sortedBy(whatToFetch.sort)

        val changesResult = getAllChanges(sortedPrs)
        val allChanges = changesResult.changes
        val previousText = changesResult.previousText
        val summaryText = changesResult.summaryText

        previousText.forEach { println(it) }

        for (type in TextOutputType.entries) {
            getSpecificChangelog(allChanges, version, type)
        }

        summaryText.forEach { println(it) }
    }

    fun findChanges(prBody: List<String>, prLink: String): Pair<List<CodeChange>, List<ChangelogError>> {
        val changes = mutableListOf<CodeChange>()
        val errors = mutableListOf<ChangelogError>()

        var currentCategory: PullRequestCategory? = null
        var currentChange: CodeChange? = null

        loop@ for (line in prBody) {
            if (line.isBlank()) {

                if (currentCategory != null && currentChange == null) {
                    println("erroring on line")
                    errors.add(ChangelogError("Unexpected empty line after category declared", line))
                }

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
                currentChange = null

                continue@loop
            }

            val category = currentCategory ?: continue

            changePattern.matchMatcher(line) {
                val text = group("text").trim()
                val author = group("author").trim()

                if (author == "your_name_here") {
                    errors.add(ChangelogError("Author is not set", line))
                }

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of change line", line))
                }
                errors.addAll(checkWording(text, LineType.CHANGE))

                currentChange = CodeChange(text, category, prLink, author).also { changes.add(it) }
                continue@loop
            }
            changePatternNoAuthor.matchMatcher(line) {
                val text = group("text").trim()
                errors.add(ChangelogError("Author is not set", line))

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of change line", line))
                }
                errors.addAll(checkWording(text, LineType.CHANGE))

                continue@loop
            }

            extraInfoPattern.matchMatcher(line) {
                if (currentChange == null) {
                    errors.add(ChangelogError("Extra info without a change", line))
                }
                val change = currentChange ?: continue@loop
                val text = group("text").trim()
                if (text == "Extra info.") {
                    errors.add(ChangelogError("Extra info is not filled out", line))
                }

                illegalStartPattern.matchMatcher(text) {
                    errors.add(ChangelogError("Illegal start of extra info line", line))
                }
                errors.addAll(checkWording(text, LineType.EXTRA_INFO))

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

    private fun checkWording(text: String, type: LineType): List<ChangelogError> {
        val errors = mutableListOf<ChangelogError>()
        val firstChar = text.first()
        if (firstChar.isLowerCase()) {
            errors.add(ChangelogError("$type should start with a capital letter", text))
        }
        val low = text.lowercase()
        if (low.startsWith("add ") || low.startsWith("adds ")) {
            errors.add(ChangelogError("$type should start with 'Added' instead of 'Add'", text))
        }
        if (low.startsWith("fix ") || low.startsWith("fixes ")) {
            errors.add(ChangelogError("$type should start with 'Fixed' instead of 'Fix'", text))
        }
        if (low.startsWith("remove ") || low.startsWith("removes ")) {
            errors.add(ChangelogError("$type should start with 'Removed' instead of 'Remove'", text))
        }
        if (!text.endsWith('.')) {
            errors.add(ChangelogError("$type should end with a full stop", text))
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
                    errors.add(
                        PullRequestNameError(
                            "Unknown category: '$prefix', valid categories are: ${PullRequestCategory.validCategories()} " +
                                    "and expected categories based on your changes are: $expectedOptions"
                        )
                    )
                    null
                }
            }

            foundCategories.forEach { category ->
                if (category !in expectedCategories) {
                    errors.add(PullRequestNameError("PR has category '${category.prPrefix}' which is not in the changelog. Expected categories: $expectedOptions"))
                }
            }

            val fix = PullRequestCategory.FIX

            if (errors.isEmpty() && fix in expectedCategories && fix !in foundCategories) {
                errors.add(PullRequestNameError("PR title must include category 'Fix' if there are any fixes in the PR"))
            }

            return errors
        }

        errors.add(PullRequestNameError("PR title does not match the expected format of 'Category: Title'"))
        return errors
    }

    /**
     * To be used by gradle tasks to generate specific output types.
     */
    fun generateSpecificOutputType(modVersion: String, outputType: String): String {
        val version = ModVersion.fromString(modVersion)
        val type =
            TextOutputType.getFromName(outputType) ?: throw IllegalArgumentException("Unknown output type: $outputType")

        val previousVersion = if (doesTagExist(version.asTag)) version else null

        val whatToFetch = WhatToFetch.ALREADY_MERGED
        val relevantPrs = getRelevantPrs(whatToFetch, previousVersion)
        val sortedPrs = relevantPrs.sortedBy(whatToFetch.sort)

        val changes = getAllChanges(sortedPrs).changes
        return generateChangelogText(changes, version, type, true).joinToString("\n")
    }

    private const val START_DIFF = "```diff"
    private const val END_DIFF = "```"
    private const val BORDER = "================================================================================="

    private fun getSpecificChangelog(changes: List<CodeChange>, version: ModVersion, type: TextOutputType) {
        val text = generateChangelogText(changes, version, type, false)

        println("")
        println("Output type: $type")

        if (type != TextOutputType.GITHUB) {
            val totalLength = text.countCharacters()
            println("$totalLength/2000 characters used")
        }
        println(BORDER)
        text.forEach { println(it) }
        println(BORDER)
    }

    // todo add unit tests for this output
    private fun generateChangelogText(
        changes: List<CodeChange>,
        version: ModVersion,
        type: TextOutputType,
        displaySplits: Boolean,
    ): List<String> {
        val list = mutableListOf<String>()
        val showWhereToSplit = displaySplits && type != TextOutputType.GITHUB
        val startDiff = if (type == TextOutputType.DISCORD_PUBLIC) START_DIFF else null
        val endDiff = if (type == TextOutputType.DISCORD_PUBLIC) END_DIFF else null

        list.add("## SkyHanni ${version.asTitle}")

        for (category in PullRequestCategory.entries) {
            if (type == TextOutputType.DISCORD_PUBLIC && category == PullRequestCategory.INTERNAL) continue

            val relevantChanges = changes.filter { it.category == category }.sortedBy { it.text.lowercase() }
            if (relevantChanges.isEmpty()) continue
            list.add("### " + category.changelogName)

            startDiff?.let { list.add(it) }

            for (change in relevantChanges) {
                val changePrefix = getPrefix(category, type)
                val changeText = "$changePrefix ${change.text} - ${change.author} ${type.prReference(change)}".trim()

                if (showWhereToSplit && (list.charactersSinceSplit() + changeText.length) > 1950) {
                    endDiff?.let { list.add(it) }
                    list.add(Utils.LIST_SPLIT_TEXT)
                    startDiff?.let { list.add(it) }
                }

                list.add(changeText)
                for (extraInfo in change.extraInfo) {
                    val extraInfoText = "${type.extraInfoPrefix} $extraInfo"

                    if (showWhereToSplit && (list.charactersSinceSplit() + extraInfoText.length) > 1950) {
                        endDiff?.let { list.add(it) }
                        list.add(Utils.LIST_SPLIT_TEXT)
                        startDiff?.let { list.add(it) }
                    }
                    list.add(extraInfoText)
                }
            }
            endDiff?.let { list.add(it) }
        }

        if (type == TextOutputType.DISCORD_PUBLIC) {
            if (showWhereToSplit && list.charactersSinceSplit() > 1750) {
                list.add(Utils.LIST_SPLIT_TEXT)
            }

            val releaseLink = "$GITHUB_URL/releases/tag/${version.asTag}"
            list.add("For more details, see the [full changelog](<$releaseLink>)")
            list.add("Downloads:")
            val forge189Link = "$GITHUB_URL/releases/download/${version.asTag}/SkyHanni-${version.asTag}-mc1.8.9.jar"
            list.add("- [Forge 1.8.9]($forge189Link)")
            val fabric1215Link = "$GITHUB_URL/releases/download/${version.asTag}/SkyHanni-${version.asTag}-mc1.21.5.jar"
            list.add("- [Fabric 1.21.5]($fabric1215Link)")
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

private enum class LineType(val displayName: String) {
    CHANGE("Change"),
    EXTRA_INFO("Extra info"),
    CATEGORY("Category"),
    ;
    override fun toString(): String {
        return displayName
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
    ALREADY_MERGED("state=closed&sort=updated&direction=desc&per_page=100",
        { it.mergedAt ?: Date(0) }),
    OPEN_PRS("state=open&sort=updated&direction=desc&per_page=100", { it.updatedAt }),
}

enum class TextOutputType(val extraInfoPrefix: String, val prReference: (CodeChange) -> String) {
    DISCORD_INTERNAL("  - ", { "[PR](<${it.prLink}>)" }),
    GITHUB("   +", { "(${it.prLink})" }),
    // TODO change pr reference here, this is currently only a workaround
    DISCORD_PUBLIC(" - ", { "" }), ;

    companion object {
        fun getFromName(name: String) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
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

fun main() {
    // stable, beta, bugfix
    var version = ModVersion(4, 1, 0)

    /**
     * If you want to generate a changelog for a specific previous version,
     * set this to the version you want to generate the changelog for.
     *
     * Leave this as null to use the above version.
     * Currently, this only works for recent versions, as the GitHub API only fetches the most recent 100 PRs unless
     * I make the code dig through all the pages.
     *
     * Does not work with legacy versions (0.x)
     */
    val specificPreviousVersion: ModVersion? = null
//    val specificPreviousVersion: ModVersion? = ModVersion(3, 18, 0)

    var whatToFetch = WhatToFetch.ALREADY_MERGED

    @Suppress("KotlinConstantConditions")
    if (specificPreviousVersion != null) {
        whatToFetch = WhatToFetch.ALREADY_MERGED
        version = specificPreviousVersion
    }

    SkyHanniChangelogBuilder.generateChangelog(whatToFetch, version, specificPreviousVersion)
}

// smart ai prompt for formatting
// I send you the changelog of a skyhanni version, a skyblock mod, below. do not touch the formatting, especially in the url/the name of the dev at the end of some lines.  do not make the sentences overly wording and try to compact it as mush as possible without losing information. additionally find typos/grammatical errors and fix them.  suggest better wording if applicable. keep the sentence beginning as "added", "fixed", etc. send me the whole text in one code block as output. Additionally, feature names are written with first letter uppercase, and lines withtout a author at the suffix describe the change above in more detaul, dont need the "added", "changed" etc, prefix theefore.

