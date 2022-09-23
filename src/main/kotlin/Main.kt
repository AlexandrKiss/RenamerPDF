import constants.*
import kotlinx.coroutines.*
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.PdfUtilities
import java.io.File
import java.nio.file.Files
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * RenamerPDF.jar\META-INF\services\javax.imageio.spi.ImageReaderSpi
 * org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi
 *
 * Баги:
 * 1. Не видит библиотеку JBIG2 - нужно прописывать путь вручную (инстуркция выше)
 * 2. Показывает иноформацию о логгере - бесит
 * 3. Вывод часов в общем времени
 */

private var runnable = true
private val fileMap = mutableMapOf<File, String>()
private val statusFileMap = mutableMapOf<File, FileStatus<String>>()

private val releaseType: ReleaseType = ReleaseType.RELEASE

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    startConsole(args)
    setPath { path ->
        launch {
            println(
                "[${getTime()}] [Сканування] Зачекайте будь ласка" +
                        "\n----------"
            )
            checkOrCreateDirectory(path)
            val measureTime = measureTimeMillis {
                val threadCount = min(path.listFiles()?.size ?: 1, 3)
                val fixedThreadPoolContext = newFixedThreadPoolContext(threadCount, "background")
                path.listFiles()?.processInParallel(dispatcher = fixedThreadPoolContext) { file ->
                    run(file)
                }
            }
            val time = Date(measureTime)
            val dateFormat = SimpleDateFormat("mm:ss")
            statuses(statusFileMap) { all, success, duplicate, error ->
                println(
                    "-----\n[${getTime()}] [Переiменування завершене] " +
                            "Всього оброблено ${(all).suffix("файл")}, " +
                            "з них успiшнi: ${success.suffix("файл")}, " +
                            "дублiкати: ${duplicate.suffix("файл")}, " +
                            "помилкиовi: ${error.suffix("файл")}, " +
                            "за ${dateFormat.format(time)} хв. (ср. ${mean(all, measureTime)} сек.)\n"
                )
            }
            readLine()
        }
    }
}

/** Main methods */
private fun run(file: File) {
    if (file.isFile) {
        convertPdfToPng(file) { line ->
            val newName = StringBuilder()

            val type = getType(line)
            val date = getDate(line)
            val region = getRegion(line)
            val contract = getContractNumber(line)

            when(region) {
                is Status.Success<*> -> newName.append("${region.data}_")
                is Status.Error<*> -> statusFileMap[file] = FileStatus.Error(file.name, region.error)
            }
            when(type) {
                is Status.Success<*> -> newName.append("${type.data.eng}_")
                is Status.Error<*> -> statusFileMap[file] = FileStatus.Error(file.name, type.error)
            }
            when(date) {
                is Status.Success<*> -> newName.append("${date.data}_")
                is Status.Error<*> -> statusFileMap[file] = FileStatus.Error(file.name, date.error)
            }
            when(contract) {
                is Status.Success<*> -> newName.append(contract.data)
                is Status.Error<*> -> statusFileMap[file] = FileStatus.Error(file.name, contract.error)
            }

            if (region is Status.Success<*> && type is Status.Success<*> &&
                date is Status.Success<*> && contract is Status.Success<*>) {
                if (!fileMap.containsValue(newName.toString())) {
                    statusFileMap[file] = FileStatus.Success(newName.toString())
                    printStatus(file.name, statusFileMap[file])
                } else {
                    statusFileMap[file] = FileStatus.Duplicate(newName.toString())
                    printStatus(file.name, statusFileMap[file])
                }

                fileMap[file] = newName.toString()
            } else {
                printStatus(file.name, statusFileMap[file])
                if (releaseType.isDebug) {
                    val error = (statusFileMap[file] as FileStatus.Error).error
                    println("\t[$error] ${error.msg}")
                }
            }
            if (releaseType.isRelease) saveFile(file, statusFileMap[file])
        }
    }
}

/** Data getters */
private fun getType(str: String): Status<AktType> {
    val endIndex = str.indexOf("Товариство")
    val day =
        try { getDateString(str.substring(0, endIndex).correctNumbers(), false)?.get(Calendar.DAY_OF_MONTH) }
        catch (_: StringIndexOutOfBoundsException) { null }
    val type: Status<AktType> = when {
        str.lowercase(Locale.getDefault()).contains("коригуючий") ->
            Status.Success(AktType.AKT_K)
        str.substring(0,3).lowercase(Locale.getDefault()) == AktType.AKT.ukr ->
            Status.Success(AktType.AKT)
        str.substring(0,13).lowercase(Locale.getDefault()) == AktType.AKT_K.ukr ->
            Status.Success(AktType.AKT_K)
        day == null -> Status.Error(AktType.NONE, ErrorType.TYPE_ERROR)
        day > 25 -> Status.Success(AktType.AKT)
        day in 24..25 -> Status.Success(AktType.AKT_K)
        else -> { Status.Error(AktType.NONE, ErrorType.TYPE_ERROR) }
    }
    return type
}

private fun getDate(str: String): Status<Int> {
    val endIndex = str.indexOf("Товариство")
    return try {
        val date = getDateString(str.substring(0, endIndex).correctNumbers(), false)
        if (date != null) {
            var month = (date.get(Calendar.MONTH) + 1).toString()
            if (month.length == 1)
                month = "0$month"
            val year = date.get(Calendar.YEAR)
            Status.Success("$year$month".toInt())
        } else {
            Status.Error(0, ErrorType.DATE_ERROR)
        }
    } catch (e: Exception) { Status.Error(0, ErrorType.DATE_ERROR) }
}

private fun getRegion(str: String): Status<String> {
    return getContract(str) { contractStr ->
        when(contractStr) {
            is Status.Success -> {
                return@getContract try {
                    Status.Success(contractStr.data.split('-')[0].takeLast(2).correctNumbers())
                } catch (_: Exception) { Status.Error(contractStr.data, ErrorType.REGION_ERROR) }
            }
            else -> { Status.Error(contractStr.data, ErrorType.REGION_ERROR) }
        }
    }
}
private fun getContractNumber(str: String): Status<String> {
    return getContract(str) { contractStr ->
        when(contractStr) {
            is Status.Success -> {
                return@getContract try {
                    Status.Success(contractStr.data.split('-')[1].split('/')[0].correctNumbers())
                } catch (_: Exception) { Status.Error(contractStr.data, ErrorType.CONTRACT_NUM_ERROR) }
            }
            else -> { Status.Error(contractStr.data, ErrorType.CONTRACT_NUM_ERROR) }
        }
    }
}

private fun getContract(str: String, callback: (Status<String>) -> Status<String>): Status<String> {
    val startIndex = str.lastIndexOf(string= "додоговорувід")
    val endIndex = str.indexOf(string = "Товариство", startIndex = startIndex, ignoreCase = true)
    return try {
        callback.invoke(Status.Success(str.substring(startIndex, endIndex)))
    } catch (_: Exception) {
        callback.invoke(Status.Error("", ErrorType.REGION_ERROR))
    }
}

/** PDF methods */
private fun convertPdfToPng(file: File, callback: (String) -> Unit) {
    val result = StringBuilder()
    try {
        val imageList = PdfUtilities.convertPdf2Png(file)

        val tempFile = imageList[0]
        val recognition =
            recognitionText(tempFile)?.replace(" ", "")?.replace("\n", "")
        result.append(recognition)
        tempFile.delete()
        callback.invoke(result.toString())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun recognitionText(file: File): String? {
    return try {
        val tesseract = Tesseract()
        val path = System.getenv("TESSDATA_PREFIX")
        val dataPath: String =
            if (path != null) "tessdata"
            else "."
        tesseract.setDatapath(dataPath)
        tesseract.setLanguage("ukr") //ukr

        tesseract.doOCR(file)
    } catch (e: Exception) { null }
}

/** Service methods */
private fun setPath(callback: (path: File) -> Unit) {
    var isRun = true
    while(isRun) {
        println("Встановiть шлях до папки з файлами (для виходу введiть exit):")
        val line = readLine()
        if (!line.isNullOrEmpty()) {
            if (line == "exit") {
                runnable = false
            } else {
                val file = File(line.replace("\\", "/"))
                if ((file.listFiles()?.size ?: 0) > 0) {
                    callback.invoke(file)
                    isRun = false
                } else {
                    print("[Якась фiгня] ")
                }
            }
        }
    }
}

private fun printStatus(oldName: String, status: FileStatus<String>?) {
    if (status != null) {
        val newName: String
        val statusName = when(status) {
            is FileStatus.Success -> {
                newName = status.data
                "OK"
            }
            is FileStatus.Duplicate -> {
                newName = "${status.data}_(${oldName.dropLast(4)})"
                "Дублiкат"
            }
            is FileStatus.Error -> {
                newName = status.data.dropLast(4)
                "Помилка"
            }
        }
        println("[${getTime()}] [$statusName] \"$oldName\" -> \"$newName.pdf\"")
    }
}

private fun saveFile(old: File, status: FileStatus<String>?) {
    if (status != null) {
        val new: File = when (status) {
            is FileStatus.Success ->
                File("${old.parent}\\success\\${status.data}.pdf")
            is FileStatus.Duplicate ->
                File("${old.parent}\\success\\${status.data}_(${old.name.dropLast(4)}).pdf")
            is FileStatus.Error ->
                File("${old.parent}\\error\\${status.data}")
        }
        val newPath = Files.copy(old.toPath(), new.toPath())
        if (Files.exists(newPath))
            old.delete()
    }
}

private fun checkOrCreateDirectory(path: File) {
    val successDir = File("$path\\success").toPath()
    val errorDir = File("$path\\error").toPath()
    if (Files.notExists(successDir))
        Files.createDirectory(successDir)
    if (Files.notExists(errorDir))
        Files.createDirectory(errorDir)
}

private fun statuses(statusFiles: Map<File, FileStatus<String>>,
                     result: (all: Int, success: Int, duplicate: Int, error: Int) -> Unit) {
    result.invoke(
        statusFiles.size,
        statusFiles.filterValues { it is FileStatus.Success }.size,
        statusFiles.filterValues { it is FileStatus.Duplicate }.size,
        statusFiles.filterValues { it is FileStatus.Error }.size
    )
}

private fun getDateString(str: String, isPrint: Boolean = true): Calendar? {
    if (isPrint) print("[${str.length}] ")
    return if (str.length < 100) {
        if (isPrint) println(str)
        val regex =
            Regex("(?<!\\d)(?:0?[1-9]|[12][0-9]|3[01]).(?:0?[1-9]|1[0-2]).(?:19[0-9][0-9]|20[0-9][0-9])(?!\\d)")
        val match = regex.find(str)
        val date = match?.value ?: str
        if (isPrint) println(date)
        val formatter = SimpleDateFormat("dd.MM.yyyy")
        return try {
            val calendar = Calendar.getInstance()
            calendar.time = formatter.parse(date)
            calendar
        } catch (_: ParseException) { null }
    } else null
}