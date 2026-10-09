package com.laddu.app.core.safety

import org.json.JSONArray
import org.json.JSONObject

/** How seriously an owner should take an event. CRITICAL is deliberately hard to reach. */
enum class RiskLevel { INFO, CAUTION, HIGH, CRITICAL }

/** The owner's decision about an item, independent of what the camera thinks it sees. */
enum class Approval { APPROVED, RESTRICTED, HAZARDOUS, UNKNOWN }

enum class HazardCategory(val label: String) {
    PLASTIC("Plastic"), PACKAGING("Packaging / wrapper"), RUBBISH("Rubbish"), TEXTILE("Cloth / string"),
    SMALL_OBJECT("Small swallowable object"), SHARP("Sharp object"), BATTERY("Battery / electronics"),
    TOXIC_FOOD("Toxic food"), SPOILED_FOOD("Spoiled food"), FAECES("Faeces"), FOOD("Food"),
    TOY("Toy"), CONTAINER("Bowl / container"), UNKNOWN("Unknown object"),
}

/**
 * One thing Laddu may notice. [labels] are the detector class names (or aliases for an open-vocabulary model) that
 * map to this item; matching is case-insensitive. [risk] is the *baseline* risk if the dog genuinely interacts with
 * it - observed evidence decides whether that is ever reached.
 */
data class SafetyItem(
    val id: String,
    val name: String,
    val category: HazardCategory,
    val approval: Approval,
    val risk: RiskLevel,
    val labels: Set<String>,
    val notify: Boolean = true,
    val notes: String = "",
    val updatedAtMs: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("category", category.name); put("approval", approval.name)
        put("risk", risk.name); put("labels", JSONArray(labels.toList())); put("notify", notify)
        put("notes", notes); put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(j: JSONObject): SafetyItem? = runCatching {
            SafetyItem(
                id = j.getString("id"), name = j.getString("name"),
                category = enumOr(j.optString("category"), HazardCategory.UNKNOWN),
                approval = enumOr(j.optString("approval"), Approval.UNKNOWN),
                risk = enumOr(j.optString("risk"), RiskLevel.CAUTION),
                labels = j.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() }.toSet() } ?: emptySet(),
                notify = j.optBoolean("notify", true), notes = j.optString("notes"), updatedAtMs = j.optLong("updatedAtMs"),
            )
        }.getOrNull()
    }
}

/**
 * The owner-editable safety configuration. Detection stays local; this only decides what counts as a concern.
 * [sensitivity] 0..1 lowers the evidence needed for each alert level.
 */
data class SafetyPolicy(
    val enabled: Boolean = true,
    val sensitivity: Float = 0.5f,
    /** Alert on objects the detector cannot name (only possible with a class-agnostic or open-vocabulary model). */
    val unknownObjectAlerts: Boolean = true,
    val items: List<SafetyItem> = DefaultSafety.items,
) {
    private val byLabel: Map<String, SafetyItem> by lazy {
        buildMap { items.forEach { item -> item.labels.forEach { put(it.lowercase(), item) } } }
    }

    /** The configured item for a detector label, or null when the label is not part of the policy. */
    fun match(label: String): SafetyItem? = byLabel[label.lowercase()]

    /** Evidence needed to reach each level, relaxed as sensitivity rises. */
    val cautionMin: Float get() = 0.45f - 0.20f * sensitivity.coerceIn(0f, 1f)
    val highMin: Float get() = 0.70f - 0.20f * sensitivity.coerceIn(0f, 1f)
    val criticalMin: Float get() = 0.85f - 0.10f * sensitivity.coerceIn(0f, 1f)

    fun toJson() = JSONObject().apply {
        put("enabled", enabled); put("sensitivity", sensitivity.toDouble()); put("unknownObjectAlerts", unknownObjectAlerts)
        put("items", JSONArray(items.map { it.toJson() }))
    }

    companion object {
        fun fromJson(j: JSONObject?): SafetyPolicy {
            if (j == null) return SafetyPolicy()
            val items = j.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { SafetyItem.fromJson(a.getJSONObject(it)) } }
            return SafetyPolicy(
                enabled = j.optBoolean("enabled", true),
                sensitivity = j.optDouble("sensitivity", 0.5).toFloat().coerceIn(0f, 1f),
                unknownObjectAlerts = j.optBoolean("unknownObjectAlerts", true),
                items = if (items.isNullOrEmpty()) DefaultSafety.items else items,
            )
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E = enumValues<E>().firstOrNull { it.name == raw } ?: default

/**
 * Starting policy. Labels are COCO class names, because that is what the bundled SSD MobileNet detector can output.
 * Items whose labels the current model cannot produce (plastic wrapper, sock, chocolate, rubbish...) are included for
 * open-vocabulary / custom models; with the bundled model they simply never trigger. See docs/HAZARD_DETECTION.md.
 */
object DefaultSafety {
    private fun item(
        id: String, name: String, c: HazardCategory, a: Approval, r: RiskLevel, vararg labels: String, notes: String = "",
    ) = SafetyItem(id, name, c, a, r, labels.map { it.lowercase() }.toSet(), notes = notes)

    val items: List<SafetyItem> = listOf(
        // --- visible with the bundled COCO detector
        item("bottle", "Bottle (possibly plastic)", HazardCategory.PLASTIC, Approval.RESTRICTED, RiskLevel.CAUTION, "bottle"),
        item("bag", "Bag (possibly a plastic bag)", HazardCategory.PACKAGING, Approval.RESTRICTED, RiskLevel.CAUTION, "handbag", "backpack", "suitcase", "plastic bag", "wrapper", "packaging"),
        item("sharp", "Knife / scissors / fork", HazardCategory.SHARP, Approval.HAZARDOUS, RiskLevel.HIGH, "knife", "scissors", "fork"),
        item("cloth", "Tie / cloth / string", HazardCategory.TEXTILE, Approval.RESTRICTED, RiskLevel.CAUTION, "tie", "sock", "cloth", "string", "thread", "rubber band"),
        item("electronics", "Remote / phone / small electronics", HazardCategory.BATTERY, Approval.RESTRICTED, RiskLevel.HIGH, "remote", "cell phone", "mouse", "battery"),
        item("small", "Small household item", HazardCategory.SMALL_OBJECT, Approval.RESTRICTED, RiskLevel.CAUTION, "toothbrush", "hair drier", "clock", "vase", "book"),
        item("cup", "Cup", HazardCategory.CONTAINER, Approval.UNKNOWN, RiskLevel.INFO, "cup", "wine glass"),
        item("food_bowl", "Food bowl", HazardCategory.CONTAINER, Approval.APPROVED, RiskLevel.INFO, "bowl"),
        item("toys", "Toy / ball", HazardCategory.TOY, Approval.APPROVED, RiskLevel.INFO, "teddy bear", "sports ball", "frisbee", "kite"),
        item("human_food", "Human food (not approved by default)", HazardCategory.FOOD, Approval.UNKNOWN, RiskLevel.CAUTION,
            "banana", "apple", "orange", "carrot", "broccoli", "sandwich", "hot dog", "pizza", "donut", "cake",
            notes = "Mark the ones your dog may eat as Approved."),
        // --- need an open-vocabulary or custom model to be detected
        item("rubbish", "Rubbish / waste", HazardCategory.RUBBISH, Approval.HAZARDOUS, RiskLevel.HIGH, "trash", "rubbish", "garbage", "waste bin", "tissue"),
        item("toxic_food", "Chocolate / grapes / onion / garlic", HazardCategory.TOXIC_FOOD, Approval.HAZARDOUS, RiskLevel.HIGH, "chocolate", "grapes", "raisins", "onion", "garlic"),
        item("faeces", "Faeces", HazardCategory.FAECES, Approval.HAZARDOUS, RiskLevel.HIGH, "faeces", "poop"),
        item("unknown", "Unidentified object", HazardCategory.UNKNOWN, Approval.UNKNOWN, RiskLevel.CAUTION, "unknown object"),
    )
}
