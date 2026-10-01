# Crash-базлайн перед раскаткой (Stage 12 п.7, VAL-REL-006)

**Исполнение — user-gated там, где нужен доступ к консолям/проду.** Этот
документ определяет метрики, пороги остановки и базу, с которой сравнивается
каждая ступень `release-gate-rollout.md`.

## 1. Телеметрия: что подключено

**Crashlytics в сборке** (`kmp/androidApp`, коммит M12 release-rollout-prep):

| Элемент | Значение |
|---|---|
| SDK | `com.google.firebase:firebase-crashlytics`, версию задаёт BoM `firebaseBom = 34.19.0` (главный модуль, НЕ -ktx: KTX сняты с BoM с 34.0.0, на старых ktx теряется `mapping_file_id` — firebase-android-sdk #8564) |
| Gradle-плагин | `com.google.firebase.crashlytics` **3.0.8** (пин в `kmp/gradle/libs.versions.toml`), применяется только в `:androidApp` |
| Mapping-файлы (деобфускация R8) | `mappingFileUploadEnabled = true` для release — ЗАДАНО ЯВНО: AGP 9.3.1 сам не регистрирует `uploadCrashlyticsMappingFile<Variant>` (#8545, фикс AGP с 9.3.3; пин AGP 9.3.1 не бампается миссией) |
| Native-символы (Stage 12 п.7 «загрузка символов нативных библиотек») | `nativeSymbolUploadEnabled = true` для release: libsqliteJni.so (sqlite-bundled), libdatastore_shared_counter.so, libandroidx.graphics.path.so |
| Debug-сборки | `isCrashlyticsCollectionEnabled = false` в `GreenThumbApplication` (FLAG_DEBUGGABLE): пробы валидаторов на эмуляторе не попадают в базлайн релиза |
| Инициализация | автоматическая (`FirebaseInitProvider` от google-services, тот же `google-services.json`, проект `greenthumb-5e3df`, пакет `com.greenthumbplantcare`) |

**User-gated шаги Firebase Console** (воркеры не исполняют):

1. ☐ Firebase Console → проект `greenthumb-5e3df` → **Crashlytics** →
   «Enable Crashlytics» для приложения (первое открытие консоли доусловно
   предлагает включить сервис; без этого шага upload-задачи сборки и доставка
   крашей не работают).
2. ☐ После включения — проверить загрузку символов первой release-сборкой:
   сборка должна выполнить `uploadCrashlyticsMappingFileRelease` /
   `uploadCrashlyticsSymbolFileRelease` без ошибок; в консоли
   Crashlytics → вкладка «Mapping files» / «dSYM & .sym file» появляются записи
   для `com.greenthumbplantcare`.
   Замер сухого прогона (2026-10-01, до console-шага): gradle-задачи
   `generateCrashlyticsSymbolFileRelease` + `uploadCrashlyticsSymbolFileRelease`
   выполняются успешно (exit 0), `uploadCrashlyticsMappingFileRelease` —
   NO-SOURCE (mapping-файла нет без R8/minify — норма; при включении R8
   загрузка уже настроена `mappingFileUploadEnabled = true`). Остаток —
   подтверждение ВИДИМОСТИ символов/mapping в консоли после её включения.
3. ☐ Тестовый краш: на internal-сборке (не на личном телефоне — стоп M8,
   AGENTS.md) вызвать краш и убедиться, что он появился в консоли Crashlytics
   и деобфусцирован. Рецепт краша — на усмотрение пользователя (например,
   временный debug-вызов в dev-ветке с последующим удалением).

## 2. Базлайн (что с чем сравнивается)

Метрики плана (Stage 12 п.7): **доля сессий с крашем** и **доля сессий с ANR**.

| Эпоха | Источник чисел |
|---|---|
| Expo (база, versionCode ≤ 5) | **Android Vitals** в Play Console (у Expo-сборки Crashlytics нет): Production/Testing → Statistics/Vitals → crash rate, ANR rate по версиям |
| KMP (ступени раскатки) | **Crashlytics**: crash-free sessions / sessions; ANR: Vitals (Crashlytics-ANR сигнал выводится из того же Play-контура) + сессии Crashlytics |

**Таблица базлайна (заполняет пользователь перед выходом из internal-трека;**
значения берутся за 14 дней до даты гейта):

| Метрика | Значение базлайна | Дата замера | Где взято |
|---|---|---|---|
| Доля сессий с крашем (Expo, Vitals) | ______ | ______ | Play Console → Vitals → Crash rate |
| Доля сессий с ANR (Expo, Vitals) | ______ | ______ | Play Console → Vitals → ANR rate |
| Активные установки всего | ______ | ______ | Play Console → Statistics |
| Доля активных на versionCode ≥ 5 | ______ | ______ | Active devices by app version (это и есть вход гейта VAL-REL-002) |

Пока поля не заполнены, раскатка не выходит из internal-трека даже при
формально закрытом гейте адопции: сравнивать ступени не с чем.

**Честное ограничение:** до первой release-сборки KMP Crashlytics не имеет
данных; «базлайн KMP» на первой ступени = сами пороги ниже, а не относительное
сравнение. Сравнение с базой Expo становится возможным со второй ступени.

## 3. Пороги остановки (числами, не на глаз)

Любой порог превышен на ступени → **halt** (`release-gate-rollout.md`) и
`incident-runbook.md`. Продвижение разрешено только если на ступени набран
минимум наблюдения (там же, таблица лестницы).

| # | Метрика | Порог остановки | Измерение |
|---|---|---|---|
| 1 | Доля сессий с крашем (Crashlytics) | > 0.5% **или** выше базлайна Expo более чем на 0.3 п.п. | Crashlytics → Sessions; автоматично |
| 2 | Доля сессий с ANR | > 0.47% (порог «плохого поведения» Vitals) **или** выше базлайна на 0.3 п.п. | Vitals; автоматично |
| 3 | Доля запусков с экраном «ключ не найден» на установках-обновлениях | > 1% запусков | ручная проба: перечень виден только локально (телеметрии экрана нет — аналитика осознанно не подключена); фиксируется жалобами и контрольной пробой на обновлённом устройстве |
| 4 | Доля неуспешных входов | > 2% попыток | user-gated: логи `login-recovery` на VPS (ssh — пользователь); отличать 401 (неверный ключ) от 4xx/5xx/квот Neon |
| 5 | Мутации, застрявшие в журнале > 24 ч | > 1% мутаций | прямой серверной видимости нет; контроль — пробы офлайн-очереди на сборке internal-трека + жалобы «изменение не сохраняется» |
| 6 | Устройства без активной push-подписки после ≥ 7 дней на KMP (среди имевших Expo-push) | > 10% | user-gated: SELECT по `fcm_push_subscriptions`/`expo_push_subscriptions` на проде (только чтение; пользователь) |

Пороги 1–2 — автоматические (дашборд Crashlytics/Vitals). Пороги 3–6 —
первого релиза: автоматической телеметрии событий для них в приложении нет
(аналитика — P1-roadmap, вне миссии), измеряются вручную пробами и
серверными логами; это согласовано с планом как «отдельно отслеживаются».
Переопределять пороги можно только ДО раскатки.

## 4. Как это связано с kill-switch и репетицией

- Kill-switch (`min_supported_build`) не ловит краш до чтения конфига — его
  закрывают: прогон релизной сборки на чистом устройстве (чеклист гейта) +
  порог 1 этой таблицы + репетиция аварии (`incident-runbook.md`).
- Возврат на Expo-сборку закрыт (ключ перезаписан KMP-версией) — путь
  восстановления только вперёд, исправленной KMP-сборкой.
