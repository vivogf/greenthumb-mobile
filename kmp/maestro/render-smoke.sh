#!/bin/bash
# Рендер временной копии `smoke.yaml` с recovery key аккаунта id 80
# (Stage 11 п.4, фича kmp-maestro-smoke) И защита stdout от утечки ключа.
#
# Правила, которые скрипт обязан соблюдать (AGENTS.md «Расширение на M11»,
# library/user-testing.md «Защита ключа»):
#   * ключ читается ТОЛЬКО редиректом из `/tmp/gt-utval/m8ut.key` — файл
#     не печатается, не попадает в env, не идёт в argv процесса;
#   * настоящий ключ не попадает в репозиторий: в `kmp/maestro/smoke.yaml`
#     лежит плейсхолдер `${GT_RECOVERY_KEY}`, замена делается только в копии;
#   * ВЫВОД MAESTRO ФИЛЬТРУЕТСЯ. Раннер Maestro 2.10.0 печатает введённый текст
#     в stdout строкой `Input text <значение>` — прогон без фильтра пишет
#     recovery key в терминал, в лог CI и в debug-артефакты. `redact()`
#     ниже заменяет значение ключа на `<GT_RECOVERY_KEY>` во всём потоке;
#   * копия и её каталог 0600/0700; копия и `--debug-output` удаляются после
#     прогона (trap + подкоманда `cleanup`).
#
# Использование:
#   render-smoke.sh run     # копия + maestro test (stdout отфильтрован) + чистка
#   render-smoke.sh render   # только копия (печатается путь, не ключ)
#   render-smoke.sh cleanup  # удалить копию и debug-вывод
#   render-smoke.sh selftest # проверить фильтр на фиктивном значении
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)/smoke.yaml"
KEY_FILE="/tmp/gt-utval/m8ut.key"
OUT_DIR="/tmp/gt-m11"
OUT_FLOW="$OUT_DIR/smoke.rendered.yaml"
DEBUG_OUT="$OUT_DIR/maestro-debug"
RUN_LOG="$OUT_DIR/maestro-run.log"
MAESTRO="/opt/homebrew/bin/maestro"
EMULATOR="emulator-5554"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

cleanup() {
    # Копия с ключом, debug-вывод Maestro и лог прогона удаляются всегда
    # (trap + ручной вызов). Лог тоже содержит эхо `Input text`.
    rm -f "$OUT_FLOW" "$RUN_LOG"
    rm -rf "$DEBUG_OUT"
}

render() {
    [ -f "$SRC" ] || { echo "FATAL: нет исходного флоу $SRC" >&2; exit 1; }
    [ -r "$KEY_FILE" ] || { echo "FATAL: key-файл не читаем: $KEY_FILE" >&2; exit 1; }
    mkdir -p "$OUT_DIR"
    chmod 700 "$OUT_DIR"
    # Ключ подставляется awk'ом, читающим key-файл через getline из
    # дескриптора "<key" — редирект внутри awk, ключ не в argv и не в env.
    KEY_PATH="$KEY_FILE" OUT_PATH="$OUT_FLOW" awk '
        BEGIN {
            key = ""
            while ((getline line < ENVIRON["KEY_PATH"]) > 0) {
                gsub(/\r/, "", line)
                gsub(/^[ \t]+|[ \t]+$/, "", line)
                if (line != "") { key = line; break }
            }
            close(ENVIRON["KEY_PATH"])
            if (key == "") { print "FATAL: key-файл пуст" > "/dev/stderr"; exit 1 }
        }
        {
            # Подстановка только плейсхолдера; экранируем спецсимволы regex.
            gsub(/\$\{GT_RECOVERY_KEY\}/, key)
            print
        }
    ' "$SRC" > "$OUT_FLOW"
    chmod 600 "$OUT_FLOW"
}

# Фильтр stdout/stderr Maestro: значение ключа заменяется плейсхолдером.
# Ключ передаётся в awk через ФАЙЛ (KEY_PATH), не через argv и не через env:
# иначе он осел бы в списке процессов.
redact() {
    KEY_PATH="$KEY_FILE" awk '
        BEGIN {
            key = ""
            while ((getline line < ENVIRON["KEY_PATH"]) > 0) {
                gsub(/\r/, "", line)
                gsub(/^[ \t]+|[ \t]+$/, "", line)
                if (line != "") { key = line; break }
            }
            close(ENVIRON["KEY_PATH"])
        }
        {
            if (key != "") {
                gsub(key, "<GT_RECOVERY_KEY>")
                # Общий UUID-фильтр на случай другой формы эха.
                gsub(/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/, "<REDACTED-UUID>")
            }
            print
            fflush()
        }
    '
}

selftest() {
    # Фиктивное значение: проверяем, что фильтр съедает и «Input text X»,
    # и голое вхождение. Реальный ключ не участвует.
    local fake="deadbeef-0000-1111-2222-333344445555"
    local sample="Tap on ... COMPLETED
Input text $fake... COMPLETED
Assert that ... is visible... COMPLETED
key in tree: $fake"
    printf '%s' "$fake" > /tmp/gt-m11/.selftest.key
    chmod 600 /tmp/gt-m11/.selftest.key
    printf '%s\n' "$sample" | KEY_PATH=/tmp/gt-m11/.selftest.key redact
    rm -f /tmp/gt-m11/.selftest.key
}

case "${1:-run}" in
    render)
        render
        echo "отрендерено: $OUT_FLOW"
        ;;
    cleanup)
        cleanup
        echo "временная копия, debug-вывод и лог прогона удалены"
        ;;
    selftest)
        selftest
        ;;
    run)
        trap cleanup EXIT
        render
        # stdout/stderr идут через фильтр; сырой вывод не сохраняется нигде.
        set +e
        "$MAESTRO" test \
            --udid "$EMULATOR" \
            --no-ansi \
            --debug-output "$DEBUG_OUT" \
            "$OUT_FLOW" 2>&1 | tee "$RUN_LOG" | redact
        status=${PIPESTATUS[0]}
        set -e
        echo "maestro exit code: $status"
        exit "$status"
        ;;
    *)
        echo "usage: $0 [run|render|cleanup|selftest]" >&2
        exit 2
        ;;
esac
