#!/usr/bin/env bash
# Гейт kmp-кода: компиляция Android+JVM+desktop, :shared:jvmTest, grep-правила.
# Замена npm run check для kmp/ (Expo-гейт живёт отдельно: scripts/check.mjs, job check).
# Вызывается: mission-check.sh, CI job kmp (.github/workflows/check.yml), вручную.
#
# Использование:
#   bash scripts/check.sh              полный гейт (gradle + grep-правила)
#   bash scripts/check.sh --grep-only  только grep-правила (быстрая итерация по правилам)
#
# Grep-правила — каркас Stage 1, нумерация по образцу scripts/check.mjs (R1, R3, ...).
# Полный набор аналогов R1/R3/R4/R7/R8 добивается к M11 (VAL-TEST-005):
#   K1 (аналог R1) — HttpClient( вне core.network: сеть только через ApiClient core.network
#   K2 (аналог R3) — unsplash.com URL (запрещены, QW3)
#   K3 (аналог R7) — дата со временем в теле запроса: литерал "YYYY-MM-DDT…" / toISOString(
#                    (консервативно; точный grep по слоям данных — M11)
#   K4 (аналог R4) — Room.databaseBuilder вне core.storage|data: БД только через репозиторий
#   K5 (Stage 5 п.5) — UI-литералы в ui/screens/**: .dp, Color(, .sp, TextUnit —
#       оформление только через токены GreenThumbTheme/Spacing/Radii (architecture.md §8).
#       .dp/.sp проверяются с границей слова, чтобы .split(/.display не были ложными
#       попаданиями. Исключение — Modifier.size внутри ui.components/**: ui.components
#       K5 не сканируется вовсе (AGENTS.md: «K5 сканирует только ui/screens/**»).
# Тестовые исходники (src/*Test/) не сканируются: там MockEngine/фикстуры легальны.
# Ограничение каркаса (как в check.mjs): пропускаются только // -комментарии, не /* */.

set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

# JAVA_HOME: переменная окружения выигрывает (CI: actions/setup-java её задаёт),
# иначе локальный Homebrew JDK 21. Без JAVA_HOME gradlew падает сразу.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export JAVA_HOME

grep_rules() {
  echo "== [2/2] grep rules (K1..K5) =="
  local files hits rule id title n failed
  files=$(find shared androidApp desktopApp -type f -name '*.kt' -not -path '*/build/*' | sort)
  if [ -z "$files" ]; then
    echo "OK: kotlin-источников ещё нет — grep-правила пропущены"
    return 0
  fi

  # Один проход по всем .kt: каждое нарушение печатается как "ID\tфайл:строка\tтекст".
  # inComment — аналог inLineComment из scripts/check.mjs: матч внутри // -комментария не считается.
  hits=$(awk '
    function inComment(s, pos) { c = index(s, "//"); return (c != 0 && c < pos) }
    {
      if (FILENAME ~ /src\/(common|jvm|android)Test\//) next
      # import-строки не нарушают контентных правил (аналог import-исключения R1 в check.mjs)
      if ($0 ~ /^[[:space:]]*import[[:space:]]/) next

      if (FILENAME !~ /core\/network\// && match($0, /HttpClient\(/) && !inComment($0, RSTART))
        print "K1\t" FILENAME ":" FNR "\t" $0

      if (match(tolower($0), /unsplash\.com/) && !inComment($0, RSTART))
        print "K2\t" FILENAME ":" FNR "\t" $0

      if ((match($0, /"[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/) || match($0, /toISOString\(/)) && !inComment($0, RSTART))
        print "K3\t" FILENAME ":" FNR "\t" $0

      if (FILENAME !~ /core\/storage\// && FILENAME !~ /\/data\// && match($0, /Room\.databaseBuilder/) && !inComment($0, RSTART))
        print "K4\t" FILENAME ":" FNR "\t" $0

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
    "raw HttpClient() outside core.network (аналог R1)"
    "unsplash.com URL (аналог R3)"
    "date-with-time in request body (аналог R7, каркас)"
    "Room.databaseBuilder outside core.storage|data (БД мимо репозитория, каркас)"
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

if [ "${1:-}" = "--grep-only" ]; then
  grep_rules
  exit $?
fi

echo "== [1/2] gradle: :shared:compileAndroidMain :shared:compileKotlinJvm :desktopApp:compileKotlin :shared:jvmTest =="
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
exit 0
