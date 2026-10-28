package com.example.gigprofit

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ───────────────────────── Data models ─────────────────────────

data class WearTearItem(
    val id: Long,
    val name: String,
    val intervalKm: Double,     // replace every X km
    val estimatedCost: Double   // RM per replacement
)

data class FixedReserveItem(
    val id: Long,
    val name: String,
    val monthlyAmount: Double   // RM per month
)

data class AppSettings(
    val fuelPricePerLitre: Double = 2.05,
    val litresPer100Km: Double = 7.0,
    val workingDaysPerMonth: Int = 26,
    val wearItems: List<WearTearItem> = listOf(
        WearTearItem(1, "Engine oil & filter", 10_000.0, 180.0),
        WearTearItem(2, "Tyres (set of 4)", 40_000.0, 800.0),
        WearTearItem(3, "Brake pads", 30_000.0, 250.0),
        WearTearItem(4, "Service & misc.", 20_000.0, 300.0)
    ),
    val fixedItems: List<FixedReserveItem> = listOf(
        FixedReserveItem(1, "Insurance & road tax", 200.0),
        FixedReserveItem(2, "Phone & data", 50.0)
    )
)

// ───────────────────────── Math engine ─────────────────────────

data class Result(
    val fuelLitres: Double,
    val fuelCost: Double,
    val wearReserve: Double,
    val fixedAllocation: Double,
    val tolls: Double,
    val totalCosts: Double,
    val netProfit: Double,
    val netPerHour: Double,
    val netPerKm: Double
)

object ProfitEngine {
    fun fuelCostPerKm(s: AppSettings) = s.fuelPricePerLitre * s.litresPer100Km / 100.0

    fun wearPerKm(s: AppSettings) =
        s.wearItems.sumOf { if (it.intervalKm > 0) it.estimatedCost / it.intervalKm else 0.0 }

    fun fixedPerDay(s: AppSettings) =
        s.fixedItems.sumOf { it.monthlyAmount } / s.workingDaysPerMonth.coerceAtLeast(1)

    fun calculate(s: AppSettings, earnings: Double, km: Double, hours: Double, tolls: Double): Result {
        val litres = km * s.litresPer100Km / 100.0
        val fuel = km * fuelCostPerKm(s)
        val wear = km * wearPerKm(s)
        val fixed = fixedPerDay(s)
        val costs = fuel + wear + fixed + tolls
        val net = earnings - costs
        return Result(
            fuelLitres = litres, fuelCost = fuel, wearReserve = wear, fixedAllocation = fixed,
            tolls = tolls, totalCosts = costs, netProfit = net,
            netPerHour = if (hours > 0) net / hours else 0.0,
            netPerKm = if (km > 0) net / km else 0.0
        )
    }
}

// ───────────────────────── Persistence + ViewModel ─────────────────────────

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("gig_profit", 0)

    var settings by mutableStateOf(load())
        private set

    fun update(new: AppSettings) {
        settings = new
        save(new)
    }

    private fun save(s: AppSettings) {
        val json = JSONObject()
            .put("fuelPrice", s.fuelPricePerLitre)
            .put("l100", s.litresPer100Km)
            .put("days", s.workingDaysPerMonth)
            .put("wear", JSONArray().also { a ->
                s.wearItems.forEach {
                    a.put(JSONObject().put("id", it.id).put("n", it.name)
                        .put("km", it.intervalKm).put("c", it.estimatedCost))
                }
            })
            .put("fixed", JSONArray().also { a ->
                s.fixedItems.forEach {
                    a.put(JSONObject().put("id", it.id).put("n", it.name).put("m", it.monthlyAmount))
                }
            })
        prefs.edit().putString("settings", json.toString()).apply()
    }

    private fun load(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return try {
            val j = JSONObject(raw)
            val w = j.getJSONArray("wear")
            val f = j.getJSONArray("fixed")
            AppSettings(
                fuelPricePerLitre = j.getDouble("fuelPrice"),
                litresPer100Km = j.getDouble("l100"),
                workingDaysPerMonth = j.getInt("days"),
                wearItems = (0 until w.length()).map {
                    val o = w.getJSONObject(it)
                    WearTearItem(o.getLong("id"), o.getString("n"), o.getDouble("km"), o.getDouble("c"))
                },
                fixedItems = (0 until f.length()).map {
                    val o = f.getJSONObject(it)
                    FixedReserveItem(o.getLong("id"), o.getString("n"), o.getDouble("m"))
                }
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }
}

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ───────────────────────── UI helpers ─────────────────────────

fun rm(v: Double): String = String.format(Locale.US, "RM %.2f", v)
fun fmtNum(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
fun NumField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
fun Kpi(label: String, value: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ───────────────────────── App shell ─────────────────────────

@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Dashboard") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) Dashboard(vm.settings) else SettingsScreen(vm.settings, vm::update)
        }
    }
}

// ───────────────────────── Dashboard ─────────────────────────

@Composable
fun Dashboard(s: AppSettings) {
    var earnings by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("") }
    var tolls by rememberSaveable { mutableStateOf("") }

    val r = ProfitEngine.calculate(
        s,
        earnings.toDoubleOrNull() ?: 0.0,
        km.toDoubleOrNull() ?: 0.0,
        hours.toDoubleOrNull() ?: 0.0,
        tolls.toDoubleOrNull() ?: 0.0
    )

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Today's Run", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        NumField(earnings, "Gross earnings (RM)", { earnings = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(km, "Distance (km)", { km = it }, Modifier.weight(1f))
            NumField(hours, "Hours online", { hours = it }, Modifier.weight(1f))
        }
        NumField(tolls, "Tolls / parking (RM)", { tolls = it })

        val good = r.netProfit >= 0
        Kpi(
            "NET PROFIT",
            rm(r.netProfit),
            Modifier.fillMaxWidth(),
            if (good) Color(0xFF1B5E20) else Color(0xFFB71C1C)
        )
        // Re-style big card text to white via a second line summary
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Net / hour", rm(r.netPerHour), Modifier.weight(1f))
            Kpi("Net / km", rm(r.netPerKm), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Fuel (${String.format(Locale.US, "%.1f", r.fuelLitres)} L)", rm(r.fuelCost), Modifier.weight(1f))
            Kpi("Wear & tear reserve", rm(r.wearReserve), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Daily fixed reserve", rm(r.fixedAllocation), Modifier.weight(1f))
            Kpi("Tolls", rm(r.tolls), Modifier.weight(1f))
        }
        Kpi("Total costs & reserves", rm(r.totalCosts), Modifier.fillMaxWidth(),
            MaterialTheme.colorScheme.secondaryContainer)
    }
}

// ───────────────────────── Settings ─────────────────────────

@Composable
fun SettingsScreen(s: AppSettings, onChange: (AppSettings) -> Unit) {
    var price by rememberSaveable { mutableStateOf(fmtNum(s.fuelPricePerLitre)) }
    var l100 by rememberSaveable { mutableStateOf(fmtNum(s.litresPer100Km)) }
    var days by rememberSaveable { mutableStateOf(s.workingDaysPerMonth.toString()) }

    var wearDialog by remember { mutableStateOf<WearTearItem?>(null) }
    var fixedDialog by remember { mutableStateOf<FixedReserveItem?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("Car & fuel", style = MaterialTheme.typography.titleMedium)
        NumField(price, "Fuel price (RM / litre)", {
            price = it
            it.toDoubleOrNull()?.let { v -> onChange(s.copy(fuelPricePerLitre = v)) }
        })
        NumField(l100, "Fuel economy (L / 100 km)", {
            l100 = it
            it.toDoubleOrNull()?.let { v -> onChange(s.copy(litresPer100Km = v)) }
        })
        NumField(days, "Working days per month", {
            days = it
            it.toIntOrNull()?.takeIf { v -> v > 0 }?.let { v -> onChange(s.copy(workingDaysPerMonth = v)) }
        })
        Text(
            "Fuel ${rm(ProfitEngine.fuelCostPerKm(s))}/km  •  Wear ${rm(ProfitEngine.wearPerKm(s))}/km  •  Fixed ${rm(ProfitEngine.fixedPerDay(s))}/day",
            fontSize = 12.sp
        )

        // Wear & tear
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Wear & tear items", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { wearDialog = WearTearItem(0, "", 0.0, 0.0) }) {
                Icon(Icons.Filled.Add, null); Text(" Add")
            }
        }
        s.wearItems.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        Text("${rm(item.estimatedCost)} every ${fmtNum(item.intervalKm)} km  →  " +
                            String.format(Locale.US, "RM %.3f/km", item.estimatedCost / item.intervalKm),
                            fontSize = 12.sp)
                    }
                    IconButton(onClick = { wearDialog = item }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { onChange(s.copy(wearItems = s.wearItems - item)) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }

        // Fixed reserves
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Monthly fixed sinking funds", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { fixedDialog = FixedReserveItem(0, "", 0.0) }) {
                Icon(Icons.Filled.Add, null); Text(" Add")
            }
        }
        s.fixedItems.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        Text("${rm(item.monthlyAmount)} / month", fontSize = 12.sp)
                    }
                    IconButton(onClick = { fixedDialog = item }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { onChange(s.copy(fixedItems = s.fixedItems - item)) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }
    }

    wearDialog?.let { cur ->
        ItemDialog(
            title = if (cur.id == 0L) "Add wear & tear item" else "Edit wear & tear item",
            name0 = cur.name, label1 = "Replace every (km)", v1 = cur.intervalKm,
            label2 = "Estimated cost (RM)", v2 = cur.estimatedCost,
            onDismiss = { wearDialog = null },
            onSave = { n, km, cost ->
                val saved = WearTearItem(if (cur.id == 0L) System.currentTimeMillis() else cur.id, n, km, cost)
                onChange(
                    s.copy(
                        wearItems = if (cur.id == 0L) s.wearItems + saved
                        else s.wearItems.map { if (it.id == cur.id) saved else it }
                    )
                )
                wearDialog = null
            }
        )
    }

    fixedDialog?.let { cur ->
        ItemDialog(
            title = if (cur.id == 0L) "Add fixed reserve" else "Edit fixed reserve",
            name0 = cur.name, label1 = "Monthly amount (RM)", v1 = cur.monthlyAmount,
            label2 = null, v2 = 0.0,
            onDismiss = { fixedDialog = null },
            onSave = { n, amt, _ ->
                val saved = FixedReserveItem(if (cur.id == 0L) System.currentTimeMillis() else cur.id, n, amt)
                onChange(
                    s.copy(
                        fixedItems = if (cur.id == 0L) s.fixedItems + saved
                        else s.fixedItems.map { if (it.id == cur.id) saved else it }
                    )
                )
                fixedDialog = null
            }
        )
    }
}

@Composable
fun ItemDialog(
    title: String, name0: String,
    label1: String, v1: Double,
    label2: String?, v2: Double,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double) -> Unit
) {
    var name by remember { mutableStateOf(name0) }
    var a by remember { mutableStateOf(if (v1 == 0.0) "" else fmtNum(v1)) }
    var b by remember { mutableStateOf(if (v2 == 0.0) "" else fmtNum(v2)) }

    val valid = name.isNotBlank() &&
        (a.toDoubleOrNull() ?: 0.0) > 0 &&
        (label2 == null || (b.toDoubleOrNull() ?: 0.0) > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                NumField(a, label1, { a = it })
                if (label2 != null) NumField(b, label2, { b = it })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(name.trim(), a.toDouble(), b.toDoubleOrNull() ?: 0.0)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
