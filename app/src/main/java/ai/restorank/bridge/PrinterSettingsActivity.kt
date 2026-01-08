package ai.restorank.bridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

class PrinterSettingsActivity : AppCompatActivity() {

    private lateinit var printerManager: PrinterManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PrinterAdapter
    private lateinit var switchAutoPrint: SwitchCompat
    private lateinit var txtNoPrinters: TextView
    private lateinit var btnSyncPrinters: Button
    private lateinit var btnSyncToWeb: Button
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_settings)

        printerManager = PrinterManager(this)
        
        initViews()
        loadPrinters()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerPrinters)
        switchAutoPrint = findViewById(R.id.switchAutoPrint)
        txtNoPrinters = findViewById(R.id.txtNoPrinters)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PrinterAdapter(
            onTest = { printer -> testPrinter(printer) },
            onToggle = { printer, enabled -> togglePrinter(printer, enabled) },
            onDelete = { printer -> deletePrinter(printer) }
        )
        recyclerView.adapter = adapter

        switchAutoPrint.isChecked = printerManager.isAutoPrintEnabled()
        switchAutoPrint.setOnCheckedChangeListener { _, isChecked ->
            printerManager.setAutoPrintEnabled(isChecked)
            if (isChecked) {
                startService(Intent(this, PrintService::class.java))
            }
            Toast.makeText(this, 
                if (isChecked) "Auto-print enabled" else "Auto-print disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<FloatingActionButton>(R.id.fabAddPrinter).setOnClickListener {
            showAddPrinterDialog()
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnSyncPrinters = findViewById(R.id.btnSyncPrinters)
        btnSyncPrinters.setOnClickListener {
            syncPrintersFromServer()
        }

        btnSyncToWeb = findViewById(R.id.btnSyncToWeb)
        btnSyncToWeb.setOnClickListener {
            syncPrintersToServer()
        }
    }

    private fun syncPrintersFromServer() {
        btnSyncPrinters.isEnabled = false
        btnSyncPrinters.text = "Syncing..."
        
        executor.execute {
            val result = printerManager.syncPrintersFromServer()
            runOnUiThread {
                btnSyncPrinters.isEnabled = true
                btnSyncPrinters.text = "Sync from Web"
                
                if (result.isSuccess) {
                    val printers = result.getOrNull() ?: emptyList()
                    Toast.makeText(this, "Synced ${printers.size} printer(s) from server", Toast.LENGTH_SHORT).show()
                    loadPrinters()
                } else {
                    Toast.makeText(this, "Sync failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncPrintersToServer() {
        btnSyncToWeb.isEnabled = false
        btnSyncToWeb.text = "Syncing..."
        
        executor.execute {
            val result = printerManager.syncPrintersToServer()
            runOnUiThread {
                btnSyncToWeb.isEnabled = true
                btnSyncToWeb.text = "Sync to Web"
                
                if (result.isSuccess) {
                    val syncResult = result.getOrNull()!!
                    val message = when {
                        syncResult.created > 0 -> "Added ${syncResult.created} printer(s) to web dashboard"
                        syncResult.skipped > 0 -> "All ${syncResult.skipped} printer(s) already exist in web"
                        else -> syncResult.message
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sync failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadPrinters() {
        val printers = printerManager.getPrinters()
        adapter.submitList(printers)
        txtNoPrinters.visibility = if (printers.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (printers.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddPrinterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_printer, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etPrinterName)
        val etIp = dialogView.findViewById<TextInputEditText>(R.id.etPrinterIp)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.etPrinterPort)
        val btnTest = dialogView.findViewById<Button>(R.id.btnTestConnection)
        val txtTestResult = dialogView.findViewById<TextView>(R.id.txtTestResult)

        etPort.setText("9100")

        btnTest.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: 9100
            
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            txtTestResult.text = "Testing..."
            txtTestResult.visibility = View.VISIBLE

            executor.execute {
                val result = printerManager.testConnection(ip, port)
                runOnUiThread {
                    btnTest.isEnabled = true
                    if (result.isSuccess) {
                        txtTestResult.text = "✓ Connection successful!"
                        txtTestResult.setTextColor(0xFF10B981.toInt())
                    } else {
                        txtTestResult.text = "✗ Connection failed: ${result.exceptionOrNull()?.message}"
                        txtTestResult.setTextColor(0xFFEF4444.toInt())
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Add Printer")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 9100

                if (name.isEmpty() || ip.isEmpty()) {
                    Toast.makeText(this, "Name and IP are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                printerManager.addPrinter(name, ip, port)
                loadPrinters()
                Toast.makeText(this, "Printer added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testPrinter(printer: Printer) {
        Toast.makeText(this, "Testing ${printer.name}...", Toast.LENGTH_SHORT).show()
        
        executor.execute {
            val result = printerManager.testConnection(printer.ip, printer.port)
            runOnUiThread {
                if (result.isSuccess) {
                    // Send test print
                    sendTestPrint(printer)
                } else {
                    Toast.makeText(this, "Connection failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendTestPrint(printer: Printer) {
        executor.execute {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(printer.ip, printer.port), 5000)
                
                val ESC = 0x1B.toChar()
                val GS = 0x1D.toChar()
                
                val testReceipt = buildString {
                    append("${ESC}@") // Initialize
                    append("${ESC}a${1.toChar()}") // Center
                    append("${ESC}E${1.toChar()}") // Bold
                    append("RESTORANK BRIDGE\n")
                    append("${ESC}E${0.toChar()}")
                    append("Test Print\n")
                    append("================================\n")
                    append("${ESC}a${0.toChar()}") // Left
                    append("Printer: ${printer.name}\n")
                    append("IP: ${printer.ip}:${printer.port}\n")
                    append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
                    append("================================\n")
                    append("${ESC}a${1.toChar()}")
                    append("Printer is working!\n")
                    append("\n\n\n")
                    append("${GS}V${1.toChar()}") // Cut
                }
                
                socket.getOutputStream().write(testReceipt.toByteArray())
                socket.close()
                
                runOnUiThread {
                    Toast.makeText(this, "Test print sent to ${printer.name}!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun togglePrinter(printer: Printer, enabled: Boolean) {
        printerManager.togglePrinter(printer.id, enabled)
        loadPrinters()
    }

    private fun deletePrinter(printer: Printer) {
        AlertDialog.Builder(this)
            .setTitle("Delete Printer")
            .setMessage("Remove ${printer.name}?")
            .setPositiveButton("Delete") { _, _ ->
                printerManager.removePrinter(printer.id)
                loadPrinters()
                Toast.makeText(this, "Printer removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class PrinterAdapter(
        private val onTest: (Printer) -> Unit,
        private val onToggle: (Printer, Boolean) -> Unit,
        private val onDelete: (Printer) -> Unit
    ) : RecyclerView.Adapter<PrinterAdapter.ViewHolder>() {

        private var printers: List<Printer> = emptyList()

        fun submitList(list: List<Printer>) {
            printers = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_printer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(printers[position])
        }

        override fun getItemCount() = printers.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val txtName: TextView = view.findViewById(R.id.txtPrinterName)
            private val txtIp: TextView = view.findViewById(R.id.txtPrinterIp)
            private val switchEnabled: SwitchCompat = view.findViewById(R.id.switchEnabled)
            private val btnTest: Button = view.findViewById(R.id.btnTest)
            private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

            fun bind(printer: Printer) {
                txtName.text = printer.name
                txtIp.text = "${printer.ip}:${printer.port}"
                switchEnabled.isChecked = printer.isEnabled
                
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(printer, isChecked)
                }
                
                btnTest.setOnClickListener { onTest(printer) }
                btnDelete.setOnClickListener { onDelete(printer) }
            }
        }
    }
}
