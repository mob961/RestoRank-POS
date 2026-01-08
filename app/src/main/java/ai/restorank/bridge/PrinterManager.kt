package ai.restorank.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

data class Printer(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 9100,
    val isEnabled: Boolean = true
)

data class SyncResult(
    val created: Int,
    val skipped: Int,
    val message: String
)

class PrinterManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREF_NAME = "printer_config"
        const val KEY_PRINTERS = "printers"
        const val KEY_AUTO_PRINT = "auto_print_enabled"
        const val KEY_POLL_INTERVAL = "poll_interval"
        const val KEY_LAST_ORDER_ID = "last_order_id"
        const val KEY_RESTAURANT_ID = "restaurant_id"
        const val KEY_SERVER_URL = "server_url"
        const val DEFAULT_RESTAURANT_ID = "5a7f3275-5f63-4d8e-83dc-b544540e79c3"
        const val DEFAULT_SERVER_URL = "https://restorank.replit.app"
    }

    fun getRestaurantId(): String {
        return prefs.getString(KEY_RESTAURANT_ID, DEFAULT_RESTAURANT_ID) ?: DEFAULT_RESTAURANT_ID
    }

    fun setRestaurantId(id: String) {
        prefs.edit().putString(KEY_RESTAURANT_ID, id).apply()
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun syncPrintersFromServer(): Result<List<Printer>> {
        return try {
            val baseUrl = getServerUrl()
            val restaurantId = getRestaurantId()
            val url = java.net.URL("$baseUrl/api/restaurants/$restaurantId/printers")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val jsonArray = org.json.JSONArray(response)
                val printers = mutableListOf<Printer>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    printers.add(Printer(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "Printer"),
                        ip = obj.optString("networkIp", obj.optString("ipAddress", "")),
                        port = obj.optString("networkPort", obj.optString("port", "9100")).toIntOrNull() ?: 9100,
                        isEnabled = obj.optBoolean("enabled", obj.optBoolean("isEnabled", true))
                    ))
                }
                
                // Save synced printers locally
                savePrinters(printers)
                Result.success(printers)
            } else {
                conn.disconnect()
                Result.failure(Exception("Server returned ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun syncPrintersToServer(): Result<SyncResult> {
        return try {
            val baseUrl = getServerUrl()
            val restaurantId = getRestaurantId()
            val printers = getPrinters()
            
            if (printers.isEmpty()) {
                return Result.success(SyncResult(0, 0, "No printers to sync"))
            }
            
            val url = java.net.URL("$baseUrl/api/restaurants/$restaurantId/printers/sync-from-bridge")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            
            val printersArray = JSONArray()
            printers.forEach { printer ->
                val obj = JSONObject().apply {
                    put("id", printer.id)
                    put("name", printer.name)
                    put("ip", printer.ip)
                    put("port", printer.port)
                    put("isEnabled", printer.isEnabled)
                }
                printersArray.put(obj)
            }
            
            val requestBody = JSONObject().apply {
                put("printers", printersArray)
            }
            
            val writer = java.io.OutputStreamWriter(conn.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()
            
            if (conn.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                
                val responseObj = JSONObject(response)
                val created = responseObj.optInt("created", 0)
                val skipped = responseObj.optInt("skipped", 0)
                
                Result.success(SyncResult(created, skipped, "Synced successfully"))
            } else {
                val errorReader = java.io.BufferedReader(java.io.InputStreamReader(conn.errorStream ?: conn.inputStream))
                val errorResponse = errorReader.readText()
                errorReader.close()
                conn.disconnect()
                Result.failure(Exception("Server returned ${conn.responseCode}: $errorResponse"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getPrinters(): List<Printer> {
        val json = prefs.getString(KEY_PRINTERS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Printer(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    ip = obj.getString("ip"),
                    port = obj.optInt("port", 9100),
                    isEnabled = obj.optBoolean("isEnabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePrinters(printers: List<Printer>) {
        val array = JSONArray()
        printers.forEach { printer ->
            val obj = JSONObject().apply {
                put("id", printer.id)
                put("name", printer.name)
                put("ip", printer.ip)
                put("port", printer.port)
                put("isEnabled", printer.isEnabled)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRINTERS, array.toString()).apply()
    }

    fun addPrinter(name: String, ip: String, port: Int = 9100): Printer {
        val printers = getPrinters().toMutableList()
        val printer = Printer(
            id = System.currentTimeMillis().toString(),
            name = name,
            ip = ip,
            port = port,
            isEnabled = true
        )
        printers.add(printer)
        savePrinters(printers)
        return printer
    }

    fun removePrinter(id: String) {
        val printers = getPrinters().filter { it.id != id }
        savePrinters(printers)
    }

    fun updatePrinter(printer: Printer) {
        val printers = getPrinters().map { 
            if (it.id == printer.id) printer else it 
        }
        savePrinters(printers)
    }

    fun togglePrinter(id: String, enabled: Boolean) {
        val printers = getPrinters().map { 
            if (it.id == id) it.copy(isEnabled = enabled) else it 
        }
        savePrinters(printers)
    }

    fun testConnection(ip: String, port: Int, timeoutMs: Int = 5000): Result<Boolean> {
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                socket.close()
                return Result.success(true)
            } catch (e: Exception) {
                lastException = e
                if (attempt < 2) {
                    Thread.sleep(500) // Wait before retry
                }
            }
        }
        return Result.failure(lastException ?: Exception("Connection failed"))
    }

    fun isAutoPrintEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PRINT, true)
    }

    fun setAutoPrintEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PRINT, enabled).apply()
    }

    fun getPollInterval(): Long {
        return prefs.getLong(KEY_POLL_INTERVAL, 5000) // 5 seconds default
    }

    fun setPollInterval(intervalMs: Long) {
        prefs.edit().putLong(KEY_POLL_INTERVAL, intervalMs).apply()
    }

    fun getLastOrderId(): String? {
        return prefs.getString(KEY_LAST_ORDER_ID, null)
    }

    fun setLastOrderId(orderId: String) {
        prefs.edit().putString(KEY_LAST_ORDER_ID, orderId).apply()
    }
}
