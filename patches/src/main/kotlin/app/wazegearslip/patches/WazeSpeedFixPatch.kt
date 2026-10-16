package app.wazegearslip.patches

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** app.wazegearslip.extension.SpeedFix, the runtime half (extensions/extension). */
private const val SPEED_FIX = "Lapp/wazegearslip/extension/SpeedFix;"
private const val LOCATION_MANAGER = "Landroid/location/LocationManager;"
private const val LOCATION_LISTENER = "Landroid/location/LocationListener;"
private val REROUTED = setOf("requestLocationUpdates", "removeUpdates")

@Suppress("unused")
val wazeSpeedFixPatch = bytecodePatch(
    name = "Fix speed drops",
    description = "Stops Waze from showing 0 km/h for a moment while driving on phones without Google Play " +
        "services. A location without a speed gets one from the distance travelled, or keeps the last speed " +
        "briefly, and short gaps between locations can be bridged. Real stops are not affected.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    extendWith("extensions/extension.mpe")

    val holdSeconds by intOption(
        key = "holdSeconds",
        default = 2,
        title = "Hold speed (seconds)",
        description = "How long the last speed is kept when a location comes without one and its position is " +
            "too coarse to compute one. After that the speed falls as if braking.",
        required = true,
    )
    val braking by intOption(
        key = "braking",
        default = 4,
        title = "Braking (m/s²)",
        description = "How fast a held speed may fall after the hold time. 4 is firm braking.",
        required = true,
    )
    val bridgeSeconds by intOption(
        key = "bridgeSeconds",
        default = 2,
        title = "Bridge gaps (seconds)",
        description = "When no location arrives while driving, continue along the last heading for at most this " +
            "many seconds. 0 turns this off.",
        required = true,
    )

    execute {
        val hold = holdSeconds!!
        val brake = braking!!
        val bridge = bridgeSeconds!!
        if (hold !in 0..10) throw PatchException("hold seconds must be 0 to 10")
        if (brake !in 1..15) throw PatchException("braking must be 1 to 15 m/s²")
        if (bridge !in 0..5) throw PatchException("bridge seconds must be 0 to 5")

        val speedFix = mutableClassDefBy(SPEED_FIX)

        // SpeedFix.config(): "hold;braking;bridge".
        val config = speedFix.methods.singleOrNull {
            it.name == "config" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("$SPEED_FIX.config() not found")
        val first = config.implementation!!.instructions.first()
        if (first.opcode != Opcode.CONST_STRING) throw PatchException("config() no longer starts with const-string")
        config.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$hold;$brake;$bridge\"")

        // The overloads SpeedFix mirrors, keyed by name and parameters (without the LocationManager).
        val mirrored = speedFix.methods.mapNotNull { m ->
            val params = m.parameterTypes.map { it.toString() }
            if (m.name in REROUTED && params.firstOrNull() == LOCATION_MANAGER) m.name to params.drop(1) else null
        }.toSet()

        // Every call in Waze to LocationManager.requestLocationUpdates / removeUpdates that hands over a
        // LocationListener. The framework's names survive obfuscation, so this needs no Waze build.
        data class Site(val type: String, val name: String, val params: List<String>, val returnType: String, val index: Int, val call: String)
        val sites = mutableListOf<Site>()
        val skipped = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lapp/wazegearslip/")) return@classDefForEach
            for (m in c.methods) {
                m.implementation?.instructions?.forEachIndexed { i, ins ->
                    if (ins.opcode != Opcode.INVOKE_VIRTUAL && ins.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@forEachIndexed
                    val ref = (ins as ReferenceInstruction).reference as? MethodReference ?: return@forEachIndexed
                    if (ref.definingClass != LOCATION_MANAGER || ref.name !in REROUTED) return@forEachIndexed
                    val params = ref.parameterTypes.map { it.toString() }
                    if (LOCATION_LISTENER !in params) return@forEachIndexed
                    if ((ref.name to params) !in mirrored) {
                        skipped += "${ref.name}(${params.joinToString("")})"
                        return@forEachIndexed
                    }
                    sites += Site(c.type, m.name, m.parameterTypes.map { it.toString() }, m.returnType, i, ref.name)
                }
            }
        }
        if (sites.none { it.call == "requestLocationUpdates" }) {
            throw PatchException(
                "Waze calls LocationManager.requestLocationUpdates with a listener nowhere SpeedFix covers" +
                    if (skipped.isNotEmpty()) " (unsupported: ${skipped.joinToString()})" else "",
            )
        }
        if (skipped.isNotEmpty()) println("Fix speed drops: left unchanged: ${skipped.joinToString()}")

        // invoke-virtual { manager, args... }, LocationManager->X(args)  becomes
        // invoke-static  { manager, args... }, SpeedFix->X(LocationManager, args): same registers.
        for (site in sites) {
            val method = mutableClassDefBy(site.type).methods.first {
                it.name == site.name && it.parameterTypes.map { p -> p.toString() } == site.params && it.returnType == site.returnType
            }
            val ins = method.implementation!!.instructions[site.index]
            val ref = (ins as ReferenceInstruction).reference as MethodReference
            val target = "$SPEED_FIX->${ref.name}($LOCATION_MANAGER${ref.parameterTypes.joinToString("")})${ref.returnType}"
            val smali = when (ins) {
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${ins.startRegister} .. v${ins.startRegister + ins.registerCount - 1} }, $target"
                is Instruction35c -> {
                    val regs = listOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG)
                        .take(ins.registerCount).joinToString(", ") { "v$it" }
                    "invoke-static { $regs }, $target"
                }
                else -> throw PatchException("unexpected instruction format at ${site.type}->${site.name}")
            }
            method.replaceInstruction(site.index, smali)
        }
        println("Fix speed drops: rerouted ${sites.size} LocationManager calls")
    }
}
