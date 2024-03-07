package org.example

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray

import java.net.URL
import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.system.exitProcess

val allowedCategories = listOf("New Features", "Improvements", "Fixes", "Technical Details", "Removed Features")

val categoryPattern = "## Changelog (?<category>.*)".toPattern()
val changePattern = "\\+ (?<text>.*) - (?<author>.*)".toPattern()
val extraInfoPattern = " {4}\\* (?<text>.*)".toPattern()
val illegalStartPattern = "^[-=*+ ].*".toPattern()

fun getTextFromUrl(urlString: String): List<String> {
    val url = URL(urlString)
    val connection = url.openConnection()
    val inputStream = connection.getInputStream()
    val text = mutableListOf<String>()

    inputStream.bufferedReader().useLines { lines ->
        lines.forEach {
            text.add(it)
        }
    }

    return text
}

enum class WhatToDo {
    NEXT_BETA, OPEN_PRS,
    ;
}

fun main() {
    val firstPr = 1072
    val hideWhenError = true
    val title = "Version 0.24 Beta 7"

    val whatToDo = WhatToDo.NEXT_BETA

    println("")

    @Suppress("KotlinConstantConditions")
    val url = when (whatToDo) {
        WhatToDo.NEXT_BETA -> "https://api.github.com/repos/hannibal002/SkyHanni/pulls?state=closed&sort=updated&direction=desc&per_page=50"
        WhatToDo.OPEN_PRS -> "https://api.github.com/repos/hannibal002/SkyHanni/pulls?state=open&sort=updated&direction=desc&per_page=20"
    }

    val data = getTextFromUrl(url).joinToString("")
    val gson = GsonBuilder().create()
    val fromJson = gson.fromJson(data, JsonArray::class.java)
    val prs = fromJson.map { gson.fromJson(it, PullRequest::class.java) }
    readPrs(prs, firstPr, hideWhenError, title, whatToDo)
}

fun readPrs(prs: List<PullRequest>, firstPr: Int, hideWhenError: Boolean, title: String, whatToDo: WhatToDo) {
    val categories = mutableListOf<Category>()
    val allChanges = mutableListOf<Change>()
    var errors = 0
    var done = 0
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
    for (pr in filtered) {
        val number = pr.number
        val prLink = pr.htmlUrl
        val body = pr.body

        val description = body?.split(System.lineSeparator()) ?: emptyList()
        try {
            allChanges.addAll(parseChanges(description, prLink, categories))
            done++
        } catch (t: Throwable) {
            println("")
            println("Error in #$number ($prLink)")
            println(t.message)
            errors++
        }
        if (whatToDo == WhatToDo.NEXT_BETA) {
            if (number == firstPr) break
        }
    }
    println("")

    for (type in OutputType.entries) {
        print(categories, allChanges, type, title)
    }
    println("")
    println("Found $errors PRs with errors")
    println("Loaded $done PRs correctly")
    if (errors > 0) {
        if (hideWhenError) {
            exitProcess(-1)
        }
    }
}

enum class OutputType {
    DISCORD_INTERNAL, GITHUB, DISCORD_PUBLIC,
}

private fun print(
    categories: MutableList<Category>,
    allChanges: MutableList<Change>,
    outputType: OutputType,
    title: String,
) {
    val extraInfoPrefix = when (outputType) {
        OutputType.DISCORD_PUBLIC -> " = "
        OutputType.GITHUB -> "   * "
        OutputType.DISCORD_INTERNAL -> " - "
    }
    val border = "================================================================================="
    println("")
    println("outputType ${outputType.name.lowercase()}:")
    println(border)
    println("## $title")
    for (category in allowedCategories.map { getCategory(categories, it) }) {
        if (outputType == OutputType.DISCORD_PUBLIC && category.name == "Technical Details") continue
        val changes = allChanges.filter { it.category == category }
        if (changes.isEmpty()) continue
        println("### " + category.name)
        if (outputType == OutputType.DISCORD_PUBLIC) {
            println("```diff")
        }
        for (change in changes) {
            val pr = when (outputType) {
                OutputType.DISCORD_PUBLIC -> ""
                OutputType.GITHUB -> " (${change.prLink})"
                OutputType.DISCORD_INTERNAL -> " [PR](<${change.prLink}>)"
            }
            val changePrefix = getChangePrefix(category.name, outputType)
            println("$changePrefix${change.text} - ${change.author}$pr")
            for (s in change.extraInfo) {
                println("$extraInfoPrefix$s")
            }
        }
        if (outputType == OutputType.DISCORD_PUBLIC) {
            println("```")
        }
    }
    println(border)
}

fun getChangePrefix(name: String, outputType: OutputType): String = when (outputType) {
    OutputType.DISCORD_INTERNAL -> "- "
    OutputType.GITHUB -> "+ "
    OutputType.DISCORD_PUBLIC -> when (name) {
        "New Features" -> "+ "
        "Improvements" -> "+ "
        "Fixes" -> "~ "
        "Removed Features" -> "- "
        else -> error("impossible change prefix")
    }
}

inline fun <T> Pattern.matchMatcher(text: String, consumer: Matcher.() -> T) =
    matcher(text).let { if (it.matches()) consumer(it) else null }

@Suppress("IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION")
fun parseChanges(
    description: List<String>,
    prLink: String,
    categories: MutableList<Category>,
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
            if (categoryName !in allowedCategories) {
                error("unknown category: '$categoryName'")
            }
            currentCategory = getCategory(categories, categoryName)
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

fun getCategory(categories: MutableList<Category>, newName: String): Category {
    for (category in categories) {
        if (category.name == newName) {
            return category
        }
    }
    val category = Category(newName)
    categories.add(category)
    return category
}

class Category(val name: String)

class Change(val text: String, val category: Category, val prLink: String, val author: String) {
    val extraInfo = mutableListOf<String>()
}
