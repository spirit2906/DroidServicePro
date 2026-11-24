package com.droidservicepro

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1018)
private val Panel = Color(0xFF151E2B)
private val Accent = Color(0xFF55A7FF)
private val Muted = Color(0xFF9BAABD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidServiceApp(this) }
    }
}

@Composable
private fun DroidServiceApp(context: Context) {
    var page by remember { mutableStateOf("home") }
    var usbStatus by remember { mutableStateOf("No USB device detected / USB-устройство не обнаружено") }
    var selectedCsc by remember { mutableStateOf("EUX") }
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val devices = remember { usbManager.deviceList.values.toList() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    NavigationBarItem(
                        selected = page == "home", onClick = { page = "home" },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(
                        selected = page == "device", onClick = { page = "device" },
                        icon = { Icon(Icons.Default.PhoneAndroid, null) }, label = { Text("Device") })
                    NavigationBarItem(
                        selected = page == "region", onClick = { page = "region" },
                        icon = { Icon(Icons.Default.Public, null) }, label = { Text("Region") })
                    NavigationBarItem(
                        selected = page == "logs", onClick = { page = "logs" },
                        icon = { Icon(Icons.Default.ReceiptLong, null) }, label = { Text("Logs") })
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DroidService Pro", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("SAMSUNG DEVICE SERVICE • v0.1.0", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Accent)
                }
                when (page) {
                    "home" -> {
                        Text("Dashboard / Панель управления", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard("USB OTG", "USB device discovery", Icons.Default.Usb, Modifier.weight(1f)) { page = "device" }
                            FeatureCard("Wi-Fi ADB", "Pairing guidance", Icons.Default.Wifi, Modifier.weight(1f)) { page = "device" }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureCard("CSC Checker", "Read-only information", Icons.Default.Public, Modifier.weight(1f)) { page = "region" }
                            FeatureCard("Diagnostics", "Device properties", Icons.Default.MonitorHeart, Modifier.weight(1f)) { page = "device" }
                        }
                        InfoCard("Safe operation / Безопасная работа",
                            "This starter version reads locally available device details and detects USB peripherals. CSC changes are not executed automatically. Use only supported official Samsung procedures and back up data first.\\n\\nЭта версия показывает доступные сведения и обнаруживает USB-устройства. Автоматическая смена CSC пока не выполняется.")
                    }
                    "device" -> {
                        Text("Device & connection / Устройство и подключение", color = Muted)
                        InfoCard("Host phone / Телефон-хост",
                            "Model: ${Build.MODEL}\\nManufacturer: ${Build.MANUFACTURER}\\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\\nBuild: ${Build.DISPLAY}")
                        InfoCard("USB OTG", usbStatus)
                        Button(onClick = {
                            val found = usbManager.deviceList.values
                            usbStatus = if (found.isEmpty()) {
                                "No USB peripherals detected. Connect a compatible device using OTG.\\nUSB-устройства не найдены."
                            } else found.joinToString("\\n") {
                                "USB device: VID ${it.vendorId}, PID ${it.productId}"
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan USB / Сканировать USB")
                        }
                        InfoCard("Wi-Fi ADB",
                            "On the Samsung device, enable Developer options → Wireless debugging, then use its system Pair device flow. This app currently provides guidance only; it does not silently pair or execute ADB commands.\\n\\nНа Samsung включите Для разработчиков → Беспроводная отладка и выполните сопряжение штатным способом.")
                    }
                    "region" -> {
                        Text("Region Changer / Смена региона", color = Muted)
                        InfoCard("CSC information / Информация CSC",
                            "Android apps cannot reliably read Samsung's active/original CSC on every model. Values below are a region reference, not a detected device CSC.\\n\\nПриложение не может гарантированно прочитать активный и исходный CSC на каждой модели.")
                        Text("Select target CSC / Выберите регион", fontWeight = FontWeight.SemiBold)
                        val regions = listOf(
                            "EUX" to "Europe / Европа",
                            "XEF" to "France / Франция",
                            "DBT" to "Germany / Германия",
                            "SER" to "Russia / Россия",
                            "SEK" to "Ukraine / Украина",
                            "INS" to "India / Индия",
                            "XSG" to "UAE / ОАЭ"
                        )
                        regions.forEach { (code, label) ->
                            Card(
                                onClick = { selectedCsc = code },
                                colors = CardDefaults.cardColors(containerColor = if (selectedCsc == code) Color(0xFF203B59) else Panel),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(code, fontWeight = FontWeight.Bold)
                                        Text(label, color = Muted, fontSize = 12.sp)
                                    }
                                    RadioButton(selected = selectedCsc == code, onClick = { selectedCsc = code })
                                }
                            }
                        }
                        InfoCard("Selected CSC / Выбранный CSC", selectedCsc)
                        InfoCard("Compatibility check / Проверка совместимости",
                            "Before any region operation, verify the exact model, sales code, multi-CSC package, carrier restrictions and firmware. Changing CSC may factory-reset the phone or affect features, updates and warranty. This build does not modify CSC.")
                        OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                            Text("Compatibility check — coming soon / Скоро")
                        }
                        Text("Official route: use Samsung-supported settings or firmware for the exact model. Service route: only documented, authorized procedures on compatible devices. No FRP bypass or account removal.", color = Muted, fontSize = 11.sp)
                    }
                    "logs" -> {
                        Text("Operation log / Журнал операций", color = Muted)
                        InfoCard("Session", "App started.\\nUSB scan is manual.\\nNo device changes have been made.")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("DroidService Pro • Prototype build", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                        modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.height(112.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = Accent)
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body.replace("\\\\n", "\\n"), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
