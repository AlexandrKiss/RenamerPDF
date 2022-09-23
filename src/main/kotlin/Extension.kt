import constants.Status
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// this code for launch .jar file
fun startConsole(args: Array<String>) {
    if (args.isEmpty()) {
        val file = File(Status::class.java.protectionDomain.codeSource.location.path).absolutePath
        Runtime.getRuntime().exec("cmd.exe /c start java -jar $file cmd")
    }
}

fun String.correctNumbers(): String {
    val pattern = Regex("\\D+")
    return replace("з","3")
        .replace("З","3")
        .replace("о","0")
        .replace("О","0")
        .replace("і","1")
        .replace("б","6")
        .replace("в","8")
        .replace("В","8")
        .replace(pattern, "")
}

suspend fun <T, R> Array<T>.processInParallel(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    processBlock: suspend (v: T) -> R,
): List<R> = coroutineScope { // or supervisorScope
    map {
        async(dispatcher) { processBlock(it) }
    }.awaitAll()
}

fun Int.suffix(word: String): String {
    val lastChar = toString().last()
    val suffix = when {
        this in 5..20 -> "iв"
        this == 1 || lastChar == '1' -> ""
        this in 2..4 || lastChar == '2' || lastChar == '3' || lastChar == '4' -> "а"
        else -> "iв"
    }
    return "$this $word$suffix"
}

fun mean(all: Int, timeMillis: Long): Int {
    val second = timeMillis.toDouble() / 1000
    return (second / all).roundToInt()
}

fun getTime(): String {
    val date = Calendar.getInstance().time
    val dateFormat = SimpleDateFormat("HH:mm:ss")
    return dateFormat.format(date)
}