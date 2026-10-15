package app.wazegearslip.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** app.wazegearslip.extension.CarHosts, the runtime half (extensions/extension). */
private const val CAR_HOSTS = "Lapp/wazegearslip/extension/CarHosts;"

/** Gearslip's release signing certificate and package, from Gearslip's docs/BUILDING_APPS.md. */
private const val GEARSLIP = "4cc56ebdd13a54d260eca9b7e2b2ae6b30e2f225bfda8f508f0709edf084948b,app.seb3thehacker.gearslip"

/**
 * The strings androidx.car.app.validation.HostValidator's validateHost(HostInfo) leaves behind
 * once R8 has renamed it: the uid check's exception, which no log stripping removes, and the
 * rejection logged under CarApp.Val.
 */
private val VALIDATE_HOST_STRINGS = listOf("match caller's actual", "Unrecognized host")
private const val TEMPLATE_RENDERER = "android.car.permission.TEMPLATE_RENDERER"
private const val MY_UID = "Landroid/os/Process;->myUid()I"

private val ALLOWLIST_ENTRY = Regex("^(\\*|[0-9a-f]{64}),[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/**
 * The patch finds the car library's host check by its strings, not by renamed class names, so it
 * is not tied to one Waze build. 5.24.5.0 is the build the other Waze patches target; any other
 * version is offered as experimental.
 */
val COMPATIBILITY_WAZE = Compatibility(
    name = "Waze",
    packageName = "com.waze",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0x33CCFF,
    targets = listOf(
        AppTarget(version = null, isExperimental = true),
        AppTarget(version = "5.24.5.0"),
    ),
)

@Suppress("unused")
val wazeGearslipPatch = bytecodePatch(
    name = "Allow Gearslip",
    description = "Lets Gearslip, the Android Auto alternative, show Waze on the car screen. Waze's car library " +
        "accepts only Android Auto itself on a phone; this adds Gearslip (and any other host you list) to the " +
        "hosts it accepts. Android Auto keeps working as before.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    extendWith("extensions/extension.mpe")

    val hosts by stringOption(
        key = "hosts",
        default = GEARSLIP,
        values = mapOf(
            "Gearslip (release builds)" to GEARSLIP,
            "Gearslip (any build, also self-built)" to "*,app.seb3thehacker.gearslip",
        ),
        title = "Accepted hosts",
        description = "digest,package entries separated by ';'. The digest is the SHA-256 of the host's " +
            "signing certificate, 64 lowercase hex characters (apksigner verify --print-certs), or * for any " +
            "certificate.",
        required = true,
    )

    execute {
        val allowlist = hosts!!.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (allowlist.isEmpty()) throw PatchException("no hosts given")
        allowlist.forEach {
            if (!ALLOWLIST_ENTRY.matches(it)) throw PatchException("not a digest,package entry: $it")
        }

        fun strings(m: Method) = m.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.orEmpty()

        fun calls(m: Method, ref: String) = m.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == ref
        } == true

        // HostValidator.validateHost(HostInfo): boolean, one object parameter (HostInfo, renamed).
        // If R8 inlined it into isValidHost(HostInfo), that method has the same shape and strings.
        fun shaped(m: Method) = m.returnType == "Z" && m.parameterTypes.size == 1 &&
            m.parameterTypes[0].toString().let { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }

        val byStrings = mutableListOf<Pair<String, Method>>()
        val byCode = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lapp/wazegearslip/")) return@classDefForEach
            for (m in c.methods) {
                if (!shaped(m)) continue
                val s = strings(m)
                if (VALIDATE_HOST_STRINGS.any { needle -> s.any { it.contains(needle) } }) byStrings += c.type to m
                // Logs and messages stripped: the permission string and the "local service call" uid check.
                else if (TEMPLATE_RENDERER in s && calls(m, MY_UID)) byCode += c.type to m
            }
        }
        val (owner, found) = (byStrings.singleOrNull() ?: byCode.singleOrNull())
            ?: throw PatchException(
                "HostValidator's check not found (${byStrings.size} by its messages, ${byCode.size} by its code)",
            )

        val method = mutableClassDefBy(owner).methods.single {
            it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
        }
        val impl = method.implementation!!
        val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
        val params = if (isStatic) 1 else 2
        if (impl.registerCount - params < 2) throw PatchException("HostValidator's check has no two free registers")
        val hostInfo = if (isStatic) "p0" else "p1"

        // Accept a listed host before Waze's own check runs; everything else goes on to it, so
        // Android Auto is accepted exactly as before. v0 and v1 are free at the method's start.
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, $hostInfo
                const-string v1, "${allowlist.joinToString(";")}"
                invoke-static/range { v0 .. v1 }, $CAR_HOSTS->allowed(Ljava/lang/Object;Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :waze_check
                return v0
            """,
            ExternalLabel("waze_check", impl.instructions.first()),
        )
    }
}
