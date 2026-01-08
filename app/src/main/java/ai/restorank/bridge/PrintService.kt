package ai.restorank.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class PrintService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var printerManager: PrinterManager
    private lateinit var voiceManager: VoiceManager
    private var isPolling = false
    private val processedOrderIds = mutableSetOf<String>()
    private val processedBillIds = mutableSetOf<String>()
    private val processedTestPrintIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "PrintService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "print_service_channel"
        
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        printerManager = PrinterManager(this)
        voiceManager = VoiceManager(this)
        createNotificationChannel()
        isRunning = true
        Log.d(TAG, "PrintService created (KITCHEN mode with voice)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (!isPolling && printerManager.isAutoPrintEnabled()) {
            startPolling()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        isRunning = false
        voiceManager.shutdown()
        executor.shutdown()
        Log.d(TAG, "PrintService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Print Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for automatic order printing"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceStatus = if (VoiceManager.voiceEnabled) "Voice ON" else "Voice OFF"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RestoRank Kitchen")
            .setContentText("KOT Print + $voiceStatus | Monitoring orders...")
            .setSmallIcon(R.drawable.ic_printer)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startPolling() {
        isPolling = true
        
        // Sync printers from server on start
        executor.execute {
            printerManager.syncPrintersFromServer()
        }
        
        pollForOrders()
    }

    private var syncCounter = 0
    
    private fun pollForOrders() {
        if (!isPolling) return
        
        executor.execute {
            try {
                // Sync printers from server every 10 polls (~50 seconds)
                syncCounter++
                if (syncCounter >= 10) {
                    syncCounter = 0
                    printerManager.syncPrintersFromServer()
                }
                
                // Poll for all print jobs (unified endpoint for KOT and bills)
                val printJobs = fetchPrintJobs()
                printJobs.forEach { job ->
                    val jobId = job.optString("id")
                    val jobType = job.optString("type", "kot")
                    
                    if (jobId.isNotEmpty()) {
                        if (jobType == "bill" && !processedBillIds.contains(jobId)) {
                            processedBillIds.add(jobId)
                            printBillFromJob(job)
                            
                            if (processedBillIds.size > 100) {
                                processedBillIds.remove(processedBillIds.first())
                            }
                        } else if (jobType == "kot" && !processedOrderIds.contains(jobId)) {
                            processedOrderIds.add(jobId)
                            printKotFromJob(job)
                            printerManager.setLastOrderId(job.optString("orderId"))
                            
                            if (processedOrderIds.size > 100) {
                                processedOrderIds.remove(processedOrderIds.first())
                            }
                        }
                    }
                }

                // Poll for test print requests
                val testPrints = fetchPendingTestPrints()
                testPrints.forEach { testPrint ->
                    val testPrintId = testPrint.optString("id")
                    if (testPrintId.isNotEmpty() && !processedTestPrintIds.contains(testPrintId)) {
                        processedTestPrintIds.add(testPrintId)
                        executeTestPrint(testPrint)
                        
                        if (processedTestPrintIds.size > 50) {
                            processedTestPrintIds.remove(processedTestPrintIds.first())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling orders: ${e.message}")
            }

            // Schedule next poll
            handler.postDelayed({
                if (isPolling && printerManager.isAutoPrintEnabled()) {
                    pollForOrders()
                }
            }, printerManager.getPollInterval())
        }
    }

    private fun fetchPrintJobs(): List<JSONObject> {
        val baseUrl = printerManager.getServerUrl()
        val restaurantId = printerManager.getRestaurantId()
        
        return try {
            val url = URL("$baseUrl/api/restaurants/$restaurantId/print-jobs")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                
                val array = JSONArray(response)
                (0 until array.length()).map { array.getJSONObject(it) }
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch print jobs: ${e.message}")
            emptyList()
        }
    }

    private fun acknowledgePrintJob(jobId: String) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/print-jobs/$jobId/acknowledge")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write("{}")
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Print job $jobId acknowledged")
            } else {
                Log.e(TAG, "Failed to acknowledge print job: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acknowledge print job: ${e.message}")
        }
    }

    private fun failPrintJob(jobId: String, errorMessage: String) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/print-jobs/$jobId/fail")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write("{\"error\": \"$errorMessage\"}")
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Print job $jobId marked as failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark print job as failed: ${e.message}")
        }
    }

    private fun printKotFromJob(job: JSONObject) {
        val jobId = job.optString("id")
        val printerIp = job.optString("printerIp", "")
        val printerPortStr = job.optString("printerPort", "9100")
        val printerPort = printerPortStr.toIntOrNull() ?: 9100
        
        if (printerIp.isEmpty()) {
            Log.e(TAG, "KOT print job $jobId has no printer IP")
            failPrintJob(jobId, "No printer IP configured")
            return
        }
        
        Log.d(TAG, "Printing KOT job $jobId to $printerIp:$printerPort")
        
        // Voice announcement
        handler.post {
            voiceManager.speakKotOrder(job, repeatOnce = true)
        }
        
        try {
            val receipt = formatKotFromJob(job)
            printToThermal(printerIp, printerPort, receipt)
            Log.d(TAG, "KOT printed for job $jobId")
            acknowledgePrintJob(jobId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print KOT: ${e.message}")
            failPrintJob(jobId, e.message ?: "Print failed")
        }
    }

    private fun printBillFromJob(job: JSONObject) {
        val jobId = job.optString("id")
        val printerIp = job.optString("printerIp", "")
        val printerPortStr = job.optString("printerPort", "9100")
        val printerPort = printerPortStr.toIntOrNull() ?: 9100
        
        if (printerIp.isEmpty()) {
            Log.e(TAG, "Bill print job $jobId has no printer IP")
            failPrintJob(jobId, "No printer IP configured")
            return
        }
        
        Log.d(TAG, "Printing Bill job $jobId to $printerIp:$printerPort")
        
        try {
            val receipt = formatBillFromJob(job)
            printToThermal(printerIp, printerPort, receipt)
            Log.d(TAG, "Bill printed for job $jobId")
            acknowledgePrintJob(jobId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print bill: ${e.message}")
            failPrintJob(jobId, e.message ?: "Print failed")
        }
    }

    private fun formatKotFromJob(job: JSONObject): ByteArray {
        val orderId = job.optString("orderId", "N/A").takeLast(6)
        val orderType = job.optString("orderType", "Dine-in")
        val tableName = job.optString("tableName", "")
        val items = job.optJSONArray("items") ?: JSONArray()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        sb.append("${ESC}@")
        sb.append("${ESC}a${1.toChar()}")
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        sb.append("KITCHEN ORDER\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        sb.append("Time: $time\n")
        
        sb.append("--------------------------------\n")
        
        sb.append("${ESC}E${1.toChar()}")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            sb.append("${qty}x $name\n")
        }
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("--------------------------------\n")
        sb.append("\n\n\n")
        sb.append("${GS}V${66.toChar()}${3.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun formatBillFromJob(job: JSONObject): ByteArray {
        val orderId = job.optString("orderId", "N/A").takeLast(8).uppercase()
        val orderType = job.optString("orderType", "Dine-in")
        val tableName = job.optString("tableName", "")
        val items = job.optJSONArray("items") ?: JSONArray()
        val total = job.optString("total", "0").toDoubleOrNull() ?: job.optDouble("total", 0.0)
        val paymentMethod = job.optString("paymentMethod", "")
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        sb.append("${ESC}@")
        sb.append("${ESC}a${1.toChar()}")
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x11.toChar()}")
        sb.append("RESTORANK\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        sb.append("TAX INVOICE / RECEIPT\n")
        sb.append("================================\n")
        
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Date: $time\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        if (paymentMethod.isNotEmpty()) {
            sb.append("Payment: ${paymentMethod.replaceFirstChar { it.uppercase() }}\n")
        }
        
        sb.append("--------------------------------\n")
        
        var subtotal = 0.0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val price = item.optString("price", "0").toDoubleOrNull() ?: item.optDouble("price", 0.0)
            val lineTotal = price * qty
            subtotal += lineTotal
            
            val leftPart = "${qty}x $name"
            val rightPart = "RM %.2f".format(lineTotal)
            val spaces = 32 - leftPart.length - rightPart.length
            val padding = if (spaces > 0) " ".repeat(spaces) else " "
            sb.append("$leftPart$padding$rightPart\n")
        }
        
        sb.append("--------------------------------\n")
        
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        val totalLine = "TOTAL"
        val totalValue = "RM %.2f".format(total)
        val totalSpaces = 32 - totalLine.length - totalValue.length
        sb.append("$totalLine${" ".repeat(maxOf(1, totalSpaces))}$totalValue\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        sb.append("${ESC}a${1.toChar()}")
        sb.append("\nThank you for dining with us!\n")
        sb.append("Please come again\n")
        
        sb.append("\n\n\n")
        sb.append("${GS}V${66.toChar()}${3.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun markOrderAsPrinted(orderId: String) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/orders/$orderId/printed")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write("{}")
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Order $orderId marked as printed")
            } else {
                Log.e(TAG, "Failed to mark order as printed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark order as printed: ${e.message}")
        }
    }

    private fun fetchPendingBillPrints(): List<JSONObject> {
        val baseUrl = printerManager.getServerUrl()
        val restaurantId = printerManager.getRestaurantId()
        
        return try {
            val url = URL("$baseUrl/api/restaurants/$restaurantId/orders/pending-bill-prints")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                
                val array = JSONArray(response)
                (0 until array.length()).map { array.getJSONObject(it) }
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pending bill prints: ${e.message}")
            emptyList()
        }
    }

    private fun fetchPendingTestPrints(): List<JSONObject> {
        val baseUrl = printerManager.getServerUrl()
        val restaurantId = printerManager.getRestaurantId()
        
        return try {
            val url = URL("$baseUrl/api/restaurants/$restaurantId/pending-test-prints")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                
                val array = JSONArray(response)
                (0 until array.length()).map { array.getJSONObject(it) }
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pending test prints: ${e.message}")
            emptyList()
        }
    }

    private fun executeTestPrint(testPrint: JSONObject) {
        val testPrintId = testPrint.optString("id")
        val printerName = testPrint.optString("printerName", "Unknown")
        val ip = testPrint.optString("networkIp")
        val portStr = testPrint.optString("networkPort", "9100")
        val port = portStr.toIntOrNull() ?: 9100
        
        Log.d(TAG, "Executing test print $testPrintId to $printerName ($ip:$port)")
        
        if (ip.isEmpty()) {
            Log.e(TAG, "Test print has no IP address configured")
            markTestPrintComplete(testPrintId, "No IP address configured")
            return
        }
        
        try {
            // Build test receipt
            val receipt = buildTestReceipt(printerName, ip, port)
            
            // Send to printer
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), 5000)
            socket.soTimeout = 5000
            
            val outputStream = socket.getOutputStream()
            outputStream.write(receipt)
            outputStream.flush()
            outputStream.close()
            socket.close()
            
            Log.d(TAG, "Test print sent successfully to $printerName")
            markTestPrintComplete(testPrintId, null)
        } catch (e: Exception) {
            Log.e(TAG, "Test print failed: ${e.message}")
            markTestPrintComplete(testPrintId, e.message ?: "Print failed")
        }
    }

    private fun buildTestReceipt(printerName: String, ip: String, port: Int): ByteArray {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val buffer = mutableListOf<Byte>()
        
        // ESC/POS commands
        val ESC: Byte = 0x1B
        val GS: Byte = 0x1D
        val LF: Byte = 0x0A
        
        // Initialize
        buffer.addAll(listOf(ESC, '@'.code.toByte()))
        
        // Center align
        buffer.addAll(listOf(ESC, 'a'.code.toByte(), 1))
        
        // Double height
        buffer.addAll(listOf(GS, '!'.code.toByte(), 0x10))
        buffer.addAll("RestoRank\n".toByteArray().toList())
        
        // Normal size
        buffer.addAll(listOf(GS, '!'.code.toByte(), 0x00))
        buffer.add(LF)
        
        // Bold on
        buffer.addAll(listOf(ESC, 'E'.code.toByte(), 1))
        buffer.addAll("*** TEST PRINT ***\n".toByteArray().toList())
        // Bold off
        buffer.addAll(listOf(ESC, 'E'.code.toByte(), 0))
        
        buffer.add(LF)
        buffer.addAll("Printer: $printerName\n".toByteArray().toList())
        buffer.addAll("IP: $ip:$port\n".toByteArray().toList())
        buffer.addAll("Time: $now\n".toByteArray().toList())
        buffer.add(LF)
        buffer.addAll("--------------------------------\n".toByteArray().toList())
        buffer.addAll("If you see this, printing works!\n".toByteArray().toList())
        buffer.addAll("--------------------------------\n".toByteArray().toList())
        buffer.add(LF)
        buffer.add(LF)
        buffer.add(LF)
        
        // Cut paper
        buffer.addAll(listOf(GS, 'V'.code.toByte(), 66, 0))
        
        return buffer.toByteArray()
    }

    private fun markTestPrintComplete(testPrintId: String, error: String?) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/test-prints/$testPrintId/complete")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val body = if (error != null) {
                """{"error": "${error.replace("\"", "\\\"")}" }"""
            } else {
                "{}"
            }
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write(body)
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Test print $testPrintId marked as ${if (error != null) "failed" else "complete"}")
            } else {
                Log.e(TAG, "Failed to mark test print complete: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark test print complete: ${e.message}")
        }
    }

    private fun markBillAsPrinted(orderId: String) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/orders/$orderId/bill-printed")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write("{}")
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Bill for order $orderId marked as printed")
            } else {
                Log.e(TAG, "Failed to mark bill as printed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark bill as printed: ${e.message}")
        }
    }

    private fun printBill(order: JSONObject) {
        val orderId = order.optString("id", "")
        
        // Get printer info from the order (set by server)
        val printerIp = order.optString("printerIp", "")
        val printerPortStr = order.optString("printerPort", "9100")
        val printerPort = printerPortStr.toIntOrNull() ?: 9100
        val printerName = order.optString("printerName", "Default")
        
        if (printerIp.isEmpty()) {
            // Fallback to local printer list
            val allPrinters = printerManager.getPrinters().filter { it.isEnabled }
            if (allPrinters.isEmpty()) {
                Log.w(TAG, "No enabled printers configured for bill printing")
                return
            }
            val printer = allPrinters.first()
            
            try {
                val receipt = formatBillReceipt(order)
                printToThermal(printer.ip, printer.port, receipt)
                Log.d(TAG, "Bill printed for order $orderId to ${printer.name}")
                
                if (orderId.isNotEmpty()) {
                    markBillAsPrinted(orderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to print bill to ${printer.name}: ${e.message}")
            }
            return
        }
        
        // Use printer info from server
        Log.d(TAG, "Printing bill for order $orderId to $printerName ($printerIp:$printerPort)")
        
        try {
            val receipt = formatBillReceipt(order)
            printToThermal(printerIp, printerPort, receipt)
            Log.d(TAG, "Bill printed for order $orderId to $printerName")
            
            // Mark bill as printed on the server
            if (orderId.isNotEmpty()) {
                markBillAsPrinted(orderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to print bill to $printerName: ${e.message}")
        }
    }

    private fun formatBillReceipt(order: JSONObject): ByteArray {
        val orderId = order.optString("id", "N/A").takeLast(8).uppercase()
        val orderType = order.optString("orderType", order.optString("type", "Dine-in"))
        val tableName = order.optString("tableName", "")
        val items = order.optJSONArray("items") ?: JSONArray()
        val total = order.optString("total", "0").toDoubleOrNull() ?: order.optDouble("total", 0.0)
        val discount = order.optString("discountAmount", "0").toDoubleOrNull() ?: order.optDouble("discountAmount", 0.0)
        val paymentMethod = order.optString("paymentMethod", "")
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        
        // ESC/POS commands
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        // Initialize printer
        sb.append("${ESC}@")
        
        // Center align
        sb.append("${ESC}a${1.toChar()}")
        
        // Bold on, double height for restaurant name
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x11.toChar()}")
        sb.append("RESTORANK\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        sb.append("TAX INVOICE / RECEIPT\n")
        sb.append("================================\n")
        
        // Left align for details
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Date: $time\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        if (paymentMethod.isNotEmpty()) {
            sb.append("Payment: ${paymentMethod.replaceFirstChar { it.uppercase() }}\n")
        }
        
        sb.append("--------------------------------\n")
        
        // Items
        var subtotal = 0.0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val price = item.optString("price", "0").toDoubleOrNull() ?: item.optDouble("price", 0.0)
            val lineTotal = price * qty
            subtotal += lineTotal
            
            // Format: qty x name ......... price
            val leftPart = "${qty}x $name"
            val rightPart = "RM %.2f".format(lineTotal)
            val spaces = 32 - leftPart.length - rightPart.length
            val padding = if (spaces > 0) " ".repeat(spaces) else " "
            sb.append("$leftPart$padding$rightPart\n")
        }
        
        sb.append("--------------------------------\n")
        
        // Subtotal
        val subtotalLine = "Subtotal"
        val subtotalValue = "RM %.2f".format(subtotal)
        val subtotalSpaces = 32 - subtotalLine.length - subtotalValue.length
        sb.append("$subtotalLine${" ".repeat(maxOf(1, subtotalSpaces))}$subtotalValue\n")
        
        // Discount if any
        if (discount > 0) {
            val discountLine = "Discount"
            val discountValue = "-RM %.2f".format(discount)
            val discountSpaces = 32 - discountLine.length - discountValue.length
            sb.append("$discountLine${" ".repeat(maxOf(1, discountSpaces))}$discountValue\n")
        }
        
        sb.append("================================\n")
        
        // Total - bold and larger
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        val totalLine = "TOTAL"
        val totalValue = "RM %.2f".format(total)
        val totalSpaces = 32 - totalLine.length - totalValue.length
        sb.append("$totalLine${" ".repeat(maxOf(1, totalSpaces))}$totalValue\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Footer
        sb.append("${ESC}a${1.toChar()}")
        sb.append("\nThank you for dining with us!\n")
        sb.append("Please come again\n")
        
        // Feed and cut
        sb.append("\n\n\n")
        sb.append("${GS}V${66.toChar()}${3.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun printOrder(order: JSONObject) {
        val allPrinters = printerManager.getPrinters().filter { it.isEnabled }
        if (allPrinters.isEmpty()) {
            Log.w(TAG, "No enabled printers configured")
            return
        }

        val orderId = order.optString("id", "")
        val items = order.optJSONArray("items") ?: JSONArray()
        var printedSuccessfully = false
        
        handler.post {
            voiceManager.speakKotOrder(order, repeatOnce = true)
        }
        
        // Group items by printer
        val printerItemsMap = mutableMapOf<String, MutableList<JSONObject>>()
        val unassignedItems = mutableListOf<JSONObject>()
        
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            // Check for single printerId (new format) or printerIds array (legacy)
            val printerId = item.optString("printerId", "")
            val printerIds = item.optJSONArray("printerIds")
            
            if (printerId.isNotEmpty()) {
                // New format: single printerId
                printerItemsMap.getOrPut(printerId) { mutableListOf() }.add(item)
            } else if (printerIds != null && printerIds.length() > 0) {
                // Legacy format: printerIds array
                for (j in 0 until printerIds.length()) {
                    val pid = printerIds.optString(j)
                    if (pid.isNotEmpty()) {
                        printerItemsMap.getOrPut(pid) { mutableListOf() }.add(item)
                    }
                }
            } else {
                // No assigned printers - add to unassigned list
                unassignedItems.add(item)
            }
        }
        
        // Print items to their assigned printers
        printerItemsMap.forEach { (printerId, printerItems) ->
            val printer = allPrinters.find { it.id == printerId }
            if (printer != null) {
                try {
                    val receipt = formatReceiptForItems(order, printerItems, printer.name)
                    printToThermal(printer.ip, printer.port, receipt)
                    Log.d(TAG, "Printed ${printerItems.size} items to ${printer.name}")
                    printedSuccessfully = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to print to ${printer.name}: ${e.message}")
                }
            }
        }
        
        // Print unassigned items to ALL enabled printers
        if (unassignedItems.isNotEmpty()) {
            allPrinters.forEach { printer ->
                try {
                    val receipt = formatReceiptForItems(order, unassignedItems, printer.name)
                    printToThermal(printer.ip, printer.port, receipt)
                    Log.d(TAG, "Printed ${unassignedItems.size} unassigned items to ${printer.name}")
                    printedSuccessfully = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to print to ${printer.name}: ${e.message}")
                }
            }
        }
        
        // Mark order as printed on the server
        if (printedSuccessfully && orderId.isNotEmpty()) {
            markOrderAsPrinted(orderId)
        }
    }

    private fun formatReceiptForItems(order: JSONObject, items: List<JSONObject>, printerName: String): ByteArray {
        val orderId = order.optString("id", "N/A").takeLast(6)
        val orderType = order.optString("orderType", order.optString("type", "Dine-in"))
        val tableName = order.optString("tableName", "")
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        
        // ESC/POS commands
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        // Initialize printer
        sb.append("${ESC}@")
        
        // Center align
        sb.append("${ESC}a${1.toChar()}")
        
        // Bold on, double height
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        sb.append("RESTORANK\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("$printerName\n")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Left align
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        sb.append("Time: $time\n")
        
        sb.append("--------------------------------\n")
        
        // Items
        sb.append("${ESC}E${1.toChar()}")
        var itemTotal = 0.0
        items.forEach { item ->
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val price = item.optDouble("price", 0.0)
            sb.append("${qty}x $name\n")
            sb.append("   RM %.2f\n".format(price * qty))
            itemTotal += price * qty
            
            val notes = item.optString("notes", "")
            if (notes.isNotEmpty()) {
                sb.append("   > $notes\n")
            }
        }
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Total for these items
        sb.append("${ESC}E${1.toChar()}")
        sb.append("Subtotal: RM %.2f\n".format(itemTotal))
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("\n\n\n")
        
        // Cut paper
        sb.append("${GS}V${66.toChar()}${3.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun formatReceipt(order: JSONObject): ByteArray {
        val orderId = order.optString("id", "N/A").takeLast(6)
        val orderType = order.optString("orderType", order.optString("type", "Dine-in"))
        val tableName = order.optString("tableName", "")
        val items = order.optJSONArray("items") ?: JSONArray()
        val total = order.optDouble("total", 0.0)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        
        // ESC/POS commands
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        // Initialize printer
        sb.append("${ESC}@")
        
        // Center align
        sb.append("${ESC}a${1.toChar()}")
        
        // Bold on, double height
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        sb.append("RESTORANK\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("Kitchen Ticket\n")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Left align
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        sb.append("Time: $time\n")
        
        sb.append("--------------------------------\n")
        
        // Items
        sb.append("${ESC}E${1.toChar()}")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val price = item.optDouble("price", 0.0)
            sb.append("${qty}x $name\n")
            sb.append("   RM %.2f\n".format(price * qty))
            
            val notes = item.optString("notes", "")
            if (notes.isNotEmpty()) {
                sb.append("   > $notes\n")
            }
        }
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Right align for total
        sb.append("${ESC}a${2.toChar()}")
        sb.append("${ESC}E${1.toChar()}")
        sb.append("TOTAL: RM %.2f\n".format(total))
        sb.append("${ESC}E${0.toChar()}")
        
        // Feed and cut
        sb.append("\n\n\n")
        sb.append("${GS}V${1.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun printToThermal(ip: String, port: Int, data: ByteArray) {
        var retries = 3
        var lastException: Exception? = null
        
        while (retries > 0) {
            val socket = Socket()
            try {
                socket.connect(java.net.InetSocketAddress(ip, port), 5000)
                socket.soTimeout = 5000
                val output = socket.getOutputStream()
                output.write(data)
                output.flush()
                socket.close()
                return // Success
            } catch (e: Exception) {
                lastException = e
                retries--
                Log.w(TAG, "Print attempt failed, retries left: $retries - ${e.message}")
                try { socket.close() } catch (ignored: Exception) {}
                if (retries > 0) {
                    Thread.sleep(1000) // Wait 1 second before retry
                }
            }
        }
        
        throw lastException ?: Exception("Failed to print after retries")
    }
}
