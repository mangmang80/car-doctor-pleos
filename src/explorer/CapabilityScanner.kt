package ai.pleos.playground.sample.vehicle.explorer

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Local diagnostic helper (not part of the official sample).
 *
 * Phase 1: calls every public `check...Capabilities` request once and logs
 *   SCAN|<DOMAIN>|<method>|<onComplete|onFailed|EXCEPTION|SKIPPED>|<detail>
 * Phase 2: calls a small allow-list of read-only `get...` requests (using the areas that
 * phase 1 reported) and logs
 *   READ|<DOMAIN>|<method>|<area>|<onComplete|onFailed|EXCEPTION|SKIPPED>|<value or error>
 *
 * Read with: adb logcat -d -s PleosScan:I
 *
 * It never calls set/move/register APIs and honors PublicApiPolicy
 * (blocked or deprecated APIs are not called).
 */
class CapabilityScanner(private val vehicle: Vehicle) {

    private val handler = Handler(Looper.getMainLooper())
    private val areasByDomain = mutableMapOf<String, MutableList<Any>>()

    private data class Target(val domain: String, val apiClass: KClass<*>, val instance: Any)

    fun start() {
        val targets = mutableListOf<Target>()
        VehiclePublicApiCatalog.domains.forEach { (domain, apiClass) ->
            val instance = runCatching {
                vehicle.javaClass.getMethod("get" + apiClass.simpleName).invoke(vehicle)
            }.getOrNull()
            if (instance == null) {
                Log.i(TAG, "SCAN|${domain.name}|-|EXCEPTION|no accessor for ${apiClass.simpleName}")
            } else {
                targets += Target(domain.name, apiClass, instance)
            }
        }

        val checks = targets.flatMap { target ->
            target.apiClass.declaredMemberFunctions
                .filter { it.name.startsWith("check") && PublicApiPolicy.isSupported(it) }
                .sortedBy { it.name }
                .map { Triple(target, it, null as Any?) }
        }
        Log.i(TAG, "SCAN_START|total=${checks.size}")
        checks.forEachIndexed { index, (target, function, _) ->
            handler.postDelayed({ invoke("SCAN", target, function, null) }, index * STEP_MS)
        }
        val phaseOneEnd = checks.size * STEP_MS + FINISH_WAIT_MS
        handler.postDelayed({
            Log.i(TAG, "SCAN_DONE|total=${checks.size}")
            startReads(targets)
        }, phaseOneEnd)
    }

    private fun startReads(targets: List<Target>) {
        var delay = 0L
        var count = 0
        targets.forEach { target ->
            val allow = READ_ALLOW[target.domain] ?: return@forEach
            target.apiClass.declaredMemberFunctions
                .filter { it.name.startsWith("get") && PublicApiPolicy.isSupported(it) && allow.matches(it.name) }
                .sortedBy { it.name }
                .forEach { function ->
                    val areaParameter = function.parameters.firstOrNull {
                        it.kind == KParameter.Kind.VALUE && (it.type.classifier as? KClass<*>) in AREA_CLASSES
                    }
                    if (areaParameter == null) {
                        count++
                        handler.postDelayed({ invoke("READ", target, function, null) }, delay)
                        delay += STEP_MS
                    } else {
                        val areaClass = areaParameter.type.classifier as KClass<*>
                        val areas = areasByDomain[target.domain].orEmpty()
                            .filter { areaClass.isInstance(it) }
                            .distinct()
                            .take(MAX_AREAS)
                        if (areas.isEmpty()) {
                            Log.i(TAG, "READ|${target.domain}|${function.name}|-|SKIPPED|no area reported by capability check")
                        }
                        areas.forEach { area ->
                            count++
                            handler.postDelayed({ invoke("READ", target, function, area) }, delay)
                            delay += STEP_MS
                        }
                    }
                }
        }
        Log.i(TAG, "READ_START|total=$count")
        handler.postDelayed({ Log.i(TAG, "READ_DONE|total=$count") }, delay + FINISH_WAIT_MS)
    }

    private fun invoke(kind: String, target: Target, function: KFunction<*>, area: Any?) {
        val areaText = area?.toString() ?: "-"
        val prefix = if (kind == "READ") "READ|${target.domain}|${function.name}|$areaText" else "SCAN|${target.domain}|${function.name}"
        try {
            val arguments = mutableMapOf<KParameter, Any?>()
            for (parameter in function.parameters) {
                if (parameter.kind == KParameter.Kind.INSTANCE) {
                    arguments[parameter] = target.instance
                    continue
                }
                val classifier = parameter.type.classifier as? KClass<*>
                if (classifier != null && classifier in AREA_CLASSES && area != null) {
                    arguments[parameter] = area
                    continue
                }
                val callback = classifier?.let { callbackFor(it, target.domain, prefix, parameter.name.orEmpty()) }
                if (callback == null) {
                    Log.i(TAG, "$prefix|SKIPPED|unsupported parameter ${parameter.name}: ${parameter.type}")
                    return
                }
                arguments[parameter] = callback
            }
            function.callBy(arguments)
        } catch (exception: Exception) {
            val cause = (exception as? InvocationTargetException)?.targetException ?: exception
            Log.i(TAG, "$prefix|EXCEPTION|${cause.message ?: cause.javaClass.simpleName}")
        }
    }

    private fun rememberAreas(domain: String, values: List<Any?>) {
        val found = mutableListOf<Any>()
        values.forEach { value ->
            when (value) {
                is Map<*, *> -> value.keys.forEach { key -> key?.let { found += it } }
                is Iterable<*> -> value.forEach { item -> item?.let { found += it } }
            }
        }
        val areas = found.filter { candidate -> AREA_CLASSES.any { it.isInstance(candidate) } }
        if (areas.isNotEmpty()) {
            areasByDomain.getOrPut(domain) { mutableListOf() }.addAll(areas)
        }
    }

    private fun callbackFor(classifier: KClass<*>, domain: String, prefix: String, parameterName: String): Any? {
        val status = if (parameterName.contains("fail", ignoreCase = true)) "onFailed" else "onComplete"
        fun report(values: List<Any?>) {
            val config = values.filterIsInstance<CapabilityConfig>().firstOrNull()
            val areas = values.filterIsInstance<Map<*, *>>().firstOrNull()?.keys
            val throwable = values.firstOrNull() as? Throwable
            if (status == "onComplete") rememberAreas(domain, values)
            val detail = when {
                throwable != null -> throwable.message ?: throwable.javaClass.simpleName
                config != null -> {
                    val listAreas = values.filterIsInstance<List<*>>().firstOrNull()
                    val areaText = areas?.joinToString(",") ?: listAreas?.joinToString(",").orEmpty()
                    "access=${config.accessType} areas=${areaText.take(300)}"
                }
                else -> values.joinToString { it.toString() }.take(300)
            }
            Log.i(TAG, "$prefix|$status|$detail")
        }
        return when (classifier) {
            Function0::class -> {
                val callback: () -> Unit = { report(emptyList()) }
                callback
            }
            Function1::class -> {
                val callback: (Any?) -> Unit = { first -> report(listOf(first)) }
                callback
            }
            Function2::class -> {
                val callback: (Any?, Any?) -> Unit = { first, second -> report(listOf(first, second)) }
                callback
            }
            Function3::class -> {
                val callback: (Any?, Any?, Any?) -> Unit = { first, second, third ->
                    report(listOf(first, second, third))
                }
                callback
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "PleosScan"
        private const val STEP_MS = 120L
        private const val FINISH_WAIT_MS = 5000L
        private const val MAX_AREAS = 6

        private val AREA_CLASSES: Set<KClass<*>> = setOf(
            DoorArea::class,
            MirrorArea::class,
            SeatArea::class,
            WheelArea::class,
            WindowArea::class
        )

        // Read-only getters worth sampling for the proposed service. Nothing here changes vehicle state.
        private val READ_ALLOW: Map<String, Regex> = mapOf(
            "LIGHT" to Regex("get.*"),
            "WIPER" to Regex("get.*"),
            "WINDOW" to Regex("getCurrentWindowPosition|getCurrentWindowLockState"),
            "TIRE" to Regex("getLowTirePressure"),
            "TURN_SIGNAL" to Regex("get.*"),
            "ODOMETER" to Regex("getCurrentDistanceDriven"),
            "EV_BATTERY" to Regex("getEvBatteryStateOfCharge"),
            "BRAKE" to Regex("get.*"),
            "STEERING_WHEEL" to Regex("getCurrentSteeringWheelAngle"),
            "DOOR" to Regex("getCurrentDoorPosition|getDoorLockState"),
            "SEAT" to Regex("getCurrentSeatOccupancy|getCurrentSeatBeltBuckledState"),
            "HVAC" to Regex("getHvacTemperatureCurrent|getHvacPowerState|getHvacFanSpeed")
        )
    }
}
