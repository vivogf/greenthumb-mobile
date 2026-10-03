package site.xmpp.greenthumb.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Заголовочные стили для экранов, где Material3-дефолт (вес 400) читается
 * плоско. Не подключены к MaterialTheme: остальные экраны сохраняют свой вид,
 * стиль берёт только тот экран, который просит его явно.
 * Размеры — из RN `app/add-plant.tsx` (заголовок 18/700, секции 15/700),
 * заголовок экрана укрупнён для плотности телефона.
 */
object GtTypography {
    val screenTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val fieldLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}
