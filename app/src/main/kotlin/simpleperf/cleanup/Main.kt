package simpleperf.cleanup

import java.io.File

fun main(vararg args: String) {
  val sourcePath = args[0]

  val beforeExtension = sourcePath.substringBeforeLast('.')
  // delimiter not found
  val destinationPath = if (beforeExtension == sourcePath) {
    "$sourcePath-fixed"
  } else {
    val afterExtension = sourcePath.substringAfterLast('.')
    "$beforeExtension-fixed.$afterExtension"
  }

  val source = File(sourcePath)
  val destination = File(destinationPath)
  SimpleperfTraceParser.fixDetachedMainSamples(source, destination)
}