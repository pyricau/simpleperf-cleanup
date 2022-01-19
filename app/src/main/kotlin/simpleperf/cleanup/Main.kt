package simpleperf.cleanup

import java.io.File

fun main(vararg args: String) {
  val file = File(args[0])
  SimpleperfTraceParser().parseTraceFile(file)
}