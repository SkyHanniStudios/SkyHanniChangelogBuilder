package at.hannibal2.changelog

import java.net.URL
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

object Utils {

    // todo add way to set this from a file that is git ignored or from env variables
    private const val GITHUB_TOKEN = ""

    inline fun <T> Pattern.matchMatcher(text: String, consumer: Matcher.() -> T) =
        matcher(text).let { if (it.matches()) consumer(it) else null }

    fun getTextFromUrl(urlString: String): List<String> {
        val url = URL(urlString)
        val connection = url.openConnection()
        if (GITHUB_TOKEN.isNotEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
        }
        return connection.getInputStream().bufferedReader().useLines { lines ->
            lines.toList()
        }
    }

    fun Date.offsetMinute() = Date(time + 60 * 1000)

    const val LIST_SPLIT_TEXT = "--SPLIT--"

    fun List<String>.countCharacters() = sumOf { it.length } + size - 1

    fun List<String>.charactersSinceSplit(): Int {
        val splitIndex = lastIndexOf(LIST_SPLIT_TEXT)
        val sublist = if (splitIndex == -1) this else subList(splitIndex + 1, size)
        return sublist.countCharacters()
    }
}