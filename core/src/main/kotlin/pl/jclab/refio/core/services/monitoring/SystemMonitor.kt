package pl.jclab.refio.core.services.monitoring

import pl.jclab.refio.core.services.logging.coreLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import kotlin.math.max

/**
 * System monitor for CPU and memory usage.
 * Platform-agnostic singleton (no IntelliJ dependencies).
 *
 * This is a core service - it does NOT depend on plugin infrastructure.
 */
object SystemMonitor {
    private val logger = coreLogger("SystemMonitor")
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val osBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()
    private val runtime = Runtime.getRuntime()

    // System metrics state
    data class SystemMetrics(
        val cpuUsagePercent: Double,
        val memoryUsedMb: Long,
        val memoryTotalMb: Long,
        val memoryUsagePercent: Double,
        val availableProcessors: Int
    )

    private val _metrics = MutableStateFlow(
        SystemMetrics(
            cpuUsagePercent = 0.0,
            memoryUsedMb = 0,
            memoryTotalMb = 0,
            memoryUsagePercent = 0.0,
            availableProcessors = Runtime.getRuntime().availableProcessors()
        )
    )
    val metrics: StateFlow<SystemMetrics> = _metrics.asStateFlow()

    // CPU monitoring
    private var lastCpuTime = 0L
    private var lastUpTime = 0L
    private var isMonitoring = false

    init {
        startMonitoring()
    }

    /**
     * Start monitoring system metrics
     */
    private fun startMonitoring() {
        if (isMonitoring) {
            logger.warn { "Monitoring already started" }
            return
        }

        isMonitoring = true

        // Update metrics every 2 seconds
        cs.launch {
            while (isActive) {
                try {
                    updateMetrics()
                    delay(2000)
                } catch (e: Exception) {
                    logger.error(e) { "Error updating system metrics" }
                    delay(5000) // Wait longer on error
                }
            }
        }

        logger.info { "System monitoring started" }
    }

    /**
     * Update system metrics
     */
    private fun updateMetrics() {
        try {
            // Memory metrics
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val maxMemory = runtime.maxMemory()

            val usedMb = usedMemory / (1024 * 1024)
            val maxMb = maxMemory / (1024 * 1024)
            val memoryPercent = (usedMemory.toDouble() / maxMemory.toDouble()) * 100.0

            // CPU metrics (approximation)
            val cpuPercent = estimateCpuUsage()

            _metrics.value = SystemMetrics(
                cpuUsagePercent = cpuPercent,
                memoryUsedMb = usedMb,
                memoryTotalMb = maxMb,
                memoryUsagePercent = memoryPercent,
                availableProcessors = runtime.availableProcessors()
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to update metrics" }
        }
    }

    /**
     * Estimate CPU usage (approximation using thread CPU time)
     * Note: This is an approximation and may not be 100% accurate
     */
    private fun estimateCpuUsage(): Double {
        return try {
            val threadMXBean = ManagementFactory.getThreadMXBean()
            val threadIds = threadMXBean.allThreadIds

            // Calculate total CPU time for all threads
            var totalCpuTime = 0L
            for (threadId in threadIds) {
                val threadInfo = threadMXBean.getThreadCpuTime(threadId)
                if (threadInfo > 0) {
                    totalCpuTime += threadInfo
                }
            }

            // Calculate CPU usage based on time delta
            val upTime = ManagementFactory.getRuntimeMXBean().uptime * 1_000_000 // Convert to nanoseconds
            val cpuTimeDelta = totalCpuTime - lastCpuTime
            val upTimeDelta = upTime - lastUpTime

            lastCpuTime = totalCpuTime
            lastUpTime = upTime

            if (upTimeDelta > 0 && cpuTimeDelta >= 0) {
                val usage = (cpuTimeDelta.toDouble() / upTimeDelta.toDouble()) * 100.0
                // Normalize by number of processors
                val normalizedUsage = usage / runtime.availableProcessors()
                // Cap at 100%
                max(0.0, normalizedUsage.coerceAtMost(100.0))
            } else {
                0.0
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to estimate CPU usage" }
            0.0
        }
    }

    /**
     * Get current memory info
     */
    fun getMemoryInfo(): MemoryInfo {
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        return MemoryInfo(
            usedMb = usedMemory / (1024 * 1024),
            freeMb = freeMemory / (1024 * 1024),
            totalMb = totalMemory / (1024 * 1024),
            maxMb = maxMemory / (1024 * 1024),
            usagePercent = (usedMemory.toDouble() / maxMemory.toDouble()) * 100.0
        )
    }

    /**
     * Force garbage collection (for testing/debugging)
     */
    fun forceGC() {
        logger.info { "Forcing garbage collection..." }
        System.gc()
        logger.info { "Garbage collection completed" }
    }

    /**
     * Stop monitoring
     */
    fun dispose() {
        isMonitoring = false
        cs.cancel()
        logger.info { "System monitoring stopped" }
    }
}

/**
 * Memory information snapshot
 */
data class MemoryInfo(
    val usedMb: Long,
    val freeMb: Long,
    val totalMb: Long,
    val maxMb: Long,
    val usagePercent: Double
)
