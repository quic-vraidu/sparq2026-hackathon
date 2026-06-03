package com.aster.ondevice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import java.util.Calendar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.ondevice.agent.OnDeviceAgent
import com.aster.ondevice.data.SettingsDataStore
import com.aster.ondevice.llm.LlmBackend
import com.aster.ondevice.llm.LlmEngine
import com.aster.ondevice.service.handlers.SmsHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ── Debug dataset (May 2026) — used when debugMode = true ────────────────────
private val DEBUG_SMS_MAY2026 = """
[{"from":"AD-HDFCBK-S","body":"Sent Rs.30.00 From HDFC Bank A/C *8431 To Mulkanoor Rice And Genera On 31/05/26 Ref 007665523136 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808 ","date":1780236938784}]
""".trimIndent()


// ── Analysis types ────────────────────────────────────────────────────────────

enum class AnalysisType(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    SPENDING(
        label       = "Monthly Spending Total",
        description = "Sum all debit/spending transactions from bank alerts, UPI/NEFT/IMPS, card & wallet SMS for the selected month",
        icon        = Icons.Default.BarChart,
    );
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

@HiltViewModel
class AnalyserViewModel @Inject constructor(
    private val agent:      OnDeviceAgent,
    private val smsHandler: SmsHandler,
    private val settings:   SettingsDataStore,
    val llm: LlmEngine,
) : ViewModel() {

    var isRunning      by mutableStateOf(false)              ; private set
    var result         by mutableStateOf("")                 ; private set
    var chunkProgress  by mutableStateOf("")                 ; private set
    var activeType     by mutableStateOf<AnalysisType?>(null); private set
    var selectedYear   by mutableStateOf(Calendar.getInstance().get(Calendar.YEAR))
    var selectedMonth  by mutableStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) // 1-12
    var debugMode      by mutableStateOf(false)

    fun setYear(year: Int)   { selectedYear  = year  }
    fun setMonth(month: Int) { selectedMonth = month }

    fun run(type: AnalysisType) {
        if (isRunning) return
        viewModelScope.launch {
            isRunning     = true
            activeType    = type
            result        = ""
            chunkProgress = ""

            // Date range for selected month
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth - 1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val sinceMs    = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val toMs       = cal.timeInMillis
            val monthLabel = "${MONTH_NAMES[selectedMonth - 1]} $selectedYear"

            // Chunk size: cloud handles more tokens; on-device keep it small
            val backend   = withContext(Dispatchers.IO) { settings.getLlmBackend() }
            val chunkSize = if (backend == LlmBackend.QAIC_CLOUD) 30 else 5

            val chunks = if (debugMode) {
                parseAndChunk(DEBUG_SMS_MAY2026, chunkSize)
            } else {
                smsHandler.readSmsInChunks(sinceMs, toMs, chunkSize)
            }

            if (chunks.isEmpty()) {
                result    = "No SMS found for $monthLabel."
                isRunning = false
                return@launch
            }

            // Open log file — one file per run, same dir as AgentLogger
            val logFile = runCatching {
                val dir = File("/storage/emulated/0/Download/aster").also { it.mkdirs() }
                val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val src = if (debugMode) "debug" else "device"
                File(dir, "analyser_${src}_$ts.txt").also {
                    it.writeText("=== SMS ANALYSER LOG ===\nPeriod: $monthLabel\nSource: $src\nChunks: ${chunks.size} × $chunkSize SMS\nBackend: $backend\n\n")
                }
            }.getOrNull()

            var totalSpending = 0.0
            val batchLines    = StringBuilder()

            chunks.forEachIndexed { idx, chunk ->
                chunkProgress = "Batch ${idx + 1} / ${chunks.size}…"

                val smsCount = runCatching {
                    Json.parseToJsonElement(chunk).jsonArray.size
                }.getOrDefault(chunkSize)

                val prompt = buildString {
                    append("=== SMS DATA (batch ${idx + 1}/${chunks.size} · $monthLabel · $smsCount messages) ===\n")
                    append("Read only the \"body\" field of each SMS to find transactions. Ignore \"from\" and \"date\".\n")
                    append(chunk)
                    append("\n=== END SMS DATA ===\n\n")
                    append("=== RULES ===\n")
                    append("INCLUDE — body contains outgoing payment keywords:\n")
                    append("- \"Sent Rs.\" or \"Sent INR\" (UPI sent)\n")
                    append("- \"debited from\" or \"UPDATE: INR\" followed by \"debited\" (bank debit)\n")
                    append("- \"Withdrawn Rs.\" (ATM/card withdrawal)\n")
                    append("- \"You've spent INR\" or \"Alert: You've spent\" (credit card)\n")
                    append("- \"NEFT Dr\" or \"IMPS Dr\" (outgoing transfer)\n")
                    append("- \"ACH D-\" or \"PAYMENT ALERT! INR\" or \"UPI Mandate: Sent\" (EMI/mandate)\n")
                    append("EXCLUDE — do NOT count:\n")
                    append("- \"credited\", \"deposited\", \"NEFT Cr\", \"Credit Alert\" (incoming)\n")
                    append("- \"Refund\", \"refunded\" (refunds)\n")
                    append("- Salary/employer deposits (e.g. QUALCOMM, payroll)\n")
                    append("- \"Avl bal\", \"Bal Rs.\" — these are account balances, NOT transaction amounts\n")
                    append("- OTPs, promotions, ads, non-financial notifications\n")
                    append("- Loan repayment received notifications (e.g. \"successfully received a payment of Rs. 14125.0 via ONLINE towards your Fibe loan repayment\")\n")
                    append("=== END RULES ===\n\n")
                    append("Just provide final output value, no need to send the thinking and thought output, just final amount for each sms body\n")
                    append("For each SMS body that matches INCLUDE rules, write one line: AMOUNT=<number>\n")
                    append("Extract only the transaction amount — not the balance (\"Avl bal\") or reference number.\n")
                    append("If no spending SMS, write: AMOUNT=0\n")
                    append("Provide final total amount which sum of individual amount for each sms body\n")
                    append("TOTAL AMOUNT=")
                }

                // Log input
                logFile?.appendText("=== BATCH ${idx + 1} INPUT ===\n$prompt\n\n")

                // up to 350 tokens of output per SMS in the chunk
                val maxTokens = chunkSize * 350
                val response = agent.generateDirect(prompt, maxTokens)
                val amount   = parseChunkTotal(response)
                totalSpending += amount
                batchLines.append("Batch ${idx + 1}: ₹${"%.2f".format(amount)}\n")

                // Log output + extracted value
                logFile?.appendText("=== BATCH ${idx + 1} OUTPUT ===\n$response\n\nExtracted: ₹${"%.2f".format(amount)}\n\n")
            }

            result = buildString {
                append("Period : $monthLabel\n")
                append("Batches: ${chunks.size} × $chunkSize SMS\n")
                if (debugMode) append("Source : debug (May 2026 sample)\n")
                append("\n")
                append(batchLines)
                append("\nTotal Spending: ₹${"%.2f".format(totalSpending)}")
            }

            // Log final result
            logFile?.appendText("=== FINAL RESULT ===\n$result\n")
            logFile?.let { chunkProgress = "Log: ${it.absolutePath}" }

            isRunning = false
        }
    }

    /** Extract INR amount from LLM response. */
    private fun parseChunkTotal(response: String): Double {
        // Primary: TOTAL AMOUNT=<number> — the model is asked to output a single summed line
        Regex("TOTAL\\s+AMOUNT\\s*=\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.let { return it }

        // Fallback: sum all individual AMOUNT=<number> lines if model lists them separately
        val amountRegex = Regex("(?<!TOTAL\\s)AMOUNT\\s*=\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val amounts = amountRegex.findAll(response)
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filter { it > 0 }
            .toList()
        if (amounts.isNotEmpty()) return amounts.sum()

        // Fallback 2: "Total ... INR/₹ <number>"
        Regex("total[\\s\\w]*[:\\s]+(?:INR|₹|Rs\\.?)?\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.let { return it }

        return 0.0
    }

    /** Parse a raw JSON array string and split into chunked JSON array strings. */
    private fun parseAndChunk(json: String, chunkSize: Int): List<String> {
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()
            ?: return emptyList()
        return array.chunked(chunkSize).map { chunk ->
            buildString {
                append("[")
                chunk.forEachIndexed { i, elem -> if (i > 0) append(","); append(elem) }
                append("]")
            }
        }
    }

    fun clear() {
        result        = ""
        activeType    = null
        chunkProgress = ""
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun AnalyserScreen(vm: AnalyserViewModel = hiltViewModel()) {
    val modelLoaded = vm.llm.isLoaded()
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val years       = (currentYear - 2..currentYear).toList()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SMS Analyser", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Turns your SMS inbox into structured intelligence — on-device, private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!modelLoaded) {
            Card(
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "No model loaded. Open Settings to load a model first.",
                    modifier = Modifier.padding(12.dp),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // ── Period selector ───────────────────────────────────────────────────
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Period", style = MaterialTheme.typography.titleSmall)

                // Year chips
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    years.forEach { year ->
                        FilterChip(
                            selected = vm.selectedYear == year,
                            onClick  = { vm.setYear(year) },
                            label    = { Text(year.toString(), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Month chips — 3 rows of 4
                listOf(0..3, 4..7, 8..11).forEach { range ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        range.forEach { idx ->
                            FilterChip(
                                selected = vm.selectedMonth == idx + 1,
                                onClick  = { vm.setMonth(idx + 1) },
                                label    = { Text(MONTH_NAMES[idx], style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Debug toggle
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Debug mode (May 2026 sample data)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = vm.debugMode, onCheckedChange = { vm.debugMode = it }, enabled = !vm.isRunning)
        }

        // Analysis type cards
        AnalysisType.entries.forEach { type ->
            AnalysisCard(
                type       = type,
                isActive   = vm.activeType == type && vm.isRunning,
                isDisabled = vm.isRunning || !modelLoaded,
                onRun      = { vm.run(type) },
            )
        }

        // Results area
        if (vm.isRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val status = vm.chunkProgress.ifBlank { vm.activeType?.let { "Running: ${it.label}…" } ?: "" }
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (vm.result.isNotBlank()) {
            Divider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    vm.activeType?.label ?: "Result",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { vm.clear() }) { Text("Clear") }
            }
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = vm.result,
                    modifier = Modifier.padding(14.dp),
                    style    = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Analysis card ─────────────────────────────────────────────────────────────

@Composable
private fun AnalysisCard(
    type: AnalysisType,
    isActive: Boolean,
    isDisabled: Boolean,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector        = type.icon,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(type.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        type.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isActive) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalButton(
                    onClick        = onRun,
                    enabled        = !isDisabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
