# GreenThumb KMP — Android + JVM desktop

Kotlin Multiplatform + Compose Multiplatform перенос приложения GreenThumb.
Активная разработка ведётся здесь; Expo-приложение в корне репозитория — легаси
(см. корневой README).

Все команды ниже — из каталога `kmp/`, если не указано иное.

## Требования

- **JDK 21** — `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  обязателен в каждом вызове `./gradlew` (`scripts/check.sh` подставляет этот
  путь сам, если `JAVA_HOME` не задан).
- **Android SDK** — переменная `ANDROID_HOME` указывает на Android SDK
  (по умолчанию на macOS — `~/Library/Android/sdk`); `adb` и `emulator` не в
  PATH — использовать полные пути от `$ANDROID_HOME`.
- **Эмулятор** — AVD `elt_test` (system image API 34, `google_apis`). Нужен
  только для установки APK и Maestro-смоука; на машине максимум один эмулятор.
- **Maestro 2.10.0** (опционально) — `/opt/homebrew/bin/maestro`; без него гейт
  просто пропускает шаг проверки синтаксиса флоу.
- **Python 3** — для `scripts/i18n_check.py` в гейте.

## Модули

- **`shared/`** — весь общий код (`commonMain` + `androidMain`/`jvmMain`,
  тесты в `jvmTest`):
  - `core.network` — `ApiClient` (Ktor, таймаут 10 с, cookies в памяти) и
    `ApiError`; единственный путь в сеть (grep-правило K1);
  - `core.storage` — SecureStore (AES-GCM + AndroidKeyStore; на JVM — файл),
    `AppSettings` (DataStore: язык, тема, сетка, `cached_user`), Room
    `GreenThumbDb` (база per-user `plants_${userId}.db`);
  - `core.platform` — expect/actual: Connectivity, RemoteKillSwitch, Haptics,
    pickImage/resizeJpeg и др. (desktop — заглушки там, где платформы нет);
  - `data/` — `PlantRepository`, журнал офлайн-мутаций, `WateringStatus`,
    `RefreshCoordinator`;
  - `ui.theme` / `ui.components` / `ui.screens.*` (welcome, login,
    enablenotifications, dashboard, addplant, plantdetail, profile, update,
    gallery) / `ui.nav`; строки en/ru — в `composeResources`.
- **`androidApp/`** — тонкая оболочка: `MainActivity`, `GreenThumbApplication`,
  `GtFirebaseMessagingService`, `google-services.json`, backup-правила
  (`full_backup_content.xml`, `data_extraction_rules.xml` — из них исключены
  файлы с recovery key). `applicationId com.greenthumbplantcare`,
  `versionCode 6` / `versionName 0.1.0`.
- **`desktopApp/`** — JVM-харнесс: `main.kt` (GUI-запуск, hot reload
  `hotRun`/`hotMcpServer`), `RealApiProbe.kt` (ручная проба живого API).
- **`scripts/`** — `check.sh` (гейт), `grep-rules-selftest.sh`,
  `i18n_check.py`, `mcp-driver.mjs`.
- **`maestro/`** — `smoke.yaml` + `render-smoke.sh`, `delete-account.yaml` +
  `render-delete-account.sh`.

## Сборка и запуск

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# Debug APK Android
./gradlew :androidApp:assembleDebug
# → androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Установка на эмулятор (все adb — строго с -s emulator-5554:
# физический телефон, если подключён, не адресуется)
$ANDROID_HOME/platform-tools/adb -s emulator-5554 install -r \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Для смоука нужна установка со «свежей» меткой времени (интро-карусель):
`adb uninstall com.greenthumbplantcare` и затем `install` того же APK. Установка
`install -r` поверх стоящего пакета — вторая валидная стартовая ветка (экран
«Key not found»), смоук проходит обе.

Desktop-приложение:

```bash
./gradlew :desktopApp:run          # обычный GUI-запуск
./gradlew :desktopApp:hotRun --auto  # харнесс с hot reload (для разработки UI)
```

## Тесты и проверки

```bash
bash scripts/check.sh          # полный гейт kmp-кода
bash scripts/check.sh --grep-only   # только grep-правила (быстрая итерация)
./gradlew :shared:jvmTest      # только юнит-тесты (часто UP-TO-DATE;
                               # честный повтор — ./gradlew :shared:jvmTest --rerun)
```

`scripts/check.sh` (замена `npm run check` для `kmp/`) покрывает четыре шага:

1. компиляция `:shared:compileAndroidMain`, `:shared:compileKotlinJvm`,
   `:desktopApp:compileKotlin` + тесты `:shared:jvmTest`;
2. grep-правила K1–K5: сеть только через `core.network`, запрет unsplash-URL,
   даты `YYYY-MM-DD` в телах запросов, БД только через `PlantRepository`,
   UI-литералы (`.dp`, `Color(`, `.sp`) только вне `ui/screens/**`;
3. синтаксис Maestro-флоу `maestro/smoke.yaml` (без установленного maestro шаг
   пропускается; `GT_SKIP_MAESTRO=1` — пропустить явно);
4. i18n-паритет: множества ключей `strings.xml` en/ru покрывают i18n-набор.

Maestro smoke на эмуляторе (нужны запущенный AVD `elt_test` и **уже
установленный** debug APK):

```bash
bash maestro/render-smoke.sh run
```

Флоу проходит пять сценариев: вход по recovery key, добавление растения без
фото, полив (различающий assert «7 days overdue» → «3 days left»),
pull-to-refresh, офлайн-перезапуск со списком; обе стартовые ветки (интро и
«Key not found»). **Recovery key подставляется только из key-файла**
(редиректом, не через env/argv/логи); скрипт фильтрует вывод Maestro от эха
введённого ключа и удаляет временные копии — ключ не печатается никогда.

Отдельно: флоу удаления аккаунта
`bash kmp/maestro/render-delete-account.sh run <key-file>` — **удаляет аккаунт
этого ключа**, запускать только с отдельным тестовым аккаунтом.

## Ключевая механика

- **Хранилище и сессия.** SecureStore (AES-GCM + AndroidKeyStore; на JVM —
  файл) + `AppSettings` на DataStore. Офлайн-сессия: если сеть недоступна на
  старте, а `cached_user` есть — приложение работает из Room с полосой «нет
  сети».
- **Импорт из Expo-handoff.** Handoff-релиз Expo (versionCode 5) пишет
  `gt-handoff.json` (recovery key + настройки) в `filesDir`; KMP читает и
  удаляет его при первом запуске. Нет ни handoff, ни своего ключа — экран
  «Key not found». Файлы с ключом исключены из Android backup и device transfer.
- **Офлайн-очередь мутаций.** Room-база per-user + журнал `pending_mutations`:
  при Network/Timeout изменение остаётся в очереди и досылается при появлении
  сети (UI честно говорит «выполнится после подключения»); при определённом
  отказе сервера (4xx/5xx) — откат из снимка.
- **FCM push + deep link.** Канал `default` («Plant Care Reminders», HIGH);
  уведомления в форграунде показывает само приложение; deep link по
  `data.plant_id` открывает `plant/{id}`.
- **Kill-switch.** Firebase Remote Config `min_supported_build` сравнивается с
  versionCode установки: при блокировке — экран «Обновите приложение» с кнопкой
  в Google Play. Fail-open: любой сбой или отсутствие параметра → 0 (не
  блокирует); debug-сборки могут переопределять значением из файла
  `files/debug_min_supported_build`.
- **Удаление аккаунта в приложении.** Экран подтверждения →
  `DELETE /api/auth/account` → полная локальная чистка (требование Google Play).
- **Crashlytics.** Подключён в `:androidApp` (для release загружаются mapping и
  native-символы); debug-сборки отчёты не отправляют
  (`isCrashlyticsCollectionEnabled = false`) — пробы не попадают в базлайн.

## Релиз

Полный контракт раскатки — в `kmp/docs/`:

- `kmp/docs/release-gate-rollout.md` — гейт ≥95% активных установок на
  versionCode ≥ 5 и лестница staged rollout 5→10→20→50→100%;
- `kmp/docs/crash-baseline.md` — crash-базлайн и пороги остановки раскатки;
- `kmp/docs/incident-runbook.md` — действия при аварии после раскатки.

Шаги перед раскаткой, которые исполняет **владелец** (Play/Firebase Console,
git push — не автоматизируются агентами):

1. Firebase Console: включить Crashlytics; создать и **опубликовать** параметр
   Remote Config `min_supported_build` = 0 (выключатель выключен) **до**
   раскатки; заполнить crash-базлайн (`crash-baseline.md`) — без него из
   internal-трека не выходить.
2. `git push` — публичные страницы `docs/` (privacy и т.п.) на GitHub Pages
   обновятся только после пуша.
3. Play Console: Data Safety — добавить crash logs/diagnostics; загрузить KMP
   AAB во внутренний трек; закрытое тестирование 20 тестеров × 14 дней
   (Personal-аккаунт); staged rollout по лестнице из
   `release-gate-rollout.md`.

**Никогда не советуйте переустановку приложения** — на Android переустановка
уничтожает единственный recovery key, и аккаунт потеряется навсегда. Правильный
совет — «обновите приложение в Google Play» (обновление данные не трогает).
