#!/bin/bash
# Рендер временной копии `delete-account.yaml` с recovery key тест-аккаунта
# удаления (Stage 12 п.4, фича kmp-account-deletion-ui, VAL-REL-003) И защита
# stdout от утечки ключа. Механика — render-smoke.sh (фича kmp-maestro-smoke);
# отличия:
#   * путь key-файла — ПЕРВЫЙ аргумент (путь в argv безопасен, сам ключ —
#     нет); ключ не печатается, не идёт в env и не в argv процессов;
#   * аккаунт флоу УДАЛЯЕТ (вход тем же ключом после прогона даёт 401),
#     поэтому прогон самодостаточен: нового остатка на проде не оставляет.
#
# Использование:
#   render-delete-account.sh run <key-file>   # копия + maestro test + чистка
#   render-delete-account.sh cleanup          # удалить копию и debug-вывод
set -euo pipefail

KEY_FILE="${2:-}"
[ -n "$KEY_FILE" ] || { echo "usage: $0 run <key-file>" >&2; exit 2; }
SRC="$(cd "$(dirname "$0")" && pwd)/delete-account.yaml"
OUT_DIR="/tmp/gt-m12del"
OUT_FLOW="$OUT_DIR/delete-account.rendered.yaml"
DEBUG_OUT="$OUT_DIR/maestro-debug"
PRESERVE_DIR="${GT_EVIDENCE_DIR:-}"
RUN_LOG="$OUT_DIR/maestro-run.log"
MAESTRO="/opt/homebrew/bin/maestro"
EMULATOR="emulator-5554"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

cleanup() {
    # Копия с ключом, debug-вывод Maestro и лог прогона удаляются всегда
    # (trap + ручной вызов). Лог содержит эхо `Input text` — не сохраняется.
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
            gsub(/\$\{GT_RECOVERY_KEY\}/, key)
            print
        }
    ' "$SRC" > "$OUT_FLOW"
    chmod 600 "$OUT_FLOW"
}

# Фильтр stdout/stderr Maestro: значение ключа заменяется плейсхолдером.
# Ключ передаётся в awk через ФАЙЛ (KEY_PATH), не через argv и не через env.
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
                gsub(/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/, "<REDACTED-UUID>")
            }
            print
            fflush()
        }
    '
}

case "${1:-}" in
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
        # Скриншоты подтверждения/логина (на них ключа нет) — в evidence
        # миссии ДО удаления debug-вывода. Maestro 2.10.0 складывает их в
        # debug-output/.maestro/tests/<timestamp>/ (наблюдение прогона).
        mkdir -p "$PRESERVE_DIR"
        found_shots=$(find "$DEBUG_OUT" -type f \( -name 'delete-confirm-screen.png' -o -name 'login-after-delete.png' \) 2>/dev/null || true)
        if [ -n "$found_shots" ]; then
            echo "$found_shots" | while IFS= read -r shot; do cp -f "$shot" "$PRESERVE_DIR"/; done
            echo "скриншоты сохранены: $PRESERVE_DIR"
        else
            echo "ВНИМАНИЕ: скриншоты в debug-выводе не найдены" >&2
        fi
        echo "maestro exit code: $status"
        exit "$status"
        ;;
    cleanup)
        cleanup
        echo "временная копия, debug-вывод и лог прогона удалены"
        ;;
    *)
        echo "usage: $0 [run <key-file>|cleanup]" >&2
        exit 2
        ;;
esac
