# Гейт раскатки и staged rollout KMP-сборки (Stage 12 п.2–3, VAL-REL-002)

**Всё исполнение — user-gated.** Воркеры не входят ни в Play Console, ни в
Firebase Console, ни в EAS: каждый шаг ниже исполняет пользователь.
Канонические источники: `kmp-migration-plan.md` Stage 12 п.2–3 и п.6–7;
`missionDir/library/kill-switch.md` (механика и ограничения kill-switch);
`missionDir/library/release-checklist.md` (путь handoff-релиза versionCode 5).

## Версионный учёт

| Сборка | versionCode | versionName | Где живёт |
|---|---|---|---|
| Expo (исторические бинарики) | 2, 3 | 1.0.0 |.apk-база до handoff |
| Expo handoff-релиз (сторовая, манифест `allowBackup=false`) | **5** | 1.0.0 | internal testing |
| KMP-сборка (`kmp/androidApp`) | **6** | 0.1.0 | внутренний трек (первая публикация), затем staged rollout |

`versionCode = 6` задан в `kmp/androidApp/build.gradle.kts` и стоит выше всех
активных треков Expo (Stage 1 п.8 плана). Любая исправленная сборка после
начала раскатки — обязательно `versionCode` больше предыдущей (см.
`incident-runbook.md`).

## Зачем гейт (не сокращать и не пропускать)

Экспо-база с versionCode ≤ 4 не пишет `gt-handoff.json` (файл появился только
в handoff-релизе 5). Установка KMP поверх такой базы оставит пользователя без
ключа: KMP найдёт neither handoff, ни свой ключ — экран «ключ не найден»,
восстановить нечего. Гейт по адопции — единственная защита от того, чтобы
такие пользователи получили KMP-сборку из Play. См. Stage 12 п.2 плана и
`library/release-checklist.md` («гейт миграции»).

## Гейт: ≥95% активных установок на versionCode ≥ 5

**Правило:** KMP-сборка не уходит из internal testing в staged rollout
production, пока Play Console не покажет: **≥ 95% активных установок
приложения сидят на versionCode ≥ 5.**

**Где смотреть (Play Console):**

1. Play Console → **GreenThumb — Plant Care** → **Statistics** → метрика
   **Active devices by app version** (пользовательские метрики). Если пункт
   называется иначе в текущем интерфейсе — искать «Active devices by app
   version».
2. Дополнительно: **Testing → Internal testing → Statistics** — та же разбивка
   по версиям для трека.
3. Посчитать: `активные на ≥5 / все активные`. Порог **0.95**.

**Ожидаемый хвост:** устройства, синхронизирующиеся с Play днями/неделями
(«спящие» — см. library/release-checklist.md §2.4). Их страхуют: OTA-трек
handoff (апдейт применяется при первом запуске), модалка «сохраните ключ» и
экран KMP «ключ не найден» (ручной ввод ключа). Гейт ждёт, а не снижается.

## Перед раскаткой — предусловия (чеклист по порядку)

1. ☐ **Firebase Remote Config: параметр `min_supported_build` = 0 создан и
   ОПУБЛИКОВАН до раскатки KMP-сборки.** Firebase Console → проект
   `greenthumb-5e3df` → Remote Config → Create parameter → имя
   `min_supported_build`, тип Number (long), значение `0` → Publish. Deфолт 0 =
   выключатель не активен; поднимать значение только для отзыва конкретной
   сборки при инциденте (`incident-runbook.md`). Механика и ограничения —
   `library/kill-switch.md`.
2. ☐ **Crashlytics включён для приложения в Firebase Console** и базлайн
   зафиксирован (`crash-baseline.md`): включение сервиса — user-gated; после
   включения — прогон символов и тестовый краш (на эмуляторе/сборке
   internal-трека, не на личном телефоне).
3. ☐ **docs обновлены и запушены:** `docs/privacy.html` (в репо уже переписан
   под сбор crash-отчётов — проверен в этой же фиче; живая страница
   https://vivogf.github.io/greenthumb-mobile/privacy.html обновится ТОЛЬКО
   после `git push` пользователем).
4. ☐ **Data Safety в Play Console обновлена:** App content → Data Safety →
   добавить сбор **Crash logs** и **Diagnostics/performance data** (Crashlytics,
   Google-процессор). До этого шага сторовая декларация расходится с фактом.
5. ☐ **Гейт адопции ≥95% на versionCode ≥ 5** — закрыт (см. выше).
6. ☐ **Closed testing 20+ тестеров × 14 дней закрыт** (требование
   Personal-аккаунта перед Production; см. Phase 8 в CLAUDE.md — applies к
   приложению в целом, не только к Expo-сборке).
7. ☐ Релизная сборка KMP прогнана на чистом устройстве до раскатки
   (architecture.md §12: краш до чтения конфига kill-switch не ловит).

## Staged rollout: 5% → 100%

Лестница плана (Stage 12 п.6): Android-бета по внутреннему треку → гейт по
адопции handoff → Android 5% → … → 100%. Каждая ступень — новый release в
Production с процентом раскатки:

| Ступень | Минимум наблюдения | Критерий перехода |
|---|---|---|
| 5% | ≥ 48 ч **и** ≥ 50 сессий | все пороги `crash-baseline.md` соблюдены |
| 10% | ≥ 48 ч **и** ≥ 50 новых сессий | то же |
| 20% | ≥ 48 ч **и** ≥ 50 новых сессий | то же |
| 50% | ≥ 72 ч **и** ≥ 100 новых сессий | то же |
| 100% | — | — |

Числа — пороги первого релиза (база ~десятки установок; «минимум сессий»
защищает от выводов по пустым данным, «минимум часов» — от выводов по одному
пиковому окну напоминаний ~09:00 MSK). Перед раскаткой их можно переопределить,
но любое переопределение — до, а не по ходу.

**Механика (Play Console):**

1. Testing → Internal testing → Create new release → загрузить AAB
   (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   ./gradlew -p kmp :androidApp:bundleRelease`, см. подписание в
   `incident-runbook.md`) → release notes → rollout на тестеров.
2. После гейта: Production → Create new release → загрузить тот же AAB →
   **Staged rollout 5%** → Roll out.
3. Следующая ступень: Production → Releases → у активного релиза → **Update
   rollout percentage** → следующее значение. Новые AAB для ступеней не нужны,
   пока нет новых коммитов.
4. Проверка доли: Statistics → Active devices by app version (production).

**Остановка раскатки:** Production → Releases → активный релиз → **Halt staged
rollout**. Уже обновившиеся установки остаются на версии (staged rollout
версию не возвращает) — их обслуживают kill-switch (экран «обновите
приложение») и `incident-runbook.md`. Никогда не советовать переустановку.

## Критерии остановки (канонический источник — crash-baseline.md)

Любой порог превышен на текущей ступени → раскатка останавливается (halt),
дальше — `incident-runbook.md`. Полный пороговый лист с процедурами измерения —
в `crash-baseline.md`; кратко: доля сессий с крашем и с ANR не выше базовых,
«ключ не найден» на установках-обновлениях ~0, неуспешные входы, застрявшие
мутации журнала, устройства без push-подписки — по порогам.

## Порядок вывода после 100% (план п.9 — не эта фича)

«100% раскатки» = 100% права на обновление, не 100% обновившихся. Окно
поддержки Expo-эндпоинтов и удаление Expo-исходников — отдельное решение
пользователя после окна (в миссии не исполняется).
