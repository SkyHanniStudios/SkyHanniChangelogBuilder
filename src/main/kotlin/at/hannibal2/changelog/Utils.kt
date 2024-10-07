package at.hannibal2.changelog

import java.net.URL
import java.util.regex.Matcher
import java.util.regex.Pattern

object Utils {

    inline fun <T> Pattern.matchMatcher(text: String, consumer: Matcher.() -> T) =
        matcher(text).let { if (it.matches()) consumer(it) else null }

    fun getTextFromUrl(urlString: String): List<String> =
        URL(urlString).openStream().bufferedReader().useLines { lines ->
            lines.toList()
        }

}