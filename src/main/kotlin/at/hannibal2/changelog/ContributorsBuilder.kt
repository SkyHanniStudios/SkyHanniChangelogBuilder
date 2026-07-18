package at.hannibal2.changelog

import at.hannibal2.changelog.Utils.matchMatcher
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * This script expects the raw content of the full version in question
 * from CHANGELOG.MD in clipboard and will print the alphabetically ordered list of all contributors.
 * This also supports entries with multiple contributors
 */

object ContributorsBuilder {

    private val categoryPattern = "### (?<category>.*)".toPattern()
    private val contributionPattern = "\\+ .*\\. - (?<contributorNames>.*) \\(.*\\)(?: \\+ .*)?".toPattern()

    fun processChangelog(text: String) {

        // Remove sub-categories and fix unnecessary break points
        val content = text.split("\n")
            .filter { !it.startsWith("    + ") && !it.startsWith("      ") }
            .joinToString("\n")
            .replace("\n  ", " ")
            .replace("  ", " ")

        val changes = mutableMapOf<PullRequestCategory, Int>()
        var currentCategory = PullRequestCategory.FEATURE

        val contributions = mutableMapOf<String, Int>()

        for (line in content.split("\n")) {
            categoryPattern.matchMatcher(line) {
                val categoryName = group("category")
                val newCategory = PullRequestCategory.fromChangelogName(categoryName)
                if (newCategory == null) {
                    println("unknown new category in line: $line")
                } else {
                    currentCategory = newCategory
                }
            }

            contributionPattern.matchMatcher(line) {
                val contributorNames = group("contributorNames")
                val names = contributorNames.split(Regex(", | & | \\+ |/| and "))
                for (name in names) {
                    contributions[name] = contributions.getOrDefault(name, 0) + 1
                }
                changes[currentCategory] = (changes[currentCategory] ?: 0) + 1
            }
        }
//    printNumbers(contributions)
        printFormattedChanges(changes)
        printFormattedContributors(contributions.keys)
    }

    private fun printFormattedChanges(changes: MutableMap<PullRequestCategory, Int>) {
        println("")
        print("In addition to the **${changes[PullRequestCategory.FEATURE]} new features**, ")
        print("there were **${changes[PullRequestCategory.IMPROVEMENT]} minor improvements**, ")
        print("**${changes[PullRequestCategory.FIX]} bug fixes**, ")
        print("and **${changes[PullRequestCategory.INTERNAL]} technical changes**. ")
        print("See the full changelog on GitHub for details.")
    }

    private fun printFormattedContributors(names: MutableSet<String>) {
        println("")
        val sorted = names.map { it to it.lowercase() }.sortedBy { it.second }.map { it.first }
        val line = StringBuilder()
        val total = StringBuilder()
        for (name in sorted) {
            line.append(name)
            if (line.length > 35) {
                total.append(line)
                total.append("\n")
                line.setLength(0)
            } else {
                line.append(", ")
            }
        }
        if (line.isNotEmpty()) {
            total.append(line.removeSuffix(", "))
        }
        println(" ")
        println(total)
    }

    private fun printNumbers(map: MutableMap<String, Int>) {
        println("")
        for ((name, amount) in map.entries.sortedBy { it.value }.reversed()) {
            println("$name ($amount)")
        }
    }
}

fun main() {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard?.getData(DataFlavor.stringFlavor)?.toString()
    if (clipboard == null) {
        println("clipboard empty")
        return
    }
    ContributorsBuilder.processChangelog(clipboard)
}
