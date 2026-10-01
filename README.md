# GreenThumb

Мобильное приложение для ухода за растениями: напоминания о поливе и удобрениях,
фото растений, офлайн-работа. Пакет Android — `com.greenthumbplantcare`.

**Бэкенд** — `https://greenthumb.xmpp.site` (Express + Drizzle + Neon PostgreSQL),
код в отдельном репозитории: [vivogf/GreenThumb](https://github.com/vivogf/GreenThumb).

## Что здесь лежит

- **`kmp/` — активное приложение** (Kotlin Multiplatform + Compose Multiplatform):
  Android и JVM desktop (desktop — dev-харнесс с hot reload, не для релиза).
  Переход сюда с Expo завершён; всё новое разрабатывается здесь.
  См. [kmp/README.md](kmp/README.md).
- **Корень репозитория — легаси Expo-приложение** (Expo SDK 55 / React Native).
  По решению пользователя от 2026-10-01 оно **больше не выпускается** и хранится
  только для истории и как источник handoff-переноса recovery key (Stage 0:
  `gt-handoff.json`). Не развивается.

## Документация

- [kmp/README.md](kmp/README.md) — сборка, запуск, тесты, устройство проекта.
- [kmp/docs/](kmp/docs/) — релизные документы KMP-сборки:
  [release-gate-rollout.md](kmp/docs/release-gate-rollout.md) (гейт раскатки и
  staged rollout), [crash-baseline.md](kmp/docs/crash-baseline.md) (пороги
  остановки), [incident-runbook.md](kmp/docs/incident-runbook.md) (действия при
  аварии).
- [docs/](docs/) — публичные страницы, они же публикуются через GitHub Pages:
  [privacy.html](https://vivogf.github.io/greenthumb-mobile/privacy.html) и
  [account-deletion.html](https://vivogf.github.io/greenthumb-mobile/account-deletion.html).
