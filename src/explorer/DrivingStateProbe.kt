package ai.pleos.playground.sample.vehicle.explorer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Local diagnostic helper (not part of the official sample).
 *
 * Goal: find out whether an app can read the driving state (gear, parking brake, speed) that the
 * Pleos Vehicle SDK does not expose, by using the standard Android Automotive CarPropertyManager.
 * Everything is accessed by reflection so the project needs no android.car compile dependency.
 * Read-only: it never sets a property.
 *
 * Run:   adb shell am start -n ai.pleos.playground.sample.vehicle/.MainActivity --ez probe true
 * Read:  adb logcat -d -s PleosProbe:I
 *
 * Log format:
 *   PERM|<permission>|GRANTED|DENIED
 *   CAR|<step>|OK|FAIL|<detail>
 *   PROP|<name>|<hex id>|<REGISTERED|NOT_REGISTERED>|type=..|areas=..
 *   READ|<name>|area=<hex>|OK|value=..|status=..      or  READ|<name>|area=<hex>|FAIL|<error>
 *   VERDICT|drivingStateGate=USABLE|NOT_USABLE|parked=true|false|unknown|basis=..|speed=readable|unavailable
 *   EVENT|<name>|area=<hex>|value=..                  (live changes, see LISTEN_SECONDS)
 *   PROBE_DONE
 *
 * To see value changes, inject events while listening, e.g. (emulator shell):
 *   adb shell cmd car_service inject-vhal-event 0x11400400 0 4     (GEAR_SELECTION = PARK)
 * (the exact command depends on the emulator build; check `adb shell cmd car_service help`)
 */
class DrivingStateProbe(private val context: Context) {

    fun start() {
        Thread({
            try {
                run()
            } catch (throwable: Throwable) {
                log("CAR|fatal|FAIL|${describe(throwable)}")
            }
            log("PROBE_DONE")
        }, "DrivingStateProbe").start()
    }

    private fun run() {
        log("PROBE_START")
        PERMISSIONS.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            log("PERM|$permission|${if (granted) "GRANTED" else "DENIED"}")
        }

        val carClass = try {
            Class.forName("android.car.Car")
        } catch (throwable: Throwable) {
            log("CAR|load android.car.Car|FAIL|${describe(throwable)} (is <uses-library android.car> declared?)")
            return
        }
        log("CAR|load android.car.Car|OK|")

        val car = createCar(carClass) ?: return
        val manager = try {
            carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
        } catch (throwable: Throwable) {
            log("CAR|getCarManager(property)|FAIL|${describe(throwable)}")
            null
        }
        if (manager == null) {
            log("CAR|getCarManager(property)|FAIL|returned null")
            return
        }
        log("CAR|getCarManager(property)|OK|${manager.javaClass.simpleName}")

        val ids = propertyIds()
        val configs = registeredConfigs(manager)
        val readable = mutableListOf<Pair<String, Int>>()
        val readValues = mutableMapOf<String, Any?>()

        TARGET_NAMES.forEach { name ->
            val id = ids[name]
            if (id == null) {
                log("PROP|$name|-|NOT_REGISTERED|no constant in VehiclePropertyIds")
                return@forEach
            }
            val config = configs[id]
            if (config == null) {
                log("PROP|$name|${hex(id)}|NOT_REGISTERED|not in getPropertyList()")
                return@forEach
            }
            val type = runCatching { config.javaClass.getMethod("getPropertyType").invoke(config) as Class<*> }.getOrNull()
            val areas = runCatching { config.javaClass.getMethod("getAreaIds").invoke(config) as IntArray }
                .getOrDefault(intArrayOf(0))
            log("PROP|$name|${hex(id)}|REGISTERED|type=${type?.simpleName}|areas=${areas.joinToString { hex(it) }}")
            if (type == null) return@forEach

            areas.forEach { area ->
                try {
                    val value = manager.javaClass
                        .getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(manager, type, id, area)
                    val raw = value?.javaClass?.getMethod("getValue")?.invoke(value)
                    val status = value?.javaClass?.getMethod("getStatus")?.invoke(value)
                    log("READ|$name|area=${hex(area)}|OK|value=${format(name, raw)}|status=$status")
                    if (readable.none { it.second == id }) readable += name to id
                    readValues[name] = raw
                } catch (throwable: Throwable) {
                    log("READ|$name|area=${hex(area)}|FAIL|${describe(throwable)}")
                }
            }
        }

        logVerdict(readValues, ids.keys)
        listen(manager, readable)
    }

    /** One-line summary: can the app decide "parked / moving" from what it can read? */
    private fun logVerdict(values: Map<String, Any?>, known: Set<String>) {
        // GEAR_SELECTION (driver's selected gear) follows the Virtual Console immediately; CURRENT_GEAR may lag
        // (observed: selection=DRIVE while current stayed PARK). Parked only if every readable gear says PARK.
        val gear = values["GEAR_SELECTION"] as? Int ?: values["CURRENT_GEAR"] as? Int
        val gears = listOfNotNull(values["GEAR_SELECTION"] as? Int, values["CURRENT_GEAR"] as? Int)
        val brake = values["PARKING_BRAKE_ON"] as? Boolean
        val speedReadable = values.containsKey("PERF_VEHICLE_SPEED")
        val parked = when {
            gears.any { it != 4 && it != 0 } -> false
            gears.isNotEmpty() && gears.all { it == 4 } -> true
            brake == true -> true
            else -> null
        }
        val basis = listOfNotNull(
            gear?.let { "gear=${GEARS[it] ?: it}" },
            brake?.let { "parkingBrake=$it" }
        ).joinToString(",").ifEmpty { "none" }
        val gate = when {
            gear != null || brake != null -> "USABLE"
            else -> "NOT_USABLE"
        }
        log("VERDICT|drivingStateGate=$gate|parked=${parked ?: "unknown"}|basis=$basis|speed=${if (speedReadable) "readable" else "unavailable"}")
    }

    /** Registers a callback for the readable driving-state properties and logs changes for a while. */
    private fun listen(manager: Any, readable: List<Pair<String, Int>>) {
        val live = readable.filter { it.first in LISTEN_NAMES }
        if (live.isEmpty()) {
            log("CAR|listen|FAIL|no readable driving-state property to listen to")
            return
        }
        try {
            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val names = live.associate { it.second to it.first }
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        val event = args?.firstOrNull()
                        if (event != null) {
                            val id = event.javaClass.getMethod("getPropertyId").invoke(event) as Int
                            val area = event.javaClass.getMethod("getAreaId").invoke(event) as Int
                            val value = event.javaClass.getMethod("getValue").invoke(event)
                            val name = names[id] ?: hex(id)
                            log("EVENT|$name|area=${hex(area)}|value=${format(name, value)}")
                        }
                        null
                    }
                    "onErrorEvent" -> {
                        log("EVENT|error|${args?.joinToString()}")
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DrivingStateProbeCallback"
                    else -> null
                }
            }
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), handler)
            val register = manager.javaClass.getMethod(
                "registerCallback", callbackClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            live.forEach { (name, id) ->
                val ok = runCatching { register.invoke(manager, callback, id, 1f) }
                log("CAR|registerCallback($name)|${if (ok.isSuccess) "OK" else "FAIL"}|${ok.exceptionOrNull()?.let { describe(it) } ?: ok.getOrNull()}")
            }
            log("CAR|listen|OK|logging EVENT lines for ${LISTEN_SECONDS}s")
            Thread.sleep(LISTEN_SECONDS * 1000L)
            val unregister = manager.javaClass.getMethod("unregisterCallback", callbackClass)
            runCatching { unregister.invoke(manager, callback) }
        } catch (throwable: Throwable) {
            log("CAR|listen|FAIL|${describe(throwable)}")
        }
    }

    private fun createCar(carClass: Class<*>): Any? {
        val attempts = listOf<Pair<String, () -> Any?>>(
            "createCar(Context)" to { carClass.getMethod("createCar", Context::class.java).invoke(null, context) },
            "createCar(Context, Handler)" to {
                carClass.getMethod("createCar", Context::class.java, Handler::class.java)
                    .invoke(null, context, Handler(Looper.getMainLooper()))
            }
        )
        for ((label, attempt) in attempts) {
            try {
                val car = attempt() ?: continue
                val connected = waitConnected(carClass, car)
                log("CAR|$label|${if (connected) "OK" else "FAIL"}|connected=$connected")
                if (connected) return car
            } catch (throwable: Throwable) {
                log("CAR|$label|FAIL|${describe(throwable)}")
            }
        }
        return null
    }

    private fun waitConnected(carClass: Class<*>, car: Any): Boolean {
        val isConnected = carClass.getMethod("isConnected")
        repeat(50) {
            if (isConnected.invoke(car) as Boolean) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun registeredConfigs(manager: Any): Map<Int, Any> {
        val list = runCatching { manager.javaClass.getMethod("getPropertyList").invoke(manager) as? List<*> }
            .onFailure { log("CAR|getPropertyList|FAIL|${describe(it)}") }
            .getOrNull().orEmpty()
        log("CAR|getPropertyList|OK|count=${list.size}")
        return list.filterNotNull().associateBy { config ->
            config.javaClass.getMethod("getPropertyId").invoke(config) as Int
        }
    }

    private fun propertyIds(): Map<String, Int> {
        val holder = runCatching { Class.forName("android.car.VehiclePropertyIds") }.getOrNull() ?: return emptyMap()
        return TARGET_NAMES.mapNotNull { name ->
            runCatching { name to holder.getField(name).getInt(null) }.getOrNull()
        }.toMap()
    }

    private fun format(name: String, value: Any?): String = when {
        name == "GEAR_SELECTION" || name == "CURRENT_GEAR" -> {
            val gear = (value as? Int)
            "$value(${GEARS[gear] ?: "?"})"
        }
        name.startsWith("PERF_VEHICLE_SPEED") && value is Float -> "$value m/s (${"%.1f".format(value * 3.6f)} km/h)"
        else -> value.toString()
    }

    private fun describe(throwable: Throwable): String {
        val cause = (throwable as? InvocationTargetException)?.targetException ?: throwable
        return "${cause.javaClass.simpleName}: ${cause.message}".take(300)
    }

    private fun hex(value: Int) = "0x" + Integer.toHexString(value)

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "PleosProbe"
        private const val LISTEN_SECONDS = 60

        private val PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_SPEED",
            "pleos.car.permission.CAR_DRIVE"
        )

        // VehiclePropertyIds constant names.
        private val TARGET_NAMES = listOf(
            "GEAR_SELECTION",
            "CURRENT_GEAR",
            "PARKING_BRAKE_ON",
            "PERF_VEHICLE_SPEED",
            "PERF_VEHICLE_SPEED_DISPLAY",
            "IGNITION_STATE",
            "PERF_ODOMETER"
        )

        private val LISTEN_NAMES = setOf("GEAR_SELECTION", "CURRENT_GEAR", "PARKING_BRAKE_ON", "PERF_VEHICLE_SPEED")

        // VehicleGear bit values.
        private val GEARS = mapOf(
            0 to "UNKNOWN", 1 to "NEUTRAL", 2 to "REVERSE", 4 to "PARK", 8 to "DRIVE_1", 16 to "DRIVE_2"
        )
    }
}
