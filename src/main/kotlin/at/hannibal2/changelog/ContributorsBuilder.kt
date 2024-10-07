package org.example

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * This script expects the raw content of the full version in question
 * from CHANGELOG.MD in clipboard and will print the alphabetically orderd list of all contributrs.
 * This also supports entries with multiple contributors
 */

fun main() {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard?.getData(DataFlavor.stringFlavor)?.toString()
    if (clipboard == null) {
        println("clipboard empty")
        return
    }
    // remove sub categories
    var s = clipboard.split("\n").toList().filter { !it.startsWith("    + ") && !it.startsWith("      ") }
        .joinToString("\n")

    // fix unecessary break points
    s = s.replace("\n  ", " ").replace("  ", " ")

    val pattern = "\\+ .*\\. - (?<name>.*) \\(.*\\)".toPattern()

    val map = mutableMapOf<String, Int>()

    for (line in s.split("\n")) {
        val matcher = pattern.matcher(line)
        if (matcher.matches()) {
            val name = matcher.group("name")
            val names = mutableListOf<String>()
            if (name.contains(", ")) {
                names.addAll(name.split(", "))
            } else if (name.contains(" & ")) {
                names.addAll(name.split(" & "))
            } else if (name.contains(" + ")) {
                names.addAll(name.split(" + "))
            } else if (name.contains("/")) {
                names.addAll(name.split("/"))
            } else {
                names.add(name)
            }
            if (name == "Alexia Alexia Luna") {
                println("line: $line")
            }

            for (oneName in names) {
                val old = map[oneName] ?: 0
                map[oneName] = old + 1
            }
        } else {
            if (line.startsWith("##")) continue
            if (line == "") continue
        }
    }
//    printNumbers(map)
    printFormatted(map.keys)
}

fun printFormatted(names: MutableSet<String>) {
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
    println(" ")
    println(total)
}

private fun printNumbers(map: MutableMap<String, Int>) {
    println("")
    for ((name, amount) in map.entries.sortedBy { it.value }.reversed()) {
        println("$name ($amount)")
    }
}
