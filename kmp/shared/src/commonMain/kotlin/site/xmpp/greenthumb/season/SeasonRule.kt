package site.xmpp.greenthumb.season

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Seasonal watering rule (architecture §2.9), the Kotlin twin of PWA `shared/season/rule.ts`.
 * Both pass every case of the shared `season-vectors.json`. Pure: the zone id, the dates and the
 * latitude table are parameters; nothing here reads a clock or the device zone.
 */
public enum class SeasonMode(public val wire: String) {
    Auto("auto"),
    North("north"),
    South("south"),
    Off("off"),
    ;

    public companion object {
        public fun fromWire(value: String?): SeasonMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Missing or unknown `season_mode` behaves as auto, as on the server. */
public fun seasonModeOf(wire: String?): SeasonMode = SeasonMode.fromWire(wire) ?: SeasonMode.Auto

public enum class Hemisphere(public val wire: String) {
    North("north"),
    South("south"),
    Tropical("tropical"),
    None("none"),
}

/** Bundled resource path of the table generated in the PWA repo (`scripts/ops/gen-tz-latitudes.mjs`). */
public const val TZ_LATITUDES_RESOURCE: String = "files/tz-latitudes.json"

/** IANA zone id (aliases included) → latitude. UTC, GMT and the `Etc` area ids are absent by design. */
public class TzLatitudes(private val zones: Map<String, Double>) {
    public val size: Int get() = zones.size

    public fun latitude(zoneId: String?): Double? = zoneId?.let { zones[it] }

    public companion object {
        public val Empty: TzLatitudes = TzLatitudes(emptyMap())
    }
}

public fun decodeTzLatitudes(text: String): TzLatitudes {
    val zones = Json.parseToJsonElement(text).jsonObject.getValue("zones").jsonObject
    return TzLatitudes(
        zones.mapValues { (zone, value) ->
            requireNotNull(value.jsonPrimitive.doubleOrNull) { "latitude of $zone is not a number" }
        },
    )
}

public class SeasonRule(private val latitudes: TzLatitudes) {

    /** Geographic result for the zone alone, whatever the mode. */
    public fun hemisphere(zoneId: String?): Hemisphere {
        val latitude = latitudes.latitude(zoneId) ?: return Hemisphere.None
        return when {
            latitude >= TROPICS_LIMIT_DEGREES -> Hemisphere.North
            latitude <= -TROPICS_LIMIT_DEGREES -> Hemisphere.South
            else -> Hemisphere.Tropical
        }
    }

    /** The hemisphere whose dormant months apply, or null when the mode gives no adjustment. */
    public fun seasonHemisphere(mode: SeasonMode, zoneId: String?): Hemisphere? = when (mode) {
        SeasonMode.Off -> null
        SeasonMode.North -> Hemisphere.North
        SeasonMode.South -> Hemisphere.South
        SeasonMode.Auto -> hemisphere(zoneId).takeIf { it == Hemisphere.North || it == Hemisphere.South }
    }

    /** True when the month of the last watering is dormant for the applied hemisphere. */
    public fun isDormantWatering(lastWatered: String, mode: SeasonMode, zoneId: String?): Boolean {
        val dormant = when (seasonHemisphere(mode, zoneId)) {
            Hemisphere.North -> NORTH_DORMANT_MONTHS
            Hemisphere.South -> SOUTH_DORMANT_MONTHS
            else -> return false
        }
        return monthOf(lastWatered) in dormant
    }

    /** Watering interval only; fertilize, repot and prune intervals are never adjusted. */
    public fun effectiveWaterIntervalDays(baseDays: Int, lastWatered: String, mode: SeasonMode, zoneId: String?): Int =
        if (isDormantWatering(lastWatered, mode, zoneId)) (baseDays * 3 + 1) / 2 else baseDays

    public fun nextWaterDueDate(lastWatered: String, baseDays: Int, mode: SeasonMode, zoneId: String?): LocalDate =
        LocalDate.parse(lastWatered.take(DATE_LENGTH))
            .plus(effectiveWaterIntervalDays(baseDays, lastWatered, mode, zoneId), DateTimeUnit.DAY)

    public fun nextWaterDue(lastWatered: String, baseDays: Int, mode: SeasonMode, zoneId: String?): String =
        nextWaterDueDate(lastWatered, baseDays, mode, zoneId).toString()

    public companion object {
        /** |latitude| at or above this is outside the tropics. */
        public const val TROPICS_LIMIT_DEGREES: Double = 15.0
        public val NORTH_DORMANT_MONTHS: Set<Int> = setOf(11, 12, 1, 2)
        public val SOUTH_DORMANT_MONTHS: Set<Int> = setOf(5, 6, 7, 8)

        private const val DATE_LENGTH = 10
        private val MONTH = Regex("""^\d{4}-(\d{2})-\d{2}""")

        private fun monthOf(date: String): Int =
            requireNotNull(MONTH.find(date)) { "expected YYYY-MM-DD, got $date" }.groupValues[1].toInt()
    }
}

/**
 * The rule bound to one user's mode and the device zone: what [site.xmpp.greenthumb.data.wateringStatus]
 * and the screens consume. [Off] gives the plain interval.
 */
public class SeasonContext(
    public val mode: SeasonMode,
    public val zoneId: String?,
    latitudes: TzLatitudes,
) {
    private val rule = SeasonRule(latitudes)

    public fun effectiveWaterIntervalDays(baseDays: Int, lastWatered: String): Int =
        rule.effectiveWaterIntervalDays(baseDays, lastWatered, mode, zoneId)

    public fun nextWaterDueDate(lastWatered: String, baseDays: Int): LocalDate =
        rule.nextWaterDueDate(lastWatered, baseDays, mode, zoneId)

    /** True when the winter-mode interval ×1.5 applies to this watering. */
    public fun isAdjusted(lastWatered: String): Boolean = rule.isDormantWatering(lastWatered, mode, zoneId)

    public companion object {
        public val Off: SeasonContext = SeasonContext(SeasonMode.Off, null, TzLatitudes.Empty)
    }
}
