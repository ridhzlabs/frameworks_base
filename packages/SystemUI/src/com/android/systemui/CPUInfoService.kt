/*
 * Copyright (C) 2019-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CPUInfoService @Inject constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Main private val handler: Handler
) : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var cpuInfoView: TextView
    private lateinit var configuration: Configuration
    private lateinit var coroutineScope: CoroutineScope

    private var readJob: kotlinx.coroutines.Job? = null
    private var observerRegistered = false
    private var cpuList: List<String> = emptyList()
    private var cpuPaths: Map<String, CpuPaths> = emptyMap()
    private var cpuTempDivider = 1
    private var cpuTempSensor = ""
    private var cpuDisplayString = ""

    private val fileMap = mutableMapOf<String, RandomAccessFile>()
    private val failedPaths = mutableSetOf<String>()

    private data class CpuPaths(
        val freq: String,
        val governor: String?,
        val policyFreq: String?
    )

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            stopReading()
        }

        override fun onStartedWakingUp() {
            startReading()
        }
    }

    override fun onCreate() {
        super.onCreate()
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        windowManager = getSystemService(WindowManager::class.java)!!
        configuration = resources.configuration
        layoutParams.y = getTopInset()

        cpuTempDivider = resources.getInteger(R.integer.config_cpuTempDivider).coerceAtLeast(1)
        cpuTempSensor = getString(R.string.config_cpuTempSensor)
        cpuDisplayString = getString(R.string.config_displayCpus)

        cpuList = parseCpuList(cpuDisplayString)
        cpuPaths = cpuList.associateWith { resolveCpuPaths(it) }
        cpuTempSensor = resolveTempSensor(cpuTempSensor)

        cpuInfoView = TextView(this).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, BACKGROUND_ALPHA))
            setTextColor(Color.WHITE)
            val padding = resources.getDimensionPixelSize(R.dimen.fps_info_text_padding)
            setPadding(padding, padding, padding, padding)
        }
    }

    override fun onStartCommand(intent: Intent?, startId: Int, flags: Int): Int {
        if (!observerRegistered) {
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
            observerRegistered = true
        }
        startReading()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (configuration.orientation != newConfig.orientation) {
            layoutParams.y = getTopInset()
            if (cpuInfoView.parent != null)
                windowManager.updateViewLayout(cpuInfoView, layoutParams)
        }
        configuration = newConfig
    }

    private fun getTopInset(): Int = windowManager.currentWindowMetrics
        .windowInsets.getInsets(WindowInsets.Type.statusBars()).top

    private fun parseCpuList(displayCpus: String): List<String> {
        if (displayCpus.isNotBlank()) {
            return displayCpus.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .distinct()
                .sorted()
                .map { it.toString() }
        }

        val spec = readFirstLine(File("/sys/devices/system/cpu/present"))
            ?: readFirstLine(File("/sys/devices/system/cpu/possible"))
        val parsed = spec?.let { parseCpuRanges(it) }.orEmpty()
        if (parsed.isNotEmpty()) return parsed

        val fromDirs = File("/sys/devices/system/cpu")
            .listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { it.name.removePrefix("cpu").toIntOrNull() }
            ?.distinct()
            ?.sorted()
            ?.map { it.toString() }
            .orEmpty()
        return fromDirs.ifEmpty { listOf("0") }
    }

    private fun parseCpuRanges(spec: String): List<String> {
        val cpus = mutableListOf<Int>()
        spec.trim().split(",").forEach { part ->
            val bounds = part.trim().split("-")
            when (bounds.size) {
                1 -> bounds[0].toIntOrNull()?.let { cpus.add(it) }
                2 -> {
                    val first = bounds[0].toIntOrNull()
                    val last = bounds[1].toIntOrNull()
                    if (first != null && last != null && first <= last) {
                        cpus.addAll(first..last)
                    }
                }
            }
        }
        return cpus.distinct().sorted().map { it.toString() }
    }

    private fun resolveCpuPaths(cpu: String): CpuPaths {
        val perCpuBase = "/sys/devices/system/cpu/cpu$cpu/cpufreq"
        val perCpuFreq = "$perCpuBase/scaling_cur_freq"
        val perCpuGovernor = "$perCpuBase/scaling_governor"
        val policy = findPolicyForCpu(cpu)
        val policyFreq = policy?.let { "$it/scaling_cur_freq" }
        val policyGovernor = policy?.let { "$it/scaling_governor" }

        return CpuPaths(
            freq = if (File(perCpuFreq).exists()) perCpuFreq else (policyFreq ?: perCpuFreq),
            governor = if (File(perCpuGovernor).exists()) perCpuGovernor else policyGovernor,
            policyFreq = policyFreq
        )
    }

    private fun findPolicyForCpu(cpu: String): String? {
        val policies = File("/sys/devices/system/cpu/cpufreq")
            .listFiles { file -> file.isDirectory && file.name.startsWith("policy") }
            ?: return null
        policies.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { policy ->
                val related = readFirstLine(File(policy, "related_cpus"))
                    ?: readFirstLine(File(policy, "affected_cpus"))
                    ?: return@forEach
                if (related.split(Regex("\\s+")).contains(cpu)) {
                    return policy.absolutePath
                }
            }
        return null
    }

    private fun resolveTempSensor(configured: String): String {
        if (configured.isNotBlank() && readFirstLine(File(configured)) != null) {
            return configured
        }

        val zones = File("/sys/class/thermal")
            .listFiles { file -> file.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            .orEmpty()
        val cpuZone = zones.firstOrNull { zone ->
            val type = readFirstLine(File(zone, "type"))?.lowercase().orEmpty()
            isCpuThermalZone(type) && readFirstLine(File(zone, "temp")) != null
        }
        return cpuZone?.let { File(it, "temp").absolutePath } ?: configured
    }

    private fun isCpuThermalZone(type: String): Boolean {
        if (type.isBlank()) return false
        if (listOf("gpu", "battery", "charger", "pmic", "usb", "display").any { type.contains(it) }) {
            return false
        }
        return listOf("cpu", "soc", "tscpu", "ap", "cluster").any { type.contains(it) }
    }

    private fun readFirstLine(file: File): String? {
        return try {
            file.bufferedReader().use { it.readLine()?.trim() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun startReading() {
        if (!::cpuInfoView.isInitialized) return
        readJob?.cancel()
        if (cpuInfoView.parent == null) {
            try {
                windowManager.addView(cpuInfoView, layoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add CPU info overlay: ${e.message}")
            }
        }
        readJob = coroutineScope.launch {
            while (isActive) {
                val output = buildCpuInfo()
                handler.post {
                    if (output.isBlank()) {
                        cpuInfoView.visibility = View.GONE
                    } else {
                        cpuInfoView.text = output
                        cpuInfoView.visibility = View.VISIBLE
                    }
                }
                delay(MEASURE_INTERVAL_DEFAULT)
            }
        }
    }

    private fun buildCpuInfo(): String {
        var hasReading = false
        val builder = StringBuilder()

        readTemperature()?.let { temperature ->
            hasReading = true
            builder.append("Temp: ${temperature}°C\n")
        }

        cpuList.forEach { cpu ->
            val paths = cpuPaths[cpu]
            var freq = paths?.let { readLineFromRandomAccessFile(it.freq)?.toIntOrNull() }
            val policyFreq = paths?.policyFreq
            if ((freq == null || freq <= 0) && policyFreq != null && policyFreq != paths?.freq) {
                freq = readLineFromRandomAccessFile(policyFreq)?.toIntOrNull()
            }

            if (freq == null) {
                builder.append("cpu$cpu: --\n")
                return@forEach
            }
            val currentFreq = freq
            if (currentFreq <= 0) {
                builder.append("cpu$cpu: offline\n")
                return@forEach
            }

            hasReading = true
            val mhz = currentFreq / 1000
            val governor = paths?.governor
                ?.let { readLineFromRandomAccessFile(it) }
                ?.takeIf { it.isNotBlank() }
            if (governor != null) {
                builder.append("cpu$cpu: $governor ${mhz}MHz\n")
            } else {
                builder.append("cpu$cpu: ${mhz}MHz\n")
            }
        }

        return if (hasReading) builder.toString().trim() else ""
    }

    private fun readTemperature(): Int? {
        val raw = readLineFromRandomAccessFile(cpuTempSensor)?.toIntOrNull() ?: return null
        if (raw <= 0) return null
        val scaled = if (cpuTempDivider > 1) {
            raw / cpuTempDivider
        } else {
            when {
                raw > 1000 -> raw / 1000
                raw > 200 -> raw / 10
                else -> raw
            }
        }
        return scaled.takeIf { it in 1..150 }
    }

    private fun stopReading() {
        readJob?.cancel()
        readJob = null
        removeViewIfNeeded()
    }

    private fun removeViewIfNeeded() {
        if (::cpuInfoView.isInitialized && cpuInfoView.parent != null) {
            windowManager.removeViewImmediate(cpuInfoView)
        }
    }

    private fun readLineFromRandomAccessFile(path: String): String? {
        if (path.isBlank() || path in failedPaths) return null
        val file = fileMap[path] ?: try {
            RandomAccessFile(path, "r").also { fileMap[path] = it }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot open $path: ${e.message}")
            failedPaths.add(path)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot open $path: ${e.message}")
            failedPaths.add(path)
            return null
        }

        return try {
            file.seek(0L)
            file.readLine()?.trim()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read from $path: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        stopReading()
        coroutineScope.cancel()
        fileMap.values.forEach {
            try { it.close() } catch (_: IOException) {}
        }
        fileMap.clear()
        failedPaths.clear()
        if (observerRegistered) {
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
            observerRegistered = false
        }
        removeViewIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        private const val TAG = "CPUInfoService"
        private const val MEASURE_INTERVAL_DEFAULT = 1000L
        private const val BACKGROUND_ALPHA = 120
    }
}
