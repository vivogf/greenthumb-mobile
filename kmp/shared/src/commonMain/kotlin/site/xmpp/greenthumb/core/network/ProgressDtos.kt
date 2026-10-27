package site.xmpp.greenthumb.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/progress` (architecture §2.11). `achievements[].id` stays a wire
 * string: the app shows only the ids it knows and treats the rest as absent.
 */
@Serializable
public data class ProgressDto(
    val streak: StreakDto = StreakDto(),
    val achievements: List<AchievementDto> = emptyList(),
)

@Serializable
public data class StreakDto(
    val current: Int = 0,
    val best: Int = 0,
)

@Serializable
public data class AchievementDto(
    val id: String,
    val unlocked: Boolean = false,
    @SerialName("unlocked_at")
    val unlockedAt: String? = null,
)
