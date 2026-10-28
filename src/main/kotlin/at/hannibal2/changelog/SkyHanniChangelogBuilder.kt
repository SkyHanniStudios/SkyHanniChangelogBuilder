package at.hannibal2.changelog

import at.hannibal2.changelog.Utils.charactersSinceSplit
import at.hannibal2.changelog.Utils.countCharacters
import at.hannibal2.changelog.Utils.matchMatcher
import at.hannibal2.changelog.Utils.offsetMinute
import com.google.gson.GsonBuilder
import java.util.*

object SkyHanniChangelogBuilder {

    private const val GITHUB_API_URL = "https://api.github.com/repos/SkyHanniStudios/SkyHanni"
    private const val GITHUB_URL = "https://github.com/SkyHanniStudios/SkyHanni"
    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    private val categoryPattern = "## Changelog (?<category>.+)".toPattern()

    /**
     * REGEX-TEST: + Change. - Author
     * REGEX-TEST: - Change. - Author
     * REGEX-TEST: * Change. - Author
     */
    private val changePattern = "^[*+-] (?<text>.+?) - (?<author>.+)".toPattern()
    private val changePatternNoAuthor = "^[*+-] (?<text>.+)".toPattern()

    /**
     * REGEX-TEST:     * Extra info
     * REGEX-TEST:     + Extra info
     * REGEX-TEST: 	- Extra info
     * REGEX-TEST:   + Extra info
     * REGEX-TEST:   - Extra info
     */
    private val extraInfoPattern = "(?: {2,4}|\\t)[*+-] (?<text>.+)".toPattern()
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
        val previousText = mutableListOf("")
        val allChanges = mutableListOf<CodeChange>()
        var wrongPrNames = 0
        var wrongPrDescription = 0
        var donePrs = 0
        val excludedPrs = mutableListOf<String>()

        val notPartOfChangelog = Regex("^(?:ignore|exclude|not_a_part)_from_changelog$")
        for (pullRequest in prs) {
            val prBody = pullRequest.body?.lines().orEmpty()
            if (prBody.any { notPartOfChangelog.matches(it.trim()) }) {
                excludedPrs.add(pullRequest.prInfo())
                continue
            }

            val (changes, changeErrors) = findChanges(prBody, pullRequest.htmlUrl)
            val titleErrors = findPullRequestNameErrors(pullRequest.title, changes)

            if (titleErrors.isNotEmpty()) {
                previousText.addErrorBlock("PR has incorrect name: ${pullRequest.prInfo()}", titleErrors.map { it.message })
                wrongPrNames++
            }

            if (changeErrors.isNotEmpty()) {
                previousText.addErrorBlock("PR has errors: ${pullRequest.prInfo()}", changeErrors.map { it.formatLine() })
                wrongPrDescription++
                continue
            }

            allChanges.addAll(changes)
            donePrs++
        }

        previousText.add("")
        excludedPrs.forEach { previousText.add("Excluded PR: $it") }

        val summaryText = buildSummaryText(
            total = prs.size,
            excluded = excludedPrs.size,
            wrongPrNames = wrongPrNames,
            wrongPrDescription = wrongPrDescription,
            donePrs = donePrs,
            totalChanges = allChanges.size,
        )
        return ChangesResult(allChanges, previousText, summaryText)
    }

    private fun MutableList<String>.addErrorBlock(header: String, errors: List<String>) {
        add(header)
        errors.forEach { add("  - $it") }
        add("")
    }

    private fun buildSummaryText(
        total: Int,
        excluded: Int,
        wrongPrNames: Int,
        wrongPrDescription: Int,
        donePrs: Int,
        totalChanges: Int,
    ): List<String> = buildList {
        add("")
        add("Loaded $total PRs to be processed for changelog")
        if (excluded > 0) {
            add("Excluded $excluded of these PRs because they were marked as `exclude_from_changelog`")
        }
        if (wrongPrNames > 0) add("$wrongPrNames PRs had a wrong name")
        if (wrongPrDescription > 0) add("$wrongPrDescription PRs had a wrong description")
        val processName = if (total == donePrs) "all" else "$donePrs/$total"
        add("Processed $processName PRs for changelog")
        add("Total changes found in these PRs: $totalChanges")
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
        val parser = ChangeParser(prLink)
        prBody.forEach { parser.parseLine(it) }
        return parser.result()
    }

    private class ChangeParser(private val prLink: String) {
        private val changes = mutableListOf<CodeChange>()
        private val errors = mutableListOf<ChangelogError>()
        private var currentCategory: PullRequestCategory? = null
        private var currentChange: CodeChange? = null

        fun parseLine(line: String) {
            if (handleBlankLine(line) || handleCategoryLine(line)) return
            val category = currentCategory ?: return
            if (handleChangeLine(line, category)) return
            if (handleChangeLineWithoutAuthor(line) || handleExtraInfoLine(line)) return
            errors.add(ChangelogError("Unknown line after changes started being declared", line))
        }

        fun result(): Pair<List<CodeChange>, List<ChangelogError>> {
            if (changes.isEmpty() && errors.isEmpty()) {
                errors.add(ChangelogError("No changes detected in this pull request", ""))
            }
            return changes to errors
        }

        private fun handleBlankLine(line: String): Boolean {
            if (!line.isBlank()) return false
            if (currentCategory != null && currentChange == null) {
                errors.add(ChangelogError("Unexpected empty line after category declared", line))
            }
            currentCategory = null
            currentChange = null
            return true
        }

        private fun handleCategoryLine(line: String): Boolean {
            categoryPattern.matchMatcher(line) {
                val categoryName = group("category")
                currentCategory = PullRequestCategory.fromChangelogName(categoryName)
                if (currentCategory == null) {
                    val validCategories = PullRequestCategory.entries.joinToString("`, `") { it.changelogName }
                    errors.add(
                        ChangelogError("Unknown category: `$categoryName`, possible categories: `$validCategories`", line)
                    )
                }
                currentChange = null
                return true
            }
            return false
        }

        private fun handleChangeLine(line: String, category: PullRequestCategory): Boolean {
            changePattern.matchMatcher(line) {
                val text = group("text").trim().replace("\"", "")
                val author = group("author").trim()
                if (author == "your_name_here") {
                    errors.add(ChangelogError("Author is not set", line))
                }
                checkLineText(text, line, LineType.CHANGE)
                currentChange = CodeChange(text, category, prLink, author).also { changes.add(it) }
                return true
            }
            return false
        }

        private fun handleChangeLineWithoutAuthor(line: String): Boolean {
            changePatternNoAuthor.matchMatcher(line) {
                val text = group("text").trim()
                errors.add(ChangelogError("Author is not set", line))
                checkLineText(text, line, LineType.CHANGE)
                return true
            }
            return false
        }

        private fun handleExtraInfoLine(line: String): Boolean {
            extraInfoPattern.matchMatcher(line) {
                val change = currentChange
                if (change == null) {
                    errors.add(ChangelogError("Extra info without a change", line))
                    return true
                }
                val text = group("text").trim()
                if (text == "Optional extra info.") {
                    errors.add(ChangelogError("Extra info is not filled out", line))
                }
                checkLineText(text, line, LineType.EXTRA_INFO)
                change.extraInfo.add(text)
                return true
            }
            return false
        }

        private fun checkLineText(text: String, line: String, type: LineType) {
            illegalStartPattern.matchMatcher(text) {
                errors.add(ChangelogError("Illegal start of ${type.displayName.lowercase()} line", line))
            }
            errors.addAll(checkWording(text, type))
        }
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
        if (changes.isEmpty()) return emptyList()

        val prefixText = prTitlePattern.matchMatcher(prTitle) { group("prefix") }
            ?: return listOf(PullRequestNameError("PR title does not match the expected format of 'Category: Title'"))

        val errors = mutableListOf<PullRequestNameError>()
        if (prefixText.contains("/") || prefixText.contains("&")) {
            errors.add(PullRequestNameError("PR categories shouldn't be separated by '/' or '&', use ' + ' instead"))
        }

        val prPrefixes = prefixText.split(Regex("[+&/]")).map { it.trim() }
        val expectedCategories = changes.map { it.category }.toSet()
        val expectedOptions = expectedCategories.joinToString { it.prPrefix }

        val foundCategories = parsePrefixCategories(prPrefixes, expectedOptions, errors)
        checkCategoryMismatch(foundCategories, expectedCategories, expectedOptions, errors)
        return errors
    }

    private fun parsePrefixCategories(
        prPrefixes: List<String>,
        expectedOptions: String,
        errors: MutableList<PullRequestNameError>,
    ): List<PullRequestCategory> = prPrefixes.mapNotNull { prefix ->
        PullRequestCategory.fromPrPrefix(prefix) ?: run {
            val validCategories = PullRequestCategory.validCategories().joinToString("`, `") { it }
            errors.add(
                PullRequestNameError(
                    "Unknown category: `$prefix`, valid categories are: `$validCategories`\n" +
                            "Expected categories based on your changes are: `$expectedOptions`"
                )
            )
            null
        }
    }

    private fun checkCategoryMismatch(
        foundCategories: List<PullRequestCategory>,
        expectedCategories: Set<PullRequestCategory>,
        expectedOptions: String,
        errors: MutableList<PullRequestNameError>,
    ) {
        foundCategories.forEach { category ->
            if (category !in expectedCategories) {
                errors.add(PullRequestNameError("PR has category '${category.prPrefix}' which is not in the changelog. Expected categories: $expectedOptions"))
            }
        }

        val fix = PullRequestCategory.FIX
        if (errors.isEmpty() && fix in expectedCategories && fix !in foundCategories) {
            errors.add(PullRequestNameError("PR title must include category 'Fix' if there are any fixes in the PR"))
        }
    }

    /**
     * To be used by Gradle tasks to generate specific output types.
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

        fun split(changeText: String) {
            if (!showWhereToSplit || (list.charactersSinceSplit() + changeText.length) <= 1950) return
            endDiff?.let { list.add(it) }
            list.add(Utils.LIST_SPLIT_TEXT)
            startDiff?.let { list.add(it) }
        }

        for (category in PullRequestCategory.entries) {
            if (type == TextOutputType.DISCORD_PUBLIC && category == PullRequestCategory.INTERNAL) continue

            val relevantChanges = changes.filter { it.category == category }.sortedBy { it.text.lowercase() }
            if (relevantChanges.isEmpty()) continue
            list.add("### " + category.changelogName)

            startDiff?.let { list.add(it) }
            for (text in relevantChanges.flatMap { formatChangeLines(it, category, type) }) {
                split(text)
                list.add(text)
            }
            endDiff?.let { list.add(it) }
        }

        if (type == TextOutputType.DISCORD_PUBLIC) {
            addDiscordPublicFooter(list, version, showWhereToSplit)
        }
        return list
    }

    private fun formatChangeLines(change: CodeChange, category: PullRequestCategory, type: TextOutputType): List<String> {
        val changePrefix = getPrefix(category, type)
        val changeText = "$changePrefix ${change.text} - ${change.author} ${type.prReference(change)}".trim()
        val extraInfoTexts = change.extraInfo.map { extraInfo ->
            val processedExtraInfo = if (type == TextOutputType.DISCORD_PUBLIC) extraInfo.stripLink() else extraInfo
            "${type.extraInfoPrefix} $processedExtraInfo"
        }
        return listOf(changeText) + extraInfoTexts
    }

    private fun addDiscordPublicFooter(list: MutableList<String>, version: ModVersion, showWhereToSplit: Boolean) {
        if (showWhereToSplit && list.charactersSinceSplit() > 1750) {
            list.add(Utils.LIST_SPLIT_TEXT)
        }

        val releaseLink = "$GITHUB_URL/releases/tag/${version.asTag}"
        list.add("For more details, see the [full changelog](<$releaseLink>)")
        list.add("Downloads:")
        val fabric2612Link = "$GITHUB_URL/releases/download/${version.asTag}/SkyHanni-${version.asTag}-mc26.1.jar"
        list.add("- [Fabric 26.1.2]($fabric2612Link)")
        val fabric262Link = "$GITHUB_URL/releases/download/${version.asTag}/SkyHanni-${version.asTag}-mc26.2.jar"
        list.add("- [Fabric 26.2]($fabric262Link)")
    }

    private fun String.stripLink() = replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")

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

enum class WhatToFetch(val url: String, val sort: (PullRequest) -> Date) {
    ALREADY_MERGED(
        "state=closed&sort=updated&direction=desc&per_page=100",
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
    var version = ModVersion(9, 2, 0)

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
//    val specificPreviousVersion: ModVersion? = ModVersion(4, 3, 0)

    var whatToFetch = WhatToFetch.ALREADY_MERGED

    @Suppress("KotlinConstantConditions")
    if (specificPreviousVersion != null) {
        whatToFetch = WhatToFetch.ALREADY_MERGED
        version = specificPreviousVersion
    }

    SkyHanniChangelogBuilder.generateChangelog(whatToFetch, version, specificPreviousVersion)
}

/*

smart AI prompt for formatting

Fix typos, grammar, and phrasing issues in this SkyHanni changelog. Keep formatting intact (URLs, dev names, structure). Feature names use uppercase first letters. Sublines without author describe the line above and don't need "Added"/"Fixed" prefix. Keep wording compact without losing information.

Output format for changes only:
old line
what changed
new line

*/
