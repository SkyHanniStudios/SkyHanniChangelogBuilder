package at.hannibal2.skyhanni.changelog

object CheckSpecificPR {
    @JvmStatic
    fun main(args: Array<String>) {
        val arguments = Arguments()
        val prNumArg = arguments.string("pr-num")
        arguments.parseFlags(args)
        val prNum = prNumArg.value?.toIntOrNull() ?: arguments.printHelp("--pr-num is required")
        println("Investigating PR: $prNum")
        // TODO: actually test specific PR
    }
}