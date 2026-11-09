package org.matrix.chromext.utils

import android.os.Handler
import android.util.Base64
import java.io.IOException
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.action.ActionState
import org.matrix.chromext.script.CentralBridge
import org.matrix.chromext.script.Local

class XMLHttpRequest(
    id: String,
    request: JSONObject,
    uuid: Double,
    currentTab: Any?,
    frameId: String?,
    /** When set, a successful GET is cached under this key in Download/ChromeXT/resources. */
    cacheKey: String? = null,
) {
  val currentTab = currentTab
  val frameId = frameId
  val request = request
  val response = JSONObject(mapOf("id" to id, "uuid" to uuid))

  var connection: HttpURLConnection? = null
  var cookies: List<HttpCookie>

  private var cacheTarget = cacheKey
  private val cacheBuffer = java.io.ByteArrayOutputStream()
  private var cacheBytes = 0
  private var status = 0
  private var mime = ""

  val anonymous = request.optBoolean("anonymous")
  val binary = request.optBoolean("binary")
  val buffersize = request.optInt("buffersize", 8)
  val cookie = request.optJSONArray("cookie")
  val headers = request.optJSONObject("headers")
  val method = request.optString("method")
  val nocache = request.optBoolean("nocache")
  val timeout = request.optInt("timeout")
  val responseType = request.optString("responseType")
  val url = URL(request.optString("url"))
  val uri = url.toURI()

  init {
    if (cookie != null && !anonymous) {
      for (i in 0 until cookie.length()) {
        runCatching {
          HttpCookie.parse(cookie!!.getString(i)).forEach { Chrome.cookieStore.add(uri, it) }
        }
      }
    }
    cookies = Chrome.cookieStore.get(uri)
  }

  fun abort() {
    response("abort", JSONObject().put("abort", "Abort on request"))
  }

  fun send() {
    connection = url.openConnection() as HttpURLConnection
    with(connection!!) {
      setRequestMethod(method)
      setInstanceFollowRedirects(request.optString("redirect") != "manual")
      headers?.keys()?.forEach { setRequestProperty(it, headers.optString(it)) }
      setUseCaches(!nocache)
      setConnectTimeout(timeout)

      if (!anonymous && cookies.size > 0)
          setRequestProperty("Cookie", cookies.map { it.toString() }.joinToString("; "))

      if (request.has("user")) {
        val user = request.optString("user")
        val password = request.optString("password")
        val encoded = Base64.encodeToString(("${user}:${password}").toByteArray(), Base64.DEFAULT)
        setRequestProperty("Authorization", "Basic " + encoded)
      }

      var data = JSONObject()
      runCatching {
        if (method != "GET" && request.has("data")) {
          val input = request.optString("data")
          val bytes =
              if (binary) {
                Base64.decode(input, Base64.DEFAULT)
              } else {
                input.toByteArray()
              }
          setFixedLengthStreamingMode(bytes.size)
          outputStream.write(bytes)
        }

        data.put("status", responseCode)
        status = responseCode
        mime = contentType ?: ""
        data.put("statusText", responseMessage)
        val headers = headerFields.filter { it.key != null }.mapValues { JSONArray(it.value) }
        data.put("headers", JSONObject(headers))
        val binary =
            responseType !in listOf("", "text", "document", "json") ||
                contentEncoding != null ||
                (contentType != null &&
                    contentType.contains("charset") &&
                    !contentType.contains("utf-8"))
        data.put("binary", binary)

        val buffer = ByteArray(buffersize * DEFAULT_BUFFER_SIZE)
        while (true) {
          var bytes = 0
          while (buffer.size > bytes) {
            val b = inputStream.read(buffer, bytes, buffer.size - bytes)
            if (b == 0 || b == -1) break
            bytes += b
          }
          if (bytes == 0) break
          if (cacheTarget != null) {
            if (cacheBytes + bytes <= MAX_CACHE_BYTES) {
              cacheBuffer.write(buffer, 0, bytes)
              cacheBytes += bytes
            } else {
              // Too large to mirror; stop accumulating.
              cacheTarget = null
              cacheBuffer.reset()
            }
          }
          val chunk =
              if (binary) {
                Base64.encodeToString(buffer, 0, bytes, Base64.DEFAULT)
              } else {
                String(buffer, 0, bytes)
              }
          data.put("chunk", chunk)
          data.put("bytes", bytes)
          response("progress", data, false)
          data.remove("headers")
        }
        response("load", data)
        val key = cacheTarget
        if (key != null && status in 200..299 && cacheBytes > 0) {
          val payload = cacheBuffer.toByteArray()
          Chrome.IO.submit { CentralBridge.putResource(key, payload, mime) }
        }
      }
          .onFailure {
            if (it is IOException) {
              data.put("type", it::class.java.name)
              data.put("message", it.message)
              data.put("stack", it.stackTraceToString())
              errorStream?.bufferedReader()?.use { it.readText() }?.let { data.put("error", it) }
              if (it is SocketTimeoutException) {
                response("timeout", data.put("bytesTransferred", it.bytesTransferred))
              } else {
                response("error", data)
              }
            }
          }
    }
    if (!anonymous && connection != null) {
      Chrome.storeCoookies(this, connection!!.headerFields)
    }
  }

  fun response(
      type: String,
      data: JSONObject = JSONObject(),
      disconnect: Boolean = true,
  ) {
    response.put("type", type)
    response.put("data", data)
    val code = "Symbol.${Local.name}.unlock(${Local.key}).post('xmlhttpRequest', ${response});"
    Handler(Chrome.getContext().mainLooper).post {
      Chrome.evaluateJavascript(listOf(code), currentTab, frameId)
    }
    if (disconnect) {
      ActionState.xmlhttpRequests.remove(response.getDouble("uuid"))
      connection?.disconnect()
    }
  }
}

// The provider receives cached responses through a Bundle, which must stay well under the 1 MB
// Binder transaction limit.
private const val MAX_CACHE_BYTES = 512 * 1024
