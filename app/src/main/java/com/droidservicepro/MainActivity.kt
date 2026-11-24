package com.droidservicepro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Bg = Color(0xFF0B1018)
private val Panel = Color(0xFF151E2B)
private val Accent = Color(0xFF55A7FF)
private const val USB_PERMISSION = "com.droidservicepro.USB_PERMISSION"

private enum class Status(val ru: String, val en: String) {
    READY("Подключите телефон с включённой отладкой по USB.", "Connect a phone with USB debugging enabled."),
    PERMISSION("Разрешите приложению доступ к USB на телефоне-хосте.", "Allow USB access on the host phone."),
    DENIED("Доступ к USB не предоставлен.", "USB access was denied."),
    CONNECTING("Подключение по USB ADB…", "Connecting over USB ADB…"),
    AUTHORIZE("Разблокируйте подключённый телефон и подтвердите RSA-запрос.", "Unlock the connected phone and approve the RSA prompt."),
    DONE("Диагностика USB ADB завершена.", "USB ADB diagnostics complete."),
    DETACHED("Телефон отключён.", "Phone disconnected."),
    INTERFACE("Интерфейс ADB не найден. Проверьте отладку по USB.", "ADB interface missing. Check USB debugging."),
    OPEN("Не удалось открыть интерфейс ADB.", "Could not open the ADB interface."),
    TRANSFER("Обмен по USB прерван. Проверьте кабель и повторите.", "USB transfer interrupted. Check the cable and retry."),
    AUTHORIZATION("Нет подтверждения RSA. Разблокируйте телефон и повторите.", "RSA approval missing. Unlock the target and retry."),
    PROTOCOL("Неожиданный ответ ADB.", "Unexpected ADB response."),
    SHELL("Устройство не вернуло свойства Android.", "The device did not return Android properties."),
    KEY("Не удалось использовать ключ ADB.", "Could not use the ADB key."),
    ERROR("Ошибка локального подключения.", "Local connection error.")
}

class MainActivity : ComponentActivity() {
    private val usb by lazy { requireNotNull(getSystemService(UsbManager::class.java)) }
    private var devices by mutableStateOf(emptyList<UsbDevice>())
    private var status by mutableStateOf(Status.READY)
    private var busy by mutableStateOf(false)
    private var report by mutableStateOf<DeviceReport?>(null)
    private val log = mutableStateListOf<Pair<Date, Status>>()
    private var pendingDevice: String? = null
    private var currentDevice: String? = null
    private var pendingProbe = false
    private var modemReport by mutableStateOf<UsbModemReport?>(null)
    private var pendingCsc: String? = null
    private var cscChangeReport by mutableStateOf<UsbCscChangeReport?>(null)

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scan()
            if (intent.action != USB_PERMISSION) return
            val device = devices.firstOrNull { it.deviceName == pendingDevice } ?: return
            val request = intent.data?.lastPathSegment
            if (request != device.deviceId.toString()) return
            pendingDevice = null
            if (usb.hasPermission(device)) {
                when {
                    pendingProbe -> probeModem(device)
                    pendingCsc != null -> {
                        val csc = pendingCsc
                        pendingCsc = null
                        if (csc != null) applyCsc(device, csc)
                    }
                    else -> diagnose(device)
                }
            } else recordStatus(Status.DENIED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, usbEvents, IntentFilter(USB_PERMISSION).apply {
            addDataScheme("droidservicepro")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbEvents, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        setContent {
            var russian by remember { mutableStateOf(prefs.getBoolean("russian", java.util.Locale.getDefault().language == "ru")) }
            App(russian) {
                russian = !russian
                prefs.edit().putBoolean("russian", russian).apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scan()
    }

    override fun onDestroy() {
        unregisterReceiver(usbEvents)
        super.onDestroy()
    }

    private fun recordStatus(value: Status) {
        status = value
        log.add(Date() to value)
        if (log.size > 100) log.removeAt(0)
    }

    private fun scan() {
        devices = usb.deviceList.values.sortedBy { it.deviceName }
        if (currentDevice != null && devices.none { it.deviceName == currentDevice }) {
            currentDevice = null
            pendingDevice = null
            report = null
            recordStatus(Status.DETACHED)
        }
    }

    private fun connect(device: UsbDevice) {
        pendingProbe = false
        pendingCsc = null
        if (busy || pendingDevice != null) return
        report = null
        currentDevice = device.deviceName
        if (usb.hasPermission(device)) {
            diagnose(device)
            return
        }
        pendingDevice = device.deviceName
        recordStatus(Status.PERMISSION)
        try {
            val intent = Intent(USB_PERMISSION).setPackage(packageName)
                .setData(android.net.Uri.parse("droidservicepro://usb/${device.deviceId}"))
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            usb.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId, intent, flags))
        } catch (_: RuntimeException) {
            pendingDevice = null
            recordStatus(Status.ERROR)
        }
    }

    private fun requestModemProbe(device: UsbDevice) {
        pendingProbe = true
        currentDevice = device.deviceName
        if (usb.hasPermission(device)) {
            probeModem(device)
            return
        }
        pendingDevice = device.deviceName
        try {
            val intent = Intent(USB_PERMISSION).setPackage(packageName)
                .setData(android.net.Uri.parse("droidservicepro://usb/${device.deviceId}"))
            val pending = PendingIntent.getBroadcast(
                this, device.deviceId, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            usb.requestPermission(device, pending)
        } catch (_: RuntimeException) {
            pendingProbe = false
            modemReport = UsbModemReport("", null, "USB permission request failed")
        }
    }

    private fun probeModem(device: UsbDevice) {
        pendingProbe = false
        modemReport = null
        busy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { probeUsbModem(usb, device) }
                if (!isDestroyed && currentDevice == device.deviceName) modemReport = result
            } catch (_: Exception) {
                if (!isDestroyed && currentDevice == device.deviceName) {
                    modemReport = UsbModemReport("", null, "USB modem probe failed")
                }
            } finally {
                busy = false
            }
        }
    }

    private fun requestCscChange(device: UsbDevice, csc: String) {
        if (busy || report?.supportedCscs?.contains(csc) != true) return
        if (usb.hasPermission(device)) {
            applyCsc(device, csc)
            return
        }
        pendingProbe = false
        pendingCsc = csc
        pendingDevice = device.deviceName
        val intent = Intent(USB_PERMISSION).setPackage(packageName)
            .setData(android.net.Uri.parse("droidservicepro://usb/${device.deviceId}"))
        val pending = PendingIntent.getBroadcast(
            this, device.deviceId, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        usb.requestPermission(device, pending)
    }

    private fun applyCsc(device: UsbDevice, csc: String) {
        if (report?.supportedCscs?.contains(csc) != true) return
        cscChangeReport = null
        busy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { changeUsbCsc(usb, device, csc) }
                if (!isDestroyed && currentDevice == device.deviceName) cscChangeReport = result
            } catch (_: Exception) {
                if (!isDestroyed && currentDevice == device.deviceName) {
                    cscChangeReport = UsbCscChangeReport("CSC operation failed")
                }
            } finally {
                busy = false
            }
        }
    }

    private fun diagnose(device: UsbDevice) {
        busy = true
        recordStatus(Status.CONNECTING)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    readUsbDiagnostics(usb, device) {
                        runOnUiThread {
                            if (!isDestroyed && currentDevice == device.deviceName) recordStatus(Status.AUTHORIZE)
                        }
                    }
                }
                scan()
                if (currentDevice == device.deviceName) {
                    report = result
                    recordStatus(Status.DONE)
                }
            } catch (error: AdbException) {
                scan()
                if (currentDevice == device.deviceName) recordStatus(Status.valueOf(error.reason.name))
            } catch (_: SecurityException) {
                scan()
                if (currentDevice == device.deviceName) recordStatus(Status.DENIED)
            } catch (_: java.io.IOException) {
                if (currentDevice == device.deviceName) recordStatus(Status.ERROR)
            } finally {
                busy = false
            }
        }
    }

    @Composable
    private fun App(ru: Boolean, toggleLanguage: () -> Unit) {
        fun t(a: String, b: String) = if (ru) a else b
        fun label(value: Status) = t(value.ru, value.en)
        var page by rememberSaveable { mutableIntStateOf(0) }
        var selected by rememberSaveable { mutableStateOf("EUX") }
        var query by rememberSaveable { mutableStateOf("") }
        var confirmCscChange by remember { mutableStateOf(false) }
        val pages = listOf(t("Главная", "Home"), t("Телефон", "Device"), "CSC", t("Журнал", "Log"))
        val icons = listOf(Icons.Default.Home, Icons.Default.Phone, Icons.Default.Place, Icons.Default.List)
        val current = report
        val unknown = t("Не определено", "Unknown")
        var showCscProperties by remember(current) { mutableStateOf(false) }
        val active = current?.activeCsc
        val cscSummary = listOf(
            t("Активный CSC", "Active CSC") to active,
            t("Код продаж", "Sales code") to current?.salesCsc,
            "Multi-CSC" to current?.multiCsc
        ).joinToString("\n") { (label, reading) ->
            val value = reading?.let { "${it.code} [${it.property}]" } ?: unknown
            "$label: $value"
        }
        val selectedUsbDevice = devices.firstOrNull { it.deviceName == currentDevice }

        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        pages.forEachIndexed { index, name ->
                            NavigationBarItem(
                                selected = page == index, onClick = { page = index },
                                icon = { Icon(icons[index], contentDescription = null) }, label = { Text(name) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("DroidService Pro", style = MaterialTheme.typography.headlineSmall)
                            Text("v0.2.6")
                        }
                        TextButton(onClick = toggleLanguage) { Text(t("EN", "RU")) }
                    }
                    when (page) {
                        0 -> {
                            Info(t("Диагностика Samsung", "Samsung diagnostics"), label(status))
                            Button(onClick = { page = 1 }) { Text(t("Подключение USB ADB", "USB ADB connection")) }
                            OutlinedButton(onClick = { page = 2 }) { Text(t("Справочник CSC", "CSC directory")) }
                        }
                        1 -> {
                            Info(t("Телефон-хост", "Host phone"),
                                "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}\n${Build.DISPLAY}")
                            Info("USB ADB", label(status))
                            OutlinedButton(onClick = ::scan) { Text(t("Обновить список USB", "Refresh USB list")) }
                            devices.forEach { device ->
                                val adb = adbInterface(device) != null
                                val ids = "VID %04X · PID %04X".format(device.vendorId, device.productId)
                                OutlinedButton(onClick = { requestModemProbe(device) }, enabled = !busy) {
                                    Text(t("Проверить USB-модем", "Probe USB modem") + " · $ids")
                                }
                                if (modemReport != null && currentDevice == device.deviceName) {
                                    val details = modemReport?.response?.let { "\nОтвет: $it" } ?: ""
                                    Info(t("Проверка AT-модема", "AT modem probe"),
                                        "${modemReport?.note}$details\n\n${modemReport?.interfaces}")
                                }
                                if (adb) {
                                    Button(onClick = { connect(device) }, enabled = !busy && status != Status.PERMISSION) {
                                        Text(t("Читать через ADB", "Read over ADB") + " · $ids · #${device.deviceId}")
                                    }
                                } else {
                                    Text("$ids · " + t("Интерфейс ADB отсутствует", "No ADB interface"))
                                }
                            }
                            if (current != null) {
                                Info(t("Подключённый телефон", "Connected phone"), listOf(
                                    t("Производитель", "Manufacturer") to current.manufacturer,
                                    t("Модель", "Model") to current.model,
                                    "Android" to current.android,
                                    t("Сборка", "Build") to current.build,
                                    t("Модем", "Baseband") to current.baseband
                                ).joinToString("\n") { (key, value) -> "$key: ${value ?: unknown}" })
                                Info("CSC", cscSummary)
                                OutlinedButton(onClick = { showCscProperties = !showCscProperties }) {
                                    Text(if (showCscProperties) t("Скрыть свойства CSC", "Hide CSC properties")
                                        else t("Показать свойства CSC", "Show CSC properties"))
                                }
                                if (showCscProperties) {
                                    val properties = current.cscProperties.entries.joinToString("\n") { (name, raw) ->
                                        val value = when {
                                            raw == null -> t("нет в ответе", "not returned")
                                            raw.isEmpty() -> t("пусто", "empty")
                                            else -> raw
                                        }
                                        "$name = $value"
                                    }
                                    Info(t("Свойства CSC из ADB", "CSC properties from ADB"), properties)
                                }
                            }
                            Info("Wi-Fi ADB", t(
                                "На телефоне включите «Беспроводная отладка» → «Сопряжение устройства с помощью кода». На компьютере в той же сети выполните adb pair IP:порт_сопряжения и введите код с телефона.\n\nПри необходимости выполните adb connect IP:порт_отладки. Диагностика в приложении доступна по USB.",
                                "On the target, open Wireless debugging → Pair device with pairing code. On a computer on the same network, run adb pair IP:pairing_port and enter the code shown on the phone.\n\nIf needed, run adb connect IP:debugging_port. This app runs diagnostics over USB."
                            ))
                        }
                        2 -> {
                            if (current != null) Info(t("Данные подключённого телефона", "Connected phone"), cscSummary)
                            val supported = current?.supportedCscs.orEmpty()
                            val supportNote = when (current?.supportedCscSource) {
                                CscListSource.FIRMWARE -> t("Список считан из прошивки через ADB.", "List read from this firmware over ADB.")
                                CscListSource.PUBLIC_REFERENCE -> t(
                                    "Справочный список для ${current.multiCsc?.code}; состав может отличаться в этой прошивке.",
                                    "Public reference list for ${current.multiCsc?.code}; this firmware may differ."
                                )
                                else -> t("Multi-CSC не распознан, список недоступен.", "Multi-CSC not recognized; list unavailable.")
                            }
                            Info(
                                t("CSC в прошивке", "CSC bundle on device"),
                                if (supported.isEmpty()) supportNote
                                else supportNote + "\n" + supported.joinToString(" · ")
                            )
                            OutlinedTextField(query, { query = it.take(32) }, Modifier.fillMaxWidth(),
                                singleLine = true, label = { Text(t("Поиск CSC", "Search CSC")) })
                            val filtered = cscCodes.filter { it.contains(query.trim(), ignoreCase = true) }
                            Text(t("Найдено", "Matches") + " ${filtered.size} / ${cscCodes.size}")
                            val selectedLabel = when (selected) {
                                "EUX" -> t("EUX · Европа", "EUX · Europe")
                                "XXV" -> t("XXV · Вьетнам", "XXV · Vietnam")
                                else -> selected
                            }
                            val assessment = when {
                                active?.code == selected -> t("Уже указан как активный CSC.", "Already reported as the active CSC.")
                                current == null -> t("Подключите телефон и прочитайте свойства через USB ADB.", "Connect the phone and read its properties over USB ADB.")
                                selected in supported && current.supportedCscSource == CscListSource.FIRMWARE -> t("Код найден в списке этой прошивки.", "Code found in this firmware's list.")
                                selected in supported && current.supportedCscSource == CscListSource.PUBLIC_REFERENCE -> t("Код есть в справочнике ${current.multiCsc?.code}; наличие именно в этой сборке не проверено.", "Code is in the ${current.multiCsc?.code} reference; this exact build is not verified.")
                                else -> t("Совместимость с этой прошивкой не подтверждена.", "Compatibility with this firmware is unverified.")
                            }
                            Info(t("Выбранный CSC", "Selected CSC"), "$selectedLabel\n$assessment")
                            Button(
                                onClick = { confirmCscChange = true },
                                enabled = !busy && selectedUsbDevice != null && selected in supported && active?.code != selected
                            ) {
                                Text(t("Сменить CSC на $selected и перезагрузить", "Change CSC to $selected and reboot"))
                            }
                            cscChangeReport?.let { result ->
                                Info(t("Результат смены CSC", "CSC change result"),
                                    result.note + (result.response?.let { "\n$it" } ?: ""))
                            }
                            filtered.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { code ->
                                        FilterChip(selected == code, { selected = code }, label = { Text(code) })
                                    }
                                }
                            }
                        }
                        3 -> Info(t("Журнал сессии", "Session log"), log.asReversed().joinToString("\n") { (time, event) ->
                            "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(time)} · ${label(event)}"
                        }.ifEmpty { t("Событий пока нет.", "No events yet.") })
                    }
                    if (confirmCscChange) {
                        AlertDialog(
                            onDismissRequest = { confirmCscChange = false },
                            title = { Text(t("Подтвердите смену CSC", "Confirm CSC change")) },
                            text = { Text(t(
                                "Для $selected будет отправлена последовательность Samsung AT-команд и запрошена перезагрузка. Метод неофициальный и не проверен на этой сборке. Возможны сброс настроек или проблемы с сетью и обновлениями. Сначала сделайте резервную копию.",
                                "Samsung AT commands will set $selected and request a reboot. This unofficial method has not been verified on this firmware. Settings may reset, and network or update behavior may change. Back up your data first."
                            )) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmCscChange = false
                                    selectedUsbDevice?.let { requestCscChange(it, selected) }
                                }) { Text(t("Продолжить", "Continue")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmCscChange = false }) { Text(t("Отмена", "Cancel")) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Info(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
