package org.matrix.chromext.utils

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.chromext.Chrome
import org.matrix.chromext.script.CentralBridge

/**
 * Rolling on-device log shared by every process the module runs in.
 *
 * The manager process writes the file directly. A hooked app process cannot reach the module's
 * private directory, so it ships its lines through the provider (`appendLog`), which resolves the
 * caller package from the Binder UID. Lines are queued and flushed in batches so a page load never
 * blocks the WebView thread on Binder IPC, and the file rotates at [MAX_BYTES].
 */
object ModuleLog {
  private const val MODULE_PACKAGE = "org.matrix.chromext"
  private const val LOG_DIR = "logs"
  private const val LOG_NAME = "chromext.log"
  private const val MAX_BYTES = 512 * 1024L
  private const val MAX_QUEUE = 400
  private const val MAX_BATCH = 8 * 1024

  /** Longest single line kept; stack traces and huge URLs are truncated. */
  const val MAX_LINE = 600

  /** A stored line also carries the timestamp and the process tag. */
  private const val MAX_STORED_LINE = MAX_LINE + 200

  @Volatile private var context: Context? = null
  @Volatile private var moduleProcess = false

  private val queue = ArrayDeque<String>()
  private val scheduled = AtomicBoolean(false)
  private val fileLock = Any()

  private val formatter =
      object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
      }

  /** Called from every process that wants its `Log` lines to reach the shared file. */
  fun attach(ctx: Context) {
    val app = ctx.applicationContext ?: ctx
    context = app
    moduleProcess = app.packageName == MODULE_PACKAGE
  }

  fun file(context: Context): File = File(File(context.filesDir, LOG_DIR), LOG_NAME)

  /**
   * Queues one line. Returns immediately; the actual write (or provider upload) happens on
   * [Chrome.IO]. A process that never called [attach] silently drops the line.
   */
  fun append(message: String) {
    val ctx = context ?: return
    if (message.isBlank()) return
    val tag = "${ctx.packageName ?: "?"}#${android.os.Process.myPid()}"
    val line = formatLine(System.currentTimeMillis(), tag, message)
    synchronized(queue) {
      if (queue.size >= MAX_QUEUE) queue.removeFirst()
      queue.addLast(line)
    }
    if (!scheduled.compareAndSet(false, true)) return
    runCatching { Chrome.IO.submit { flush() } }
  }

  private fun flush() {
    val batch =
        synchronized(queue) {
          val copy = queue.toList()
          queue.clear()
          copy
        }
    scheduled.set(false)
    if (batch.isEmpty()) return
    val ctx = context ?: return
    if (moduleProcess) {
      write(ctx, batch)
      return
    }
    // Keep every Binder transaction far below the ~1 MB limit.
    val chunk = StringBuilder()
    fun send() {
      if (chunk.isEmpty()) return
      CentralBridge.appendLog(chunk.toString())
      chunk.setLength(0)
    }
    batch.forEach { line ->
      if (chunk.isNotEmpty() && chunk.length + line.length > MAX_BATCH) send()
      if (chunk.isNotEmpty()) chunk.append('\n')
      chunk.append(line)
    }
    send()
  }

  /** Appends already formatted lines to the log file, rotating it first when it grew too large. */
  fun write(context: Context, lines: List<String>) {
    if (lines.isEmpty()) return
    val clean = lines.filter { it.isNotBlank() }.map { flatten(it, MAX_STORED_LINE) }
    if (clean.isEmpty()) return
    synchronized(fileLock) {
      runCatching {
        val dir = File(context.filesDir, LOG_DIR)
        dir.mkdirs()
        val file = File(dir, LOG_NAME)
        if (file.length() > MAX_BYTES) rotate(file)
        file.appendText(clean.joinToString("\n", postfix = "\n"))
      }
    }
  }

  private fun rotate(file: File) {
    val previous = File(file.parentFile, "$LOG_NAME.1")
    runCatching { previous.delete() }
    if (!file.renameTo(previous)) runCatching { file.delete() }
  }

  /** Returns at most [maxBytes] from the end of the log, or an empty string. */
  fun read(context: Context, maxBytes: Int = 64 * 1024): String {
    val file = file(context)
    if (!file.isFile) return ""
    return runCatching {
          val length = file.length()
          val start = (length - maxBytes).coerceAtLeast(0L)
          RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
          }
        }
        .getOrDefault("")
  }

  fun clear(context: Context) {
    synchronized(fileLock) {
      val file = file(context)
      runCatching { file.delete() }
      runCatching { File(file.parentFile, "$LOG_NAME.1").delete() }
    }
  }

  /** Collapses a multi-line message into one log line and bounds its length. */
  fun sanitizeLine(message: String): String = flatten(message, MAX_LINE)

  private fun flatten(message: String, limit: Int): String {
    val flat = message.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    return if (flat.length <= limit) flat else flat.take(limit) + " ..."
  }

  fun formatLine(timestamp: Long, tag: String, message: String): String {
    val time = formatter.get()?.format(Date(timestamp)) ?: timestamp.toString()
    return "$time  $tag  ${sanitizeLine(message)}"
  }
}
