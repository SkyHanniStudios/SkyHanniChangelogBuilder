package at.hannibal2.skyhanni.changelog

class Arguments {
    private val flags = mutableMapOf<String, Flag>()

    sealed interface Flag
    data class BooleanFlag(var set: Boolean) : Flag
    data class StringFlag(var value: String?) : Flag


    fun <T : Flag> registerFlag(name: String, flag: T): T {
        require(name.matches(Regex("^[a-z][a-z\\-]*$")) && !name.startsWith("no-"))
        flags[name] = flag
        return flag
    }

    fun string(name: String): StringFlag {
        return registerFlag(name, StringFlag(null))
    }

    fun bool(name: String, default: Boolean): BooleanFlag {
        return registerFlag(name, BooleanFlag(default))
    }

    fun parseFlags(args: Array<out String>) {
        var i = 0
        while (i in args.indices) {
            val flag = args[i]
            if (!flag.startsWith("--")) {
                printHelp("Named argument provided: $flag")
            }
            val flagName = flag.substring(2)
            if (flagName.startsWith("no-")) {
                val boolFlag = flags[flagName.substring(3)] as? BooleanFlag
                boolFlag ?: printHelp("Unknown flag $flag")
                boolFlag.set = false
            } else {
                val flagObject = flags[flagName]
                when (flagObject) {
                    is BooleanFlag -> flagObject.set = true
                    is StringFlag -> {
                        i++
                        if(i !in args.indices) { printHelp("No argument provided for flag $flag") }
                        flagObject.value = args[i]
                    }

                    null -> printHelp("Unknown flag $flag")
                }
            }

            i++
        }
    }

    fun printHelp(errorMessage: String?): Nothing {
        var help = "\n\n"
        if (errorMessage != null) {
            help += "ERROR: $errorMessage\n\n"
        }
        help += "Available flags:\n"
        flags.forEach { (name, flag) ->
            help += when (flag) {
                is BooleanFlag -> "    --$name, --no-$name\n"
                is StringFlag -> "    --$name <value>\n"
            }
        }
        error(help)
    }
}