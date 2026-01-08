package ai.restorank.bridge

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.content.ContextCompat
import android.webkit.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var txtStatus: TextView
    
    private var currentTab = "home"

    companion object {
        private const val BASE_URL = "https://restorank.replit.app/#/dashboard"
        private const val TAG = "RestoRankBridge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupClickListeners()
        updateStatus()
        
        // Start the auto-print service
        startService(Intent(this, PrintService::class.java))

        // Setup bottom navigation
        setupBottomNavigation()
        
        // Load default page
        navigateTo("home")
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        txtStatus = findViewById(R.id.txtStatus)

        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_green_light,
            android.R.color.holo_blue_bright,
            android.R.color.holo_orange_light
        )
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    private fun setupClickListeners() {
        findViewById<ImageButton>(R.id.btnPrinterSettings).setOnClickListener {
            startActivity(Intent(this, PrinterSettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            webView.reload()
        }
    }
    
    private fun setupBottomNavigation() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            navigateTo("home")
        }
        
        findViewById<LinearLayout>(R.id.navTables).setOnClickListener {
            navigateTo("tables")
        }
        
        findViewById<LinearLayout>(R.id.navKitchen).setOnClickListener {
            navigateTo("kitchen")
        }
        
        findViewById<LinearLayout>(R.id.navOrders).setOnClickListener {
            navigateTo("orders")
        }
    }
    
    private fun navigateTo(tab: String) {
        if (currentTab == tab && webView.url?.contains(tab) == true) {
            return
        }
        
        currentTab = tab
        val url = when (tab) {
            "home" -> "$BASE_URL/home"
            "tables" -> "$BASE_URL/tables"
            "kitchen" -> "$BASE_URL/kitchen"
            "orders" -> "$BASE_URL/orders"
            else -> "$BASE_URL/home"
        }
        
        webView.loadUrl(url)
        updateTabSelection(tab)
    }
    
    private fun updateTabSelection(selectedTab: String) {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val grayColor = ContextCompat.getColor(this, R.color.gray_600)
        
        // Reset all tabs
        findViewById<ImageView>(R.id.iconHome).setColorFilter(grayColor)
        findViewById<TextView>(R.id.labelHome).setTextColor(grayColor)
        findViewById<ImageView>(R.id.iconTables).setColorFilter(grayColor)
        findViewById<TextView>(R.id.labelTables).setTextColor(grayColor)
        findViewById<ImageView>(R.id.iconKitchen).setColorFilter(grayColor)
        findViewById<TextView>(R.id.labelKitchen).setTextColor(grayColor)
        findViewById<ImageView>(R.id.iconOrders).setColorFilter(grayColor)
        findViewById<TextView>(R.id.labelOrders).setTextColor(grayColor)
        
        // Highlight selected tab
        when (selectedTab) {
            "home" -> {
                findViewById<ImageView>(R.id.iconHome).setColorFilter(primaryColor)
                findViewById<TextView>(R.id.labelHome).setTextColor(primaryColor)
            }
            "tables" -> {
                findViewById<ImageView>(R.id.iconTables).setColorFilter(primaryColor)
                findViewById<TextView>(R.id.labelTables).setTextColor(primaryColor)
            }
            "kitchen" -> {
                findViewById<ImageView>(R.id.iconKitchen).setColorFilter(primaryColor)
                findViewById<TextView>(R.id.labelKitchen).setTextColor(primaryColor)
            }
            "orders" -> {
                findViewById<ImageView>(R.id.iconOrders).setColorFilter(primaryColor)
                findViewById<TextView>(R.id.labelOrders).setTextColor(primaryColor)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Connection error. Pull to refresh.", Toast.LENGTH_LONG).show()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.contains("restorank") || url.contains("replit")) {
                        false
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                } else {
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }
        }

        // Add JavaScript interface for on-demand printing
        webView.addJavascriptInterface(PrinterBridge(this), "AndroidPrinter")
    }

    inner class PrinterBridge(private val context: Context) {
        private val printerManager = PrinterManager(context)

        @android.webkit.JavascriptInterface
        fun isAvailable(): Boolean {
            return true
        }

        @android.webkit.JavascriptInterface
        fun printBill(orderId: String, orderJson: String): String {
            return try {
                val order = org.json.JSONObject(orderJson)
                val billPrinters = printerManager.getPrinters().filter { it.isEnabled }
                
                if (billPrinters.isEmpty()) {
                    return "No printer configured"
                }
                
                var success = false
                billPrinters.forEach { printer ->
                    try {
                        val receipt = formatBillReceipt(order)
                        printToThermal(printer.ip, printer.port, receipt)
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.e("PrinterBridge", "Failed to print to ${printer.name}: ${e.message}")
                    }
                }
                
                if (success) "OK" else "Print failed"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

        @android.webkit.JavascriptInterface
        fun testPrint(printerIp: String, printerPort: Int): String {
            return try {
                val testData = buildTestReceipt()
                printToThermal(printerIp, printerPort, testData)
                "OK"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

        private fun formatBillReceipt(order: org.json.JSONObject): ByteArray {
            val orderId = order.optString("id", "N/A").takeLast(8)
            val orderType = order.optString("orderType", order.optString("type", "Dine-in"))
            val tableName = order.optString("tableName", "")
            val items = order.optJSONArray("items") ?: org.json.JSONArray()
            val total = order.optString("total", "0").toDoubleOrNull() ?: order.optDouble("total", 0.0)
            val subtotal = order.optString("subtotal", "0").toDoubleOrNull() ?: total
            val discount = order.optString("discount", "0").toDoubleOrNull() ?: order.optDouble("discount", 0.0)
            val time = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

            val sb = StringBuilder()
            val ESC = 0x1B.toChar()
            val GS = 0x1D.toChar()

            sb.append("${ESC}@")
            sb.append("${ESC}a${1.toChar()}")
            sb.append("${ESC}E${1.toChar()}")
            sb.append("${GS}!${0x10.toChar()}")
            sb.append("RESTORANK\n")
            sb.append("${GS}!${0.toChar()}")
            sb.append("${ESC}E${0.toChar()}")
            sb.append("\n")
            sb.append("================================\n")
            sb.append("        CUSTOMER BILL\n")
            sb.append("================================\n")
            sb.append("${ESC}a${0.toChar()}")
            sb.append("Order: #$orderId\n")
            sb.append("Type: $orderType\n")
            if (tableName.isNotEmpty()) {
                sb.append("Table: $tableName\n")
            }
            sb.append("Date: $time\n")
            sb.append("--------------------------------\n")

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("name", "Item")
                val qty = item.optInt("quantity", 1)
                val price = item.optString("price", "0").toDoubleOrNull() ?: item.optDouble("price", 0.0)
                val lineTotal = price * qty
                sb.append("${qty}x $name\n")
                sb.append("          RM %.2f\n".format(lineTotal))
            }

            sb.append("--------------------------------\n")
            sb.append("Subtotal:     RM %.2f\n".format(subtotal))
            if (discount > 0) {
                sb.append("Discount:    -RM %.2f\n".format(discount))
            }
            sb.append("${ESC}E${1.toChar()}")
            sb.append("${GS}!${0x10.toChar()}")
            sb.append("TOTAL:        RM %.2f\n".format(total))
            sb.append("${GS}!${0.toChar()}")
            sb.append("${ESC}E${0.toChar()}")
            sb.append("================================\n")
            sb.append("${ESC}a${1.toChar()}")
            sb.append("Thank you for dining with us!\n")
            sb.append("\n\n\n")
            sb.append("${GS}V${66.toChar()}${3.toChar()}")

            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun buildTestReceipt(): ByteArray {
            val sb = StringBuilder()
            val ESC = 0x1B.toChar()
            val GS = 0x1D.toChar()

            sb.append("${ESC}@")
            sb.append("${ESC}a${1.toChar()}")
            sb.append("${ESC}E${1.toChar()}")
            sb.append("RESTORANK\n")
            sb.append("${ESC}E${0.toChar()}")
            sb.append("Test Print OK\n")
            sb.append(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            sb.append("\n\n\n")
            sb.append("${GS}V${66.toChar()}${3.toChar()}")

            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun printToThermal(ip: String, port: Int, data: ByteArray) {
            var retries = 3
            var lastException: Exception? = null

            while (retries > 0) {
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress(ip, port), 5000)
                    socket.soTimeout = 5000
                    val output = socket.getOutputStream()
                    output.write(data)
                    output.flush()
                    socket.close()
                    return
                } catch (e: Exception) {
                    lastException = e
                    retries--
                    try { socket.close() } catch (ignored: Exception) {}
                    if (retries > 0) Thread.sleep(500)
                }
            }

            throw lastException ?: Exception("Failed to print after retries")
        }
    }

    private fun updateStatus() {
        val printerManager = PrinterManager(this)
        val printers = printerManager.getPrinters()
        val serviceRunning = PrintService.isRunning
        
        txtStatus.visibility = View.VISIBLE
        if (printers.isEmpty()) {
            txtStatus.text = "No printers configured - tap gear icon to add"
            txtStatus.setBackgroundColor(0xFFFEE2E2.toInt())
        } else {
            val status = if (serviceRunning) "Auto-print: ON" else "Auto-print: OFF"
            txtStatus.text = "$status | ${printers.size} printer(s) configured"
            txtStatus.setBackgroundColor(0xFFD1FAE5.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
