#!/usr/bin/env bash
# Гейт kmp-кода: компиляция Android+JVM+desktop, :shared:jvmTest, grep-правила,
# синтаксис Maestro-флоу, i18n-паритет.
# Замена npm run check для kmp/ (Expo-гейт живёт отдельно: scripts/check.mjs, job check).
# Вызывается: mission-check.sh, CI job kmp (.github/workflows/check.yml), вручную.
#
# Использование:
#   bash scripts/check.sh                    полный гейт (gradle + грепы + maestro + i18n)
#   bash scripts/check.sh --grep-only        только grep-правила (быстрая итерация)
#   GT_SKIP_MAESTRO=1 bash scripts/check.sh  полный гейт без шага maestro check-syntax
#
# ── Grep-правила (Stage 11 п.5, VAL-TEST-005) ─────────────────────────────────────
# По одному на каждое ЖИВОЕ правило scripts/check.mjs, нумерация по его образцу.
# Каждое проверяется violation-прогоном: scripts/grep-rules-selftest.sh подсовывает
# по одной подставке на правило и требует, чтобы упало РОВНО это правило (остальные
# clean), а на чистом дереве гейт был зелёным.
#   K1 (аналог R1)  — сеть мимо ApiClient: HttpClient(/io.ktor.client.request/fetch(
#                     openConnection(/okhttp3/… вне core/network. Единственный путь
#                     в сеть — ApiClient (architecture.md §5).
#   K2 (аналог R3)  — unsplash: плейсхолдер фото — только лист (QW3), URL unsplash
#                     в исходниках запрещены.
#   K3 (аналог R7)  — toISOString( и литерал "YYYY-MM-DDT…" в теле запроса.
#   K3 (аналог R8)  — конкатенация времени "T12:00:00" к не нормализованной дате, а
#                     также присваивание часового источника (now().toString() и т.п.)
#                     полю-дате-без-времени на той же строке. Тот же ID: R7 и R8 ловят
#                     одну ошибку — дата-со-временем вместо YYYY-MM-DD в теле запроса
#                     (backend-contract.md, инцидент 2026-03-09).
#   K4 (аналог R4)  — БД мимо PlantRepository: символы Room/DAO/Entity и вызовы
#                     DAO-аксессоров только в core/storage и data. Смысл R4 в RN —
#                     изоляция данных по пользователю; здесь изоляция персистентная
#                     (база per-user, VAL-DATA-009), и обход репозитория её ломает.
#   K5 (Stage 5 п.5, VAL-DS-004) — UI-литералы в ui/screens/**: .dp, Color(, .sp,
#                     TextUnit. Оформление только через токены GreenThumbTheme/
#                     Spacing/Radii (architecture.md §8). K5 сканирует ТОЛЬКО
#                     ui/screens/**: так реализовано исключение «Modifier.size внутри
#                     ui/components/**» (AGENTS.md, дополнение m5 в kmp-toolchain.md).
#
# Правила scripts/check.mjs, у которых нет аналога в kmp (не добавлялись):
#   R2  staleTime: Infinity       — React Query, в KMP её нет;
#   R10 expo-notifications lazy    — KMP использует FCM (M9), импорт в androidApp;
#   R11 gt-handoff.json            — Stage 0, только Expo-код;
#   R12 clearRecoveryKey + handoff — Stage 0, только Expo-код.
#
# Ограничения движка (как в check.mjs): пропускаются только // -комментарии, не /* */;
# import-строки не нарушают контентных правил; тестовые исходники (src/*Test/) не
# сканируются — там MockEngine/фикстуры легальны. Ложное срабатывание — сузить
# regex НИЖЕ, не обходить правило в коде.
#   `://` внутри URL-строки — НЕ начало комментария (иначе K2 молчал бы на
#   «https://images.unsplash.com/…», то есть на самом нужном случае).
#   Блочные комментарии /* … */ НЕ отслеживаются: матч в KDoc считается
#   нарушением. Это известное ограничение движка, а не дефект правила; вывод
#   с указанием файла:строки позволяет отличить текст в KDoc от живого кода.

set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

# JAVA_HOME: переменная окружения выигрывает (CI: actions/setup-java её задаёт),
# иначе локальный Homebrew JDK 21. Без JAVA_HOME gradlew падает сразу.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export JAVA_HOME

# Каталог с Maestro-флоу относительно kmp/ (скрипт уже cd'нул в корень kmp/).
MAESTRO_FLOW="maestro/smoke.yaml"

# Найти maestro: явный путь в GT_MAESTRO_BIN, затем PATH, затем Homebrew.
# Отсутствие maestro НЕ является падением гейта (см. maestro_syntax).
find_maestro() {
  if [ -n "${GT_MAESTRO_BIN:-}" ] && [ -x "${GT_MAESTRO_BIN}" ]; then
    echo "$GT_MAESTRO_BIN"
    return 0
  fi
  if command -v maestro >/dev/null 2>&1; then
    command -v maestro
    return 0
  fi
  if [ -x /opt/homebrew/bin/maestro ]; then
    echo /opt/homebrew/bin/maestro
    return 0
  fi
  return 1
}

grep_rules() {
  echo "== [2/4] grep rules (K1..K5) =="
  local files hits id title n failed
  files=$(find shared androidApp desktopApp -type f -name '*.kt' -not -path '*/build/*' | sort)
  if [ -z "$files" ]; then
    echo "OK: kotlin-источников ещё нет — grep-правила пропущены"
    return 0
  fi

  # Один проход по всем .kt: каждое нарушение печатается как "ID\tфайл:строка\tтекст".
  # inComment — аналог inLineComment из scripts/check.mjs: матч внутри // -комментария
  # не считается. Отличие от check.mjs: `://` внутри URL-строки НЕ считается началом
  # комментария (иначе правило K2 на литерале "https://images.unsplash.com/…" молчало бы
  # — ровно тот случай, ради которого правило существует).
  hits=$(awk '
    function inComment(s, pos,   i) {
      for (i = 1; i < length(s); i++) {
        if (substr(s, i, 1) == "/" && substr(s, i + 1, 1) == "/") {
          if (i > 1 && substr(s, i - 1, 1) == ":") continue
          return (i < pos)
        }
      }
      return 0
    }
    {
      if (FILENAME ~ /src\/(common|jvm|android)Test\//) next
      # import-строки не нарушают контентных правил (аналог import-исключения R1 в check.mjs)
      if ($0 ~ /^[[:space:]]*import[[:space:]]/) next

      # K1 — сеть мимо ApiClient (core.network): единственный путь в сеть.
      inNetwork = (FILENAME ~ /core\/network\//)
      if (!inNetwork) {
        if (match($0, /HttpClient\(/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /io\.ktor\.client\.request/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /io\.ktor\.client\.HttpClient/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /(^|[^[:alnum:]_])fetch\(/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /openConnection\(/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /URLConnection/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /java\.net\.http/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /okhttp3\./) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /java\.net\.URL\(/) && !inComment($0, RSTART)) print "K1\t" FILENAME ":" FNR "\t" $0
      }

      # K2 — unsplash (плейсхолдер фото — только лист, QW3).
      if (match(tolower($0), /unsplash/) && !inComment($0, RSTART))
        print "K2\t" FILENAME ":" FNR "\t" $0

      # K3 — дата-со-временем в теле запроса вместо YYYY-MM-DD (аналог R7 + R8).
      #   (a) литерал "YYYY-MM-DDT…"                     (b) toISOString(
      #   (c) конкатенация времени "T12:00:00" к дате    (R8)
      #   (d) полю-дате-без-времени на той же строке присваивается результат
      #       часового источника (now().toString() и т.п.)                  (R8)
      # (d) намеренно узкий: Instant.toString() сам по себе законен там, где
      # поле даты-со-временем (created_at в PlantRepository.addQueued).
      if ((match($0, /"[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/) ||
           match($0, /toISOString\(/) ||
           match($0, /\+[[:space:]]*"T[0-9][0-9]:[0-9][0-9]/)) && !inComment($0, RSTART))
        print "K3\t" FILENAME ":" FNR "\t" $0
      else if (match($0, /last_watered_date|last_fertilized_date|last_repotted_date|last_pruned_date|lastWateredDate|lastFertilizedDate|lastRepottedDate|lastPrunedDate/) &&
               match($0, /now\(\)\.toString\(\)|toLocalDateTime\([^)]*\)\.toString\(\)|fromEpochMilliseconds\([^)]*\)\.toString\(\)/) &&
               !inComment($0, RSTART))
        print "K3\t" FILENAME ":" FNR "\t" $0

      # K4 — БД мимо PlantRepository: символы Room/DAO/Entity и вызовы
      # DAO-аксессоров законны только в core/storage и data.
      inStorage = (FILENAME ~ /core\/storage\// || FILENAME ~ /\/data\//)
      if (!inStorage) {
        if (match($0, /(^|[^[:alnum:]_])(GreenThumbDb|PlantDao|SyncMetaDao|PendingMutationDao|PlantStoreDao|RoomDatabase)([^[:alnum:]_]|$)/) && !inComment($0, RSTART))
          print "K4\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /androidx\.room/) && !inComment($0, RSTART)) print "K4\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /@Dao([^[:alnum:]_]|$)/) && !inComment($0, RSTART)) print "K4\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /(^|[^[:alnum:]_])(PlantEntity|SyncMetaEntity|PendingMutationEntity)([^[:alnum:]_]|$)/) && !inComment($0, RSTART))
          print "K4\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /\.(plants|syncMeta|pendingMutations|store)\(\)/) && !inComment($0, RSTART))
          print "K4\t" FILENAME ":" FNR "\t" $0
      }

      # K5 — UI-литералы в ui/screens/** (оформление только токенами ui.theme).
      if (FILENAME ~ /ui\/screens\//) {
        if (match($0, /\.dp([^[:alnum:]_]|$)/) && !inComment($0, RSTART)) print "K5\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /\.sp([^[:alnum:]_]|$)/) && !inComment($0, RSTART)) print "K5\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /(^|[^a-zA-Z])Color\(/) && !inComment($0, RSTART)) print "K5\t" FILENAME ":" FNR "\t" $0
        else if (match($0, /TextUnit/) && !inComment($0, RSTART)) print "K5\t" FILENAME ":" FNR "\t" $0
      }
    }
  ' $files)

  local ids=(K1 K2 K3 K4 K5)
  local titles=(
    "network outside core.network (аналог R1: единственный путь в сеть — ApiClient)"
    "unsplash (аналог R3: плейсхолдер фото — лист)"
    "date-with-time in request body (аналог R7+R8: тело хочет YYYY-MM-DD)"
    "DB/DAO outside core.storage|data (аналог R4: БД только через PlantRepository)"
    "UI literals in ui/screens/** (.dp/Color(/.sp/TextUnit — только токены ui.theme)"
  )

  failed=0
  for i in "${!ids[@]}"; do
    id="${ids[$i]}"
    title="${titles[$i]}"
    if [ -n "$hits" ]; then
      n=$(printf '%s\n' "$hits" | grep -c "^${id}"$'\t')
    else
      n=0
    fi
    if [ "$n" = "0" ]; then
      echo "  ${id} ${title}: clean"
    else
      failed=1
      echo "  ${id} ${title}: ${n} hit(s)"
    fi
  done

  if [ -n "$hits" ]; then
    echo
    printf '%s\n' "$hits" | while IFS=$'\t' read -r id loc text; do
      echo "     ${id} ${loc}  ${text}"
    done
  fi

  echo
  if [ "$failed" = "1" ]; then
    echo "FAIL: grep rules — попадания выше."
    echo "False positive — сузь regex в kmp/scripts/check.sh (не обходи правило в коде)."
    return 1
  fi
  echo "OK: all automated checks clean."
  return 0
}

# Синтаксис Maestro-флоу. Эмулятор НЕ нужен: `maestro check-syntax` только
# разбирает YAML-флоу. Отсутствие maestro — не падение: CI-раннер его не ставит,
# и гейт обязан оставаться зелёным без него (понятное сообщение + пропуск).
# Ошибка РАЗБОРА флоу — падение: битый флоу ломает смоук на эмуляторе.
maestro_syntax() {
  echo "== [3/4] maestro flow syntax (maestro check-syntax ${MAESTRO_FLOW}) =="
  if [ "${GT_SKIP_MAESTRO:-0}" = "1" ]; then
    echo "SKIP: GT_SKIP_MAESTRO=1 — шаг maestro check-syntax пропущен по запросу."
    echo
    return 0
  fi
  if [ ! -f "$MAESTRO_FLOW" ]; then
    echo "FAIL: ${MAESTRO_FLOW} не найден — смоук-сценарий Stage 11 п.4 потерян."
    return 1
  fi
  local maestro
  if ! maestro=$(find_maestro); then
    echo "SKIP: maestro не найден в PATH, GT_MAESTRO_BIN и /opt/homebrew/bin/maestro."
    echo "      Синтаксис флоу не проверен (не падение гейта). Смоук запускается отдельно:"
    echo "      services.yaml → kmp-maestro-smoke (нужен эмулятор + установленный debug APK)."
    echo
    return 0
  fi
  if ! "$maestro" check-syntax "$MAESTRO_FLOW"; then
    echo
    echo "FAIL: maestro check-syntax ${MAESTRO_FLOW} — флоу не разбирается (см. вывод выше)."
    return 1
  fi
  echo "OK: ${MAESTRO_FLOW} — синтаксис валиден (${maestro})"
  echo
  return 0
}

if [ "${1:-}" = "--grep-only" ]; then
  grep_rules
  exit $?
fi

echo "== [1/4] gradle: :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin :shared:jvmTest =="
if ! ./gradlew --console=plain :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin :shared:jvmTest; then
  echo
  echo "FAIL: gradle — ошибки компиляции/тестов выше (упавший task виден в выводе)."
  exit 1
fi
echo "OK: gradle"
echo

if ! grep_rules; then
  exit 1
fi

if ! maestro_syntax; then
  exit 1
fi

# Stage 6 п.4 (VAL-I18N-003): множества ключей strings.xml en/ru покрывают
# i18n/locales полностью (плюральные формы, интерполяция, KMP-ключи session.*).
echo "== [4/4] i18n parity (scripts/i18n_check.py) =="
if ! python3 scripts/i18n_check.py; then
  exit 1
fi
echo
exit 0
