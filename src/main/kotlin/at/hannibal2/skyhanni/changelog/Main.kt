package org.example

import at.hannibal2.skyhanni.changelog.GsonUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray

import java.net.URL
import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.system.exitProcess

enum class Category(val changeLogName: String, val prTitle: String) {
    NEW("New Features", "Feature"),
    IMPROVEMENT("Improvements", "Improvement"),
    FIX("Fixes", "Fix"),
    INTERNAL("Technical Details", "Backend"),
    REMOVED("Removed Features", "Removed Feature"),
    ;
}

//val allowedCategories = listOf("New Features", "Improvements", "Fixes", "Technical Details", "Removed Features")

val categoryPattern = "## Changelog (?<category>.*)".toPattern()
val changePattern = "\\+ (?<text>.*) - (?<author>.*)".toPattern()
val extraInfoPattern = " {4}\\* (?<text>.*)".toPattern()
val illegalStartPattern = "^[-=*+ ].*".toPattern()

fun getTextFromUrl(urlString: String): String {
    val url = URL(urlString)
    return url.openStream().use { it.bufferedReader().readText() }
}

enum class WhatToDo {
    NEXT_BETA, OPEN_PRS,
    ;
}

fun main() {
    val firstPr = 1168
    val hideWhenError = true
    val fullVersion = "0.24"
    val beta = 13

    val whatToDo = WhatToDo.NEXT_BETA

    @Suppress("KotlinConstantConditions")
    val url = when (whatToDo) {
        WhatToDo.NEXT_BETA -> "https://api.github.com/repos/hannibal002/SkyHanni/pulls?state=closed&sort=updated&direction=desc&per_page=50"
        WhatToDo.OPEN_PRS -> "https://api.github.com/repos/hannibal002/SkyHanni/pulls?state=open&sort=updated&direction=desc&per_page=30"
    }

    val data = getTextFromUrl(url)
    val prs = GsonUtils.readObject<List<PullRequest>>(data)
    readPrs(prs, firstPr, hideWhenError, whatToDo, fullVersion, beta)
}

fun readPrs(
    prs: List<PullRequest>,
    firstPr: Int,
    hideWhenError: Boolean,
    whatToDo: WhatToDo,
    fullVersion: String,
    beta: Int,
) {
    val allChanges = mutableListOf<Change>()
    val errors = mutableListOf<String>()
    var excluded = 0
    var done = 0
    var wrongPrName = 0
    // TODO find better solution for this sorting logic
    val filtered = when (whatToDo) {
        WhatToDo.NEXT_BETA -> prs.filter { it.mergedAt != null }
            .map { it to it.mergedAt }

        WhatToDo.OPEN_PRS -> prs
            .map { it to it.updatedAt }

    }
        .map { it.first to Long.MAX_VALUE - Instant.parse(it.second).toEpochMilli() }
        .sortedBy { it.second }
        .map { it.first }

    println("")
    for (pr in filtered) {
        val number = pr.number
        val prLink = pr.htmlUrl
        val body = pr.body
        val title = pr.title

        val description = body?.split(System.lineSeparator()) ?: emptyList()
        if (description.isNotEmpty()) {
            val last = description.last()
            if (last == "exclude_from_changelog") {
                println("")
                println("Excluded #$number ($prLink)")
                excluded++
                continue
            }
        }
        try {
            val newChanges = parseChanges(description, prLink)
            if (hasWrongPrName(prLink, title, newChanges)) {
                wrongPrName++
            }
            allChanges.addAll(newChanges)
            done++
        } catch (t: Throwable) {
            errors.add("Error in #$number ($prLink)\n${t.message}")
            if (hasWrongPrName(prLink, title, emptyList())) {
                wrongPrName++
            }
        }
        if (whatToDo == WhatToDo.NEXT_BETA) {
            if (number == firstPr) break
        }
    }
    println("")

    for (error in errors) {
        println(" ")
        println(error)
    }

    for (type in OutputType.entries) {
        print(allChanges, type, fullVersion, beta)
    }
    println("")
    if (excluded > 0) {
        println("Excluded $excluded PRs.")
    }
    val errorSize = errors.size
    if (errorSize > 0) {
        println("Found $errorSize PRs with errors!")
    }
    if (wrongPrName > 0) {
        println("Found $wrongPrName PRs with wrong names!")
    }
    println("Loaded $done PRs correctly.")
    if (errorSize > 0) {
        if (hideWhenError) {
            exitProcess(-1)
        }
    }
}

fun hasWrongPrName(prLink: String, title: String, newChanges: List<Change>): Boolean {
    val hasFix = newChanges.any { it.category == Category.FIX }
    for (category in Category.entries) {
        if (newChanges.any { it.category == category }) {
            var start = category.prTitle
            if (hasFix && category != Category.FIX) {
                start += " + Fix"
            }
            start += ": "
            val wrongName = !title.startsWith(start)
            if (wrongName) {
                println("wrong pr title!")
                println("found: '$title'")
                println("should start with $start")
                println("link: $prLink")
                println(" ")
            }
            return wrongName
        }
    }

    val prefix = "Wrong/broken Changelog: "
    if (!title.startsWith(prefix)) {
        println("wrong pr title!")
        println("found: '$title'")
        println("should start with $prefix")
        println("link: $prLink")
        println(" ")
        return true
    }

    return false
}

enum class OutputType {
    DISCORD_INTERNAL, GITHUB, DISCORD_PUBLIC,
}

private fun print(
    allChanges: MutableList<Change>,
    outputType: OutputType,
    fullVersion: String,
    beta: Int,
) {
    val extraInfoPrefix = when (outputType) {
        OutputType.DISCORD_PUBLIC -> " = "
        OutputType.GITHUB -> "   * "
        OutputType.DISCORD_INTERNAL -> " - "
    }
    val list = createPrint(outputType, allChanges, extraInfoPrefix, fullVersion, beta)
    val border = "================================================================================="
    println("")
    println("outputType ${outputType.name.lowercase()}:")
    val totalLength = list.sumOf { it.length }
    if (outputType != OutputType.GITHUB) {
        println("$totalLength/2000 characters used")
    }
    println(border)
    for (line in list) {
        println(line)
    }
    println(border)
}

private fun createPrint(
    outputType: OutputType,
    allChanges: MutableList<Change>,
    extraInfoPrefix: String,
    fullVersion: String,
    beta: Int,
): MutableList<String> {
    val list = mutableListOf<String>()
    list.add("## Version $fullVersion Beta $beta")

    for (category in Category.entries) {
        if (outputType == OutputType.DISCORD_PUBLIC && category == Category.INTERNAL) continue
        val changes = allChanges.filter { it.category == category }
        if (changes.isEmpty()) continue
        list.add("### " + category.changeLogName)
        if (outputType == OutputType.DISCORD_PUBLIC) {
            list.add("```diff")
        }
        for (change in changes) {
            val pr = when (outputType) {
                OutputType.DISCORD_PUBLIC -> ""
                OutputType.GITHUB -> " (${change.prLink})"
                OutputType.DISCORD_INTERNAL -> " [PR](<${change.prLink}>)"
            }
            val changePrefix = getChangePrefix(category, outputType)
            list.add("$changePrefix${change.text} - ${change.author}$pr")
            for (s in change.extraInfo) {
                list.add("$extraInfoPrefix$s")
            }
        }
        if (outputType == OutputType.DISCORD_PUBLIC) {
            list.add("```")
        }
    }
    if (outputType == OutputType.DISCORD_PUBLIC) {
        val root = "https://github.com/hannibal002/SkyHanni"
        val releaseLink = "$root/releases/tag/$fullVersion.Beta.$beta>"
        list.add("For a full changelog, including technical details, see the [GitHub release](<$releaseLink)")

        val downloadLink = "$root/releases/download/$fullVersion.Beta.$beta/SkyHanni-$fullVersion.Beta.$beta.jar"
        list.add("Download link: $downloadLink")
    }

    return list
}

fun getChangePrefix(category: Category, outputType: OutputType): String = when (outputType) {
    OutputType.DISCORD_INTERNAL -> "- "
    OutputType.GITHUB -> "+ "
    OutputType.DISCORD_PUBLIC -> when (category) {
        Category.NEW -> "+ "
        Category.IMPROVEMENT -> "+ "
        Category.FIX -> "~ "
        Category.REMOVED -> "- "
        Category.INTERNAL -> error("internal not in discord public")
    }
}

inline fun <T> Pattern.matchMatcher(text: String, consumer: Matcher.() -> T) =
    matcher(text).let { if (it.matches()) consumer(it) else null }

@Suppress("IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION")
fun parseChanges(
    description: List<String>,
    prLink: String,
): List<Change> {
    var currentCategory: Category? = null
    var currentChange: Change? = null
    val changes = mutableListOf<Change>()
    for (line in description) {
        if (line == "") {
            currentChange = null
            currentCategory = null
            continue
        }

        categoryPattern.matchMatcher(line) {
            val categoryName = group("category")
            currentCategory = getCategoryByLogName(categoryName) ?: error("unknown category: '$categoryName'")
            currentChange = null
            continue
        }

        val category = currentCategory ?: continue

        changePattern.matchMatcher(line) {
            val author = group("author")
            if (author == "your_name_here") {
                error("no author name")
            }
            val text = group("text")
            if (illegalStartPattern.matcher(text).matches()) {
                error("illegal start at change: '$text'")
            }
            currentChange = Change(text, category, prLink, author).also {
                changes.add(it)
            }
            continue
        }

        extraInfoPattern.matchMatcher(line) {
            val change = currentChange ?: error("Found extra info without change: '$line'")
            val text = group("text")
            if (illegalStartPattern.matcher(text).matches()) {
                error("illegal start at extra info: '$text'")
            }
            change.extraInfo.add(text)
            continue
        }
        error("found unexpected line: '$line'")
    }

    if (changes.isEmpty()) {
        error("no changes found")
    }

    return changes
}

fun getCategoryByLogName(name: String): Category? = Category.entries.find { it.changeLogName == name }

//class Category(val name: String)

class Change(val text: String, val category: Category, val prLink: String, val author: String) {
    val extraInfo = mutableListOf<String>()
}
