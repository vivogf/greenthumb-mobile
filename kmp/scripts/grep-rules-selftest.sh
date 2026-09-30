#!/usr/bin/env bash
# Violation-проверка grep-правил kmp/scripts/check.sh (Stage 11 п.5, VAL-TEST-005).
#
# Требование контракта: «Каждое правило падает на подсунутом нарушении и проходит
# на чистых». Скрипт это проверяет МЕХАНИЧЕСКИ:
#
#   1. чистый прогон        — все K1..K5 clean, exit 0;
#   2. для каждого правила  — подсовывается ОДНА подставка, ожидается, что
#                              упадёт РОВНО это правило (остальные — clean);
#   3. отдельная проверка   — maestro check-syntax на битом флоу (если maestro
#                              доступен) и на живом флоу;
#   4. уборка               — подставки удаляются, гейт снова зелёный.
#   5. защиты движка         — легальный код (import ktor, Color.Transparent,
#                              split("/"), литерал в //-комментарии) НЕ считается
#                              нарушением: иначе правило — магнит на легальный код.
#
# Известное ограничение движка (проверено этим прогоном, НЕ исправлено здесь):
# блочные комментарии /* … */ не отслеживаются, поэтому матч внутри KDoc
# считается нарушением. Поэтому подставки держат свои пояснения на //-строках.
# Полный список ограничений — в шапке kmp/scripts/check.sh.
#
# Подставки НЕ компилируются: это валидный по синтаксису Kotlin, но осмысленный
# только для grep-движка текст (как пробники m5 для K5). Они создаются в
# /tmp, копируются в дерево на время прогона и удаляются в trap — в коммит не
# попадают и не остаются в рабочем дереве.
#
# Файлы НЕ перезаписывают существующие: имя подставки уникально (grep-rule-<ID>-probe),
# и скрипт падает, если такой файл уже есть (значит, прошлый прогон не убрался).
#
# Использование:
#   bash scripts/grep-rules-selftest.sh      полный прогон всех правил
#   GT_SKIP_MAESTRO=1 bash scripts/grep-rules-selftest.sh   без шага maestro
#
# ВНИМАНИЕ: скрипт НЕ запускает gradle (только --grep-only и maestro
# check-syntax) — он дешёвый и не конфликтует с :desktopApp:hotRun.

set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export JAVA_HOME

MAESTRO_FLOW="maestro/smoke.yaml"
PROBE_DIR="shared/src/commonMain/kotlin/site/xmpp/greenthumb/greenthrubprobes"

PASS=0
FAIL=0
CREATED_PROBES=""

# Пути подставок: вне ui/screens и вне core/storage|data, если не сказано иное.
K1_PROBE="$PROBE_DIR/NetworkBypassProbe.kt"
K2_PROBE="$PROBE_DIR/PhotoPlaceholderProbe.kt"
K3_PROBE="$PROBE_DIR/RequestDateProbe.kt"
K4_PROBE="$PROBE_DIR/DirectDbProbe.kt"
K5_PROBE="shared/src/commonMain/kotlin/site/xmpp/greenthumb/ui/screens/login/ScreenLiteralProbe.kt"

cleanup() {
  for f in ${CREATED_PROBES}; do
    rm -f "$f"
  done
  rmdir "$PROBE_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Создать подставку. Падает, если файл уже есть — чтобы не затереть чужой код.
write_probe() {
  path="$1"
  if [ -e "$path" ]; then
    echo "ABORT: подставка $path уже существует (прошлый прогон не убрался) — руками, не автоматом."
    exit 2
  fi
  mkdir -p "$(dirname "$path")"
  cat > "$path"
  CREATED_PROBES="$CREATED_PROBES $path"
}

# Убрать ВСЕ подставки и забыть их. Каждый кейс идёт по одной подставке:
# если оставить предыдущую, попадания накапливаются и «посторонние правила
# чисты» перестаёт что-то доказывать.
drop_probes() {
  cleanup
  CREATED_PROBES=""
}

# Прогнать только grep-часть гейта; напечатать вывод, вернуть его код.
run_grep() {
  bash scripts/check.sh --grep-only 2>&1
}

# Сколько РЕАЛЬНЫХ попаданий напечатал гейт для правила $2: строки вида
# "     K1 путь:строка  текст" (сводка "…: 3 hit(s)" не считается).
count_rule() {
  printf '%s\n' "$1" | grep -c "^     $2[[:space:]]"
}

# Есть ли у правила $2 непрозрачное попадание (только сводка, без строк)?
summary_says_hit() {
  printf '%s\n' "$1" | grep -q "^  $2 .*hit(s)$"
}

ok()   { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# ---------------------------------------------------------------- 1. чистый прогон
echo "== 1. чистый прогон grep-правил =="
CLEAN_OUT=$(run_grep); CLEAN_RC=$?
echo "$CLEAN_OUT" | sed 's/^/  | /'
if [ "$CLEAN_RC" -eq 0 ]; then
  ok "чистое дерево: check.sh --grep-only exit 0"
else
  bad "чистое дерево: ожидался exit 0, получен $CLEAN_RC"
fi

# ------------------------------------- 2. подставки: по одной на каждое правило
# Каждая подставка содержит ТОЛЬКО нарушение своего правила. Проверяем, что
# упало ровно нужное правило и остальные остались чистыми.
probe_case() {
  id="$1"; expect_clean_others="$2"; desc="$3"
  out=$(run_grep); rc=$?
  echo "$out" | sed 's/^/  | /'
  n=$(count_rule "$out" "$id")
  drop_probes
  if [ "$rc" -eq 0 ]; then
    bad "$id ($desc): ожидался ненулевой exit, получен 0 — правило не сработало"
  elif [ "$n" -lt 1 ]; then
    bad "$id ($desc): exit $rc, но строк-попаданий $id нет — упало не то правило или попадание непрозрачно"
  else
    others_hit=0
    for other in K1 K2 K3 K4 K5; do
      [ "$other" = "$id" ] && continue
      if summary_says_hit "$out" "$other"; then
        others_hit=1
        echo "    постороннее попадание $other:"
        printf '%s\n' "$out" | grep "^     $other[[:space:]]" | sed 's/^/      /'
      fi
    done
    if [ "$others_hit" = "1" ] && [ "$expect_clean_others" = "yes" ]; then
      bad "$id ($desc): сработали посторонние правила — подставка не изолирована"
    else
      ok "$id ($desc): exit $rc, попаданий $id=$n, посторонние чисты"
    fi
  fi
}

# Ожидается, что подставка НЕ считается нарушением: гейт остаётся зелёным.
probe_is_clean() {
  id="$1"; desc="$2"
  out=$(run_grep); rc=$?
  echo "$out" | sed 's/^/  | /'
  drop_probes
  if [ "$rc" -ne 0 ]; then
    bad "$id не должен сработать ($desc): получен exit $rc"
  else
    ok "$id не сработал ($desc): exit 0"
  fi
}

echo
echo "== 2. K1 (аналог R1) — сеть мимо ApiClient =="
write_probe "$K1_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/** Нарушение K1: собственный клиент Ktor и прямой запрос мимо core.network. */
object NetworkBypassProbe {
    val client = HttpClient(CIO)

    suspend fun bypass(path: String): String = client.get(path).bodyAsText()
}
EOF
probe_case K1 yes "HttpClient(CIO) вне core.network"

echo
echo "== 3. K2 (аналог R3) — unsplash в исходниках =="
write_probe "$K2_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

/** Нарушение K2: плейсхолдер фото ссылается на сторонний фото-сервис (QW3 — только лист). */
const val PHOTO_PLACEHOLDER: String = "https://images.unsplash.com/photo-1"
EOF
probe_case K2 yes "unsplash URL"

echo
echo "== 4. K3 (аналог R7) — дата-со-временем в теле запроса =="
write_probe "$K3_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

import kotlinx.datetime.Clock

/** Нарушение K3: тело хочет YYYY-MM-DD, а сюда уходит ISO-дата со временем. */
object RequestDateProbe {
    fun lastWateredDate(): String = Clock.System.now().toString()
}
EOF
probe_case K3 yes "now().toString() в поле last_watered_date"

echo
echo "== 5. K3 (аналог R8) — конкатенация 'T12:00:00' к не нормализованной дате =="
write_probe "$K3_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

/** Нарушение K3 (аналог R8): «хак» с T12:00:00 на не нормализованной строке. */
fun toApiDate(raw: String): String = raw + "T12:00:00"
EOF
probe_case K3 yes "конкатенация времени к дате"

echo
echo "== 6. K4 (аналог R4) — БД мимо PlantRepository =="
write_probe "$K4_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

import site.xmpp.greenthumb.core.storage.GreenThumbDb

/**
 * Нарушение K4: чтение per-user базы напрямую, мимо PlantRepository —
 * теряется изоляция данных по пользователю (VAL-DATA-009).
 */
object DirectDbProbe {
    suspend fun peek(db: GreenThumbDb): List<Any> = db.plants().listAll()
}
EOF
probe_case K4 yes "GreenThumbDb + db.plants() вне core.storage|data"

echo
echo "== 7. K5 (Stage 5 п.5, VAL-DS-004) — литералы оформления в экране =="
write_probe "$K5_PROBE" <<'EOF'
package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Нарушение K5: оформление литералами вместо токенов GreenThumbTheme/Spacing. */
object ScreenLiteralProbe {
    val accent = Color(0xFF00FF00)
    val width = 16.dp
    val fontSize = 24.sp
}
EOF
probe_case K5 yes "Color(/.dp/.sp в ui/screens/**"

# ------------------------------------------------ 8. защиты движка: НЕ должны сработать
# Если хоть одна из них сработает, правило превращается в «магнит» на легальный код.
echo
echo "== 8. защиты движка (легальный код не считается нарушением) =="
write_probe "$K1_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * Легальный код, который НЕ нарушает K1: import ktor-типов и обращение к сети
 * через ApiClient, полученный сверху. Комментарий в KDoc НЕ должен прятать
 * матч — иначе правило молчало бы на реальном нарушении.
 */
class LegalConsumer(private val api: ApiLike) {
    suspend fun path(name: String): String = api.get("/api/plants/" + name).bodyAsText()
}

interface ApiLike {
    suspend fun get(path: String): Statement
}

interface Statement {
    suspend fun bodyAsText(): String
}
EOF
probe_is_clean K1 "import ktor + обращение через ApiClient (не HttpClient() и не fetch)"

write_probe "$K2_PROBE" <<'EOF'
package site.xmpp.greenthumb.greenthrubprobes

/** Легальный код: свой CDN, без запрещённого фото-сервиса. */
const val PHOTO_HINT: String = "https://cdn.greenthumb.local/leaf.png"

/** Настоящий //-комментарий со словом, похожим на нарушение, — не считается. */
const val NOTE: String = "x" // unsplash.com
EOF
probe_is_clean K2 "свой CDN + запрещённое слово в // -комментарии"

write_probe "$K5_PROBE" <<'EOF'
package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.ui.graphics.Color

/**
 * Легальный экранный код: оформление только токенами темы. Разделитель строк
 * содержит и точку, и косую черту, но это не литерал размера. Color.Transparent —
 * токен, а не конструктор.
 */
object ScreenTokensOnlyProbe {
    val accent = Color.Transparent
    val parts = "a/b".split("/")
    // А такой литерал в //-комментарии правило тоже не должно считать.
    val ignored: Int = 1 // 24.dp
}
EOF
probe_is_clean K5 "Color.Transparent, split(\"/\") и литерал в // -комментарии"

# Убрать все подставки и убедиться, что дерево снова чистое.
drop_probes

echo
echo "== 9. чистый прогон после уборки подставок =="
CLEAN_OUT2=$(run_grep); CLEAN_RC2=$?
echo "$CLEAN_OUT2" | sed 's/^/  | /'
if [ "$CLEAN_RC2" -eq 0 ]; then
  ok "после уборки: check.sh --grep-only exit 0"
else
  bad "после уборки: ожидался exit 0, получен $CLEAN_RC2"
fi

# ------------------------------------------------------- 9. maestro check-syntax
echo
echo "== 10. maestro check-syntax (битый флоу → ненулевой exit; живой → 0) =="
if [ "${GT_SKIP_MAESTRO:-0}" = "1" ]; then
  echo "  SKIP: GT_SKIP_MAESTRO=1"
elif ! command -v maestro >/dev/null 2>&1 && [ ! -x /opt/homebrew/bin/maestro ]; then
  echo "  SKIP: maestro не найден (check.sh в этом случае тоже пропускает шаг, а не падает)"
else
  MAESTRO=$(command -v maestro 2>/dev/null || echo /opt/homebrew/bin/maestro)
  if [ -n "${GT_MAESTRO_BIN:-}" ] && [ -x "${GT_MAESTRO_BIN}" ]; then
    MAESTRO="$GT_MAESTRO_BIN"
  fi
  if "$MAESTRO" check-syntax "$MAESTRO_FLOW" >/dev/null 2>&1; then
    ok "maestro check-syntax ${MAESTRO_FLOW}: живой флоу валиден"
  else
    bad "maestro check-syntax ${MAESTRO_FLOW}: живой флоу НЕ проходит"
  fi

  BROKEN_FLOW="/tmp/gt-m11-selftest-broken.yaml"
  cat > "$BROKEN_FLOW" <<'EOF'
appId: com.greenthumbplantcare
name: deliberately broken (selftest)
---
- assertVisible:
  totallyInvalidCommand: [
EOF
  if "$MAESTRO" check-syntax "$BROKEN_FLOW" >/dev/null 2>&1; then
    bad "maestro check-syntax: битый флоу НЕ упал — проверка синтаксиса не работает"
  else
    ok "maestro check-syntax: битый флоу упал (exit != 0)"
  fi
  rm -f "$BROKEN_FLOW"
fi

echo
echo "== ИТОГ: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
