#!/usr/bin/env python3
"""VAL-I18N-003: паритет ключей i18n между RN-локамилями и Compose Resources.

Сверяет множества ключей:
  - источник — RN i18n/locales/{en,ru}.json (канонический перевод, ~270 ключей);
  - приёмник — kmp/shared/src/commonMain/composeResources/values/strings.xml (en,
    дефолт) и values-ru/strings.xml.

Правила конверсии (Stage 6 п.4, plan Stage 6):
  - ключ с точками a.b → имя ресурса a_b;
  - ru-плюрализм: base_0→one, base_1→few, base_2→many, base→other (<plurals>);
  - en-плюрализм: base→one, base_plural→other (<plurals>);
  - интерполяция {{count}} → %1$d, {{любое}} → %1$s;
  - экранирование: \\\\ \\n \\t (& → &amp; обязателен XML-правилами); \\' и \\" запрещены —
    Compose Resources показывают обратный слэш буквально, кавычки пишутся как есть.
  - RN_STRINGS_AS_KMP_PLURALS: RN-ключ-строка → KMP <plurals> (см. константу).

Разрешённые надмножества RN (ключи, которых в RN нет):
  - session.* — экран «ключ не найден» (SessionState.KeyNotFound, Stage 3 п.6;
    в RN-версии экрана нет). Требуются в ОБОИХ локалях.

Ещё одна пара — kmp/androidApp/src/main/res/values{,-ru}/*.xml (тексты виджета,
architecture §5.8): одинаковые переводимые ключи, без пустых текстов, с теми же
плейсхолдерами. RN-аналогов у них нет.

Любое расхождение (пропуск, лишний ключ, несовпадение текста/интерполяции,
несимметричность en/ru) — exit 1 со списком проблем.
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
RN_LOCALES = REPO_ROOT / "i18n" / "locales"
RES_DIR = (
    SCRIPT_DIR.parent
    / "shared"
    / "src"
    / "commonMain"
    / "composeResources"
)

# Ключи KMP, которых нет в RN-локалях (не пропуск — осознанное расширение).
# Каждое имя обязано присутствовать в ОБОИХ strings.xml.
KMP_ONLY_KEYS = {
    # M11 owner fixes: short dashboard filter label, reminders time row in the same card.
    "dashboard.waterFilter",
    "profile.notificationEveryDay",
    "profile.notificationLocalTime",
    # journal.* — care journal on plant detail (architecture §5.5); RN has none.
    "journal.title",
    "journal.typeWater",
    "journal.typeFertilize",
    "journal.typeRepot",
    "journal.typePrune",
    "journal.typeNote",
    "journal.typePhoto",
    "journal.typeOther",
    "journal.empty",
    "journal.notePlaceholder",
    "journal.addNote",
    # M12a UX5: the dated journal composer and the persistent plant notes get distinct labels.
    "journal.entryLabel",
    "plantDetails.aboutPlant",
    "journal.networkRequired",
    "journal.saveFailed",
    "journal.deleteNote",
    "journal.deleteTitle",
    "journal.deleteMessage",
    "journal.deleteFailed",
    # timeline.* — photo timeline on plant detail (architecture §5.5); RN has none.
    "timeline.title",
    "timeline.addPhoto",
    "timeline.empty",
    "timeline.openPhoto",
    "timeline.captionTitle",
    "timeline.captionPlaceholder",
    "timeline.save",
    "timeline.uploading",
    "timeline.networkRequired",
    "timeline.uploadFailed",
    "timeline.close",
    "timeline.deletePhoto",
    "timeline.deleteTitle",
    "timeline.deleteMessage",
    "timeline.deleteFailed",
    # species.* — offline species catalog (architecture §5.4); RN has none.
    "species.choose",
    "species.change",
    "species.clear",
    "species.title",
    "species.searchHint",
    "species.noResults",
    "species.light",
    "species.lightLow",
    "species.lightMedium",
    "species.lightBrightIndirect",
    "species.lightDirect",
    "species.tip",
    "species.notSet",
    "species.prefilledHint",
    # species identification by photo (architecture §5.4, §8.4).
    "species.identify",
    "species.identifyTitle",
    "species.identifyLoading",
    "species.identifyChooseHint",
    "species.identifyConfidence",
    "species.confidenceHigh",
    "species.confidenceMedium",
    "species.confidenceLow",
    "species.inCatalog",
    "species.identifyQuota",
    "species.identifyUnavailable",
    "species.identifyEmpty",
    "species.searchCatalog",
    "species.poweredBy",
    "species.plantNetLogo",
    # diagnosis.* — plant diagnosis by photo (architecture §5.7, §8.7); RN has none.
    "diagnosis.entry",
    "diagnosis.entryDescription",
    "diagnosis.photoPreview",
    "diagnosis.notePlaceholder",
    "diagnosis.loading",
    "diagnosis.resultTitle",
    "diagnosis.likelihoodLow",
    "diagnosis.likelihoodMedium",
    "diagnosis.likelihoodHigh",
    "diagnosis.advice",
    "diagnosis.disclaimer",
    "diagnosis.unavailable",
    "diagnosis.dailyLimit",
    "diagnosis.networkError",
    "diagnosis.failed",
    "diagnosis.newDiagnosis",
    "diagnosis.serviceLimit",
    "diagnosis.saveToJournal",
    "diagnosis.savedToJournal",
    "diagnosis.saveFailed",
    "diagnosis.done",
    "diagnosis.journalCaptionPrefix",
    "diagnosis.photoLoading",
    "diagnosis.photoLoadFailed",
    "plantDetails.photoCheck",
    "plantDetails.photoCheckHint",
    # vault.* — recovery key in the device password manager (architecture §5.3);
    # the RN app has no Credential Manager bridge.
    "vault.saveKey",
    "vault.saveNewKey",
    "vault.saved",
    "vault.saveFailed",
    "vault.saveUnavailable",
    "vault.signInWithSavedKey",
    "vault.noCredential",
    "vault.restoreUnavailable",
    "vault.restoreFailed",
    "vault.staleKey",
    # keyQr.* + profile.showKeyQr — recovery key QR export (architecture §5.3).
    "profile.showKeyQr",
    "keyQr.title",
    "keyQr.hint",
    "keyQr.imageDescription",
    "keyQr.screenshotsBlocked",
    "session.keyNotFoundTitle",
    "session.keyNotFoundBody",
    "session.keyNotFoundEnterKey",
    "session.keyNotFoundCreateAccount",
    # photoCrop.* / photoPick.* — Stage 8 п.1: RN отдаёт кроп нативному
    # allowsEditing (локализация системная), KMP рисует свой Compose-экран
    # кропа и файловый диалог десктопа — заголовки/подсказка переводятся здесь.
    "photoCrop.title",
    "photoCrop.hint",
    "photoPick.fileTitle",
    # photoPick.unreadable — a picked item the app cannot read or decode (VAL-APP-019);
    # datePicker.* — accessibility label and dialog title of the date field (VAL-CROSS-018).
    "photoPick.unreadable",
    "datePicker.fieldDescription",
    "datePicker.maximum",
    # session.handoffImportFailed — Expo→KMP handoff import failure screen (VAL-HANDOFF-IMP-005).
    "session.handoffImportFailed",
    # profile.regenerateWarning — RN profile.tsx:192-193 держит текст диалога
    # регенерации захардкоженным по-английски (пробел i18n RN); KMP заводит
    # ключ в обеих локалях (ru-текст собственный).
    "profile.regenerateWarning",
    # addPlant.validation.* — RN add-plant.tsx:29-49 держит тексты ошибок
    # формы захардкоженными по-русски в zod-схеме (пробел i18n RN; фича
    # screen-add-plant требует «ошибки валидации локализованы»); KMP заводит
    # ключи в обеих локалях. Тексты en — перевод хардкода RN 1:1.
    "addPlant.validation.nameRequired",
    "addPlant.validation.frequencyInvalidNumber",
    "addPlant.validation.frequencyMinimum",
    "addPlant.validation.dateRequired",
    # addPlant.section* — RN add-plant.tsx:251 держит заголовки секций с
    # эмодзи захардкоженными по-русски («💧 Полив»); KMP локализует их.
    "addPlant.sectionFertilizing",
    "addPlant.sectionRepotting",
    "addPlant.sectionPruning",
    # dashboard.refreshFailed / dashboard.databaseError — полоса ошибки
    # refresh при живом кэше и строка ошибки чтения локальной базы (KMP:
    # Room-источник истины; полноэкранная ошибка RN поверх данных — баг,
    # не переносится — VAL-DASH-009/VAL-DATA-001). В RN полосы нет.
    "dashboard.refreshFailed",
    "dashboard.databaseError",
    # M12a: first-load error with Retry (not a confirmed-empty garden) and the bulk-action scope note.
    "dashboard.loadFailed",
    "dashboard.bulkScope",
    # dashboard.unsaved — метка карточки с открытым офлайн-журналом (M4);
    # в RN офлайн-журнала нет (VAL-APP-009: раньше был ru-литерал в коде).
    "dashboard.unsaved",
    # plantDetails.deleteQueued* — честный статус отложенного удаления при
    # Network/Timeout (VAL-DETAIL-004, решение пользователя 2026-09-27): M4
    # досылает удаление после подключения, RN-алерта на такую ситуацию нет
    # (RN откатывал при любой сетевой ошибке — осознанное отличие).
    "plantDetails.deleteQueuedTitle",
    "plantDetails.deleteQueuedMessage",
    # plantDetails.winterMode — seasonal watering hint on plant detail (architecture §8.6); RN has none.
    "plantDetails.winterMode",
    # updateRequired.* — Stage 12 п.1: kill-switch (Remote Config
    # min_supported_build) — блокирующий экран обновления. В RN аналога нет:
    # OTA закрывал отзыв версии доставкой JS-апдейта (EAS Update), KMP-замена —
    # kill-switch. Ключи в обеих локалях.
    "updateRequired.title",
    "updateRequired.body",
    "updateRequired.openStore",
    # profile.deleteAccount + deleteAccount.* — Stage 12 п.4: удаление
    # аккаунта в приложении (Play-требование, VAL-REL-003). В RN нет — там
    # удаление email-путём (docs/account-deletion.html); KMP добавляет
    # строку в профиле и экран подтверждения с последствиями. Ключи в обеих
    # локалях.
    "profile.deleteAccount",
    "deleteAccount.title",
    "deleteAccount.body",
    "deleteAccount.bulletPlants",
    "deleteAccount.bulletPush",
    "deleteAccount.bulletKey",
    "deleteAccount.confirm",
    "deleteAccount.deleting",
    # profile.season* + a11y.seasonOption — seasonal watering switcher in Profile (architecture §5.6, §8.6);
    # RN has none.
    "profile.seasonTitle",
    "profile.seasonHint",
    "profile.seasonAuto",
    "profile.seasonNorth",
    "profile.seasonSouth",
    "profile.seasonOff",
    "profile.seasonAutoNorth",
    "profile.seasonAutoSouth",
    "profile.seasonAutoTropical",
    "profile.seasonAutoUnknown",
    "profile.seasonSaveFailed",
    "a11y.seasonOption",
    # progress.* + achievement.<id>.* — streaks and achievements (architecture §5.8); RN has none.
    "progress.entry",
    "progress.title",
    "progress.currentStreak",
    "progress.bestStreak",
    "progress.streakHint",
    "progress.achievements",
    "progress.unlocked",
    "progress.locked",
    "progress.cellDescription",
    "progress.loading",
    "progress.offline",
    "progress.refreshFailed",
    "progress.needsNetwork",
    "progress.retry",
    "achievement.first_plant.title",
    "achievement.first_plant.description",
    "achievement.plants_5.title",
    "achievement.plants_5.description",
    "achievement.first_water.title",
    "achievement.first_water.description",
    "achievement.waterings_25.title",
    "achievement.waterings_25.description",
    "achievement.waterings_100.title",
    "achievement.waterings_100.description",
    "achievement.streak_7.title",
    "achievement.streak_7.description",
    "achievement.streak_30.title",
    "achievement.streak_30.description",
    "achievement.first_note.title",
    "achievement.first_note.description",
    "achievement.first_photo.title",
    "achievement.first_photo.description",
    # Two-slot key rotation and session recovery (commit fdb1472): the KMP app keeps
    # the recovery key only in its own secure storage; the Expo app has no such flow.
    "profile.currentKeyLabel",
    "profile.newKeyLabel",
    "profile.keyChangeNotStored",
    "profile.keyChangeRetry",
    "profile.keyChangeSuperseded",
    "profile.keyChangeUnfinished",
    "profile.regenerateNoKey",
    "profile.regenerateUncertain",
    "profile.signOutAnyway",
    "profile.signOutSaveKeyFirst",
    "session.keyChangeFailed",
    "session.keyChangeFinished",
    "session.keyNotStored",
    "session.retryBody",
    "session.retryButton",
    "session.retryOtherKey",
    # plantDetails.photo* — «Фото растения» sheet on plant detail (M10 D§1 item 8).
    "plantDetails.photoSheetTitle",
    "plantDetails.photoNew",
    "plantDetails.photoGallery",
    # Start-screen privacy link and contact line (M10 D§1 item 2).
    "privacy.moreLink",
    "privacy.contactLead",
    # Watering line «Полить через N дней», variant B (M10 D§1 item 4); plurals are in KMP_ONLY_PLURALS.
    "plantDetails.waterToday",
    "plantDetails.lastWateredLine",
    # «Название и место» sheet, variant A (M10 D§1 items 5-6).
    "plantDetails.namePlaceTitle",
    "plantDetails.nameLabel",
    "plantDetails.placeLabel",
    "plantDetails.placeLiving",
    "plantDetails.placeKitchen",
    "plantDetails.placeBedroom",
    "plantDetails.placeBalcony",
    "plantDetails.placeBathroom",
    "plantDetails.placeOffice",
    "plantDetails.placeCustom",
    "plantDetails.placeCustomHint",
    # Care settings sheet, variant A (M10 D§1 item 7a); unit plurals are in KMP_ONLY_PLURALS.
    "plantDetails.careSheetTitle",
    "plantDetails.careWatering",
    "plantDetails.careAlwaysOn",
    "plantDetails.careOn",
    "plantDetails.careOff",
    "plantDetails.careHowOften",
    "plantDetails.careDecrease",
    "plantDetails.careIncrease",
    "plantDetails.careRejected",
    "plantDetails.careServerError",
    "plantDetails.careOffline",
    "plantDetails.careQueuedTitle",
    "plantDetails.careQueuedBody",
    # Smart camera (M10 D§ item 9a).
    "smartcam.title",
    "smartcam.modePlant",
    "smartcam.modeDisease",
    "smartcam.hintPlant",
    "smartcam.hintDisease",
    "smartcam.choosePhoto",
    "smartcam.addToMine",
    "smartcam.justLook",
    "smartcam.retake",
    "smartcam.backToPhoto",
    "smartcam.noMatch",
    "smartcam.notInList",
    "smartcam.isMine",
    "smartcam.pickPlant",
    "smartcam.chosenPlant",
    "smartcam.plantChipClear",
    "smartcam.plantCare",
    "smartcam.zoomReset",
    "smartcam.close",
    "smartcam.flashOn",
    "smartcam.flashOff",
    "smartcam.shutterPlant",
    "smartcam.shutterDisease",
    "smartcam.sendPlant",
    "smartcam.sendDisease",
    "smartcam.sendNotePlant",
    "smartcam.sendNoteDisease",
    "smartcam.reshoot",
    "smartcam.cameraDenied",
    "smartcam.cameraUnavailable",
    "smartcam.openSettings",
    "smartcam.systemCamera",
    "a11y.smartCamera",
    # New plant: optional recognition, «по виду» marks, watering stepper (M10 D§1 item 9b).
    "addPlant.identifyByPhoto",
    "addPlant.identifyNote",
    "addPlant.orPickSpecies",
    "addPlant.nameHint",
    "addPlant.fromSpecies",
    "addPlant.waterEvery",
    # New plant as one short form with a collapsed «Ещё параметры» (M11 item 5).
    "addPlant.submit",
    "addPlant.moreOptions",
    "addPlant.moreOptionsHint",
    "addPlant.extraCare",
    # M12a UX4: compact last-watered summary above «Ещё параметры».
    "addPlant.lastWateredShort",
    "addPlant.lastWateredToday",
    "addPlant.lastWateredChange",
}

# KMP-only <plurals> (no RN key). Each must exist in both locales with the full CLDR set of
# REQUIRED_PLURAL_QUANTITIES.
KMP_ONLY_PLURALS = frozenset({
    # Watering line on plant detail (M10 D§1 item 4).
    "plantDetails.waterIn",
    "plantDetails.waterOverdue",
    # Stepper unit words of the care settings sheet (M10 D§1 item 7a).
    "plantDetails.careDays",
    "plantDetails.careMonths",
})

# RN keys whose KMP text is deliberately rewritten (privacy wording of the KMP
# release: crash reports exist, server logs do not). The key must still exist in
# both KMP locales; only the text comparison with the Expo locale is skipped,
# because the Expo locale files belong to the Expo app and are not edited here.
KMP_REWRITTEN_TEXT_KEYS = frozenset({
    "privacy.loginHint",
    "privacy.aboutBody",
})

# RN держит эти ключи-строки как KMP <plurals> (не <string>): плюрализации
# success/pending-баннера массового полива в RN нет — t('dashboard.plantsWatered')
# без форм (баг «2 растений полито»), t('dashboard.wateringAllPending', {count})
# с захардкоженным «растений» («Поливаем 2 растений...»). KMP эти формы
# легитимно заводит: ru one/few/many/other через <plurals> +
# pluralStringResource (VAL-DASH-006, коммит a0e6f54 и его pending-продолжение).
# Паритет честный по базовому тексту: RN-строка — «общая» форма, она обязана
# совпадать с quantity "other" (ru) / "one"+"other" (en); ru дополняется
# one/few/many — текстов в RN нет, проверяется только НАЛИЧИЕ всех форм CLDR
# в обеих локалях. Суффиксные ключи в RN-JSON для этого НЕ заводить:
# i18n/locales — живые Expo-исходники, RN-рантайм их не читает.
RN_STRINGS_AS_KMP_PLURALS = frozenset({
    "dashboard.plantsWatered",
    "dashboard.wateringAllPending",
})

# Полный набор quantity KMP-плюрала поверх RN-строки (см. выше).
REQUIRED_PLURAL_QUANTITIES = {
    "ru": {"one", "few", "many", "other"},
    "en": {"one", "other"},
}

KMP_PLURAL_NAMES = frozenset(k.replace(".", "_") for k in RN_STRINGS_AS_KMP_PLURALS)

PLURAL_SUFFIX_QUANTITY_RU = {"_0": "one", "_1": "few", "_2": "many"}
PLURAL_SUFFIX_QUANTITY_EN = {"_plural": "other"}


def flatten(d, prefix=""):
    out = {}
    for k, v in d.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict):
            out.update(flatten(v, key + "."))
        else:
            out[key] = v
    return out


ESCAPED_QUOTE_RE = re.compile(r"(?<!\\)\\['\"]")


def to_res_name(dotted_key):
    return dotted_key.replace(".", "_")


def convert_interpolation(text):
    """{{count}} → %1$d; прочие {{var}} → %1$s."""
    return re.sub(
        r"\{\{(\w+)\}\}",
        lambda m: "%1$d" if m.group(1) == "count" else "%1$s",
        text,
    )


def unescape_android(text):
    """Разэкранирование Android-строковой записи в исходнике XML."""
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "u" and i + 5 < len(text):
                out.append(chr(int(text[i + 2 : i + 6], 16)))
                i += 6
                continue
            mapped = {"n": "\n", "t": "\t", "\\": "\\", "'": "'", '"': '"'}.get(nxt)
            if mapped is not None:
                out.append(mapped)
                i += 2
                continue
        out.append(c)
        i += 1
    return "".join(out)


def plural_bases(keys):
    """Базовые ключи плюральных групп: base, если base_0 (ru) / base_plural (en) есть."""
    bases = set()
    for k in keys:
        if k.endswith("_0") and k[:-2] in keys:
            bases.add(k[:-2])
        if k.endswith("_plural") and k[:-7] in keys:
            bases.add(k[:-7])
    return bases


def expected_resources(flat, lang):
    """Ожидаемое содержимое strings.xml для локали: {имя: текст | {quantity: текст}}."""
    bases = plural_bases(flat)
    strings = {}
    plurals = {}
    for key, value in flat.items():
        name = to_res_name(key)
        converted = convert_interpolation(value)
        if key in RN_STRINGS_AS_KMP_PLURALS:
            # RN-строка — «общая» форма KMP-плюрала: ru base → other
            # (Stage 6-правило), en — one+other одинаковым текстом
            # (en форм не различает). Остальные quantity добираются
            # в check_locale как KMP-only (REQUIRED_PLURAL_QUANTITIES).
            plurals[name] = (
                {"one": converted, "other": converted}
                if lang == "en"
                else {"other": converted}
            )
            continue
        base = next((b for b in bases if key == b or key.startswith(b + "_")), None)
        if base is None:
            strings[name] = converted
            continue
        plural_name = to_res_name(base)
        plurals.setdefault(plural_name, {})
        if lang == "ru":
            if key == base:
                plurals[plural_name]["other"] = converted
            else:
                suffix = key[len(base) :]
                if suffix not in PLURAL_SUFFIX_QUANTITY_RU:
                    raise SystemExit(f"ru: неожидаемый суффикс плюрала {key}")
                plurals[plural_name][PLURAL_SUFFIX_QUANTITY_RU[suffix]] = converted
        else:
            if key == base:
                plurals[plural_name]["one"] = converted
            else:
                suffix = key[len(base) :]
                if suffix not in PLURAL_SUFFIX_QUANTITY_EN:
                    raise SystemExit(f"en: неожидаемый суффикс плюрала {key}")
                plurals[plural_name][PLURAL_SUFFIX_QUANTITY_EN[suffix]] = converted
    return strings, plurals


def find_escaped_quotes(path):
    """Compose Multiplatform resources keep \\' and \\" literally (no Android
    unescaping), so the backslash shows up in the UI. Returns offending names."""
    root = ET.parse(path).getroot()
    found = []
    for el in root:
        texts = []
        if el.tag == "string":
            texts.append((el.attrib["name"], el.text or ""))
        elif el.tag == "plurals":
            for item in el:
                if item.tag == "item":
                    texts.append((f"{el.attrib['name']}#{item.attrib['quantity']}", item.text or ""))
        for name, raw in texts:
            if ESCAPED_QUOTE_RE.search(raw):
                found.append(name)
    return found


def parse_strings_xml(path):
    root = ET.parse(path).getroot()
    strings = {}
    plurals = {}
    for el in root:
        if el.tag == "string":
            strings[el.attrib["name"]] = unescape_android(el.text or "")
        elif el.tag == "plurals":
            quantities = {}
            for item in el:
                if item.tag != "item":
                    continue
                quantities[item.attrib["quantity"]] = unescape_android(item.text or "")
            plurals[el.attrib["name"]] = quantities
        else:
            raise SystemExit(f"{path.name}: неизвестный элемент <{el.tag}>")
    return strings, plurals


ANDROID_APP_RES = SCRIPT_DIR.parent / "androidApp" / "src" / "main" / "res"
PLACEHOLDER_RE = re.compile(r"%(\d+\$)?[sd]")


def android_app_strings(values_dir):
    """Translatable <string>/<plurals> of every XML file in an androidApp values dir.

    Returns {name: [texts]} (one text per string, one per plural quantity)."""
    found = {}
    for path in sorted(values_dir.glob("*.xml")):
        root = ET.parse(path).getroot()
        for el in root:
            if el.get("translatable") == "false":
                continue
            if el.tag == "string":
                found[el.attrib["name"]] = [unescape_android(el.text or "")]
            elif el.tag == "plurals":
                found[el.attrib["name"]] = [
                    unescape_android(item.text or "") for item in el if item.tag == "item"
                ]
    return found


def check_android_app_res(problems):
    """androidApp/src/main/res (widget texts, architecture §5.8): values/ and values-ru/ hold the
    same translatable keys, none empty, with the same placeholders."""
    en_dir = ANDROID_APP_RES / "values"
    ru_dir = ANDROID_APP_RES / "values-ru"
    if not ru_dir.is_dir():
        problems.append(f"androidApp: нет каталога {ru_dir}")
        return 0
    en = android_app_strings(en_dir)
    ru = android_app_strings(ru_dir)
    for name in sorted(set(en) - set(ru)):
        problems.append(f"androidApp ru: нет <string name=\"{name}\"> (есть в values/)")
    for name in sorted(set(ru) - set(en)):
        problems.append(f"androidApp en: нет <string name=\"{name}\"> (есть в values-ru/)")
    for name in sorted(set(en) & set(ru)):
        for lang, texts in (("en", en[name]), ("ru", ru[name])):
            if not texts or any(not t.strip() for t in texts):
                problems.append(f"androidApp {lang}: пустой текст {name}")
        en_ph = sorted(set(PLACEHOLDER_RE.findall(" ".join(en[name]))))
        ru_ph = sorted(set(PLACEHOLDER_RE.findall(" ".join(ru[name]))))
        if en_ph != ru_ph:
            problems.append(f"androidApp: плейсхолдеры {name} не совпадают: en={en_ph} ru={ru_ph}")
    return len(en)


def main():
    problems = []

    rn_en = flatten(json.loads((RN_LOCALES / "en.json").read_text(encoding="utf-8")))
    rn_ru = flatten(json.loads((RN_LOCALES / "ru.json").read_text(encoding="utf-8")))

    exp_en = expected_resources(rn_en, "en")
    exp_ru = expected_resources(rn_ru, "ru")

    file_en = RES_DIR / "values" / "strings.xml"
    file_ru = RES_DIR / "values-ru" / "strings.xml"
    for f in (file_en, file_ru):
        if not f.exists():
            problems.append(f"ОТСУТСТВУЕТ файл {f}")
    if problems:
        for p in problems:
            print("FAIL:", p)
        sys.exit(1)

    got_en = parse_strings_xml(file_en)
    got_ru = parse_strings_xml(file_ru)

    for f in (file_en, file_ru):
        for name in find_escaped_quotes(f):
            problems.append(
                f"{f.parent.name}/{f.name}: {name} — backslash-escaped quote (\\' or \\\"); "
                "Compose resources render the backslash literally, use a plain ' or \""
            )

    def check_locale(lang, exp, got):
        exp_strings, exp_plurals = exp
        got_strings, got_plurals = got
        # Разрешённые KMP-ключи: только ожидаемые, и в обеих локалях.
        allowed_kmp = {to_res_name(k) for k in KMP_ONLY_KEYS}
        missing = sorted(set(exp_strings) - set(got_strings))
        extra = sorted((set(got_strings) - set(exp_strings)) - allowed_kmp)
        for n in missing:
            problems.append(f"{lang}: нет <string name=\"{n}\">")
        for n in extra:
            problems.append(f"{lang}: лишний <string name=\"{n}\"> (нет в RN)")
        missing_p = sorted(set(exp_plurals) - set(got_plurals))
        kmp_only_p = {to_res_name(k) for k in KMP_ONLY_PLURALS}
        extra_p = sorted(set(got_plurals) - set(exp_plurals) - kmp_only_p)
        for n in missing_p:
            problems.append(f"{lang}: нет <plurals name=\"{n}\">")
        for n in extra_p:
            problems.append(f"{lang}: лишний <plurals name=\"{n}\">")
        rewritten = {to_res_name(k) for k in KMP_REWRITTEN_TEXT_KEYS}
        for n, txt in exp_strings.items():
            if n in rewritten:
                continue
            if n in got_strings and got_strings[n] != txt:
                problems.append(
                    f"{lang}: текст {n} не совпадает:\n  RN:  {txt!r}\n  XML: {got_strings[n]!r}"
                )
        for n, quantities in exp_plurals.items():
            if n not in got_plurals:
                continue
            got_q = got_plurals[n]
            for q, txt in quantities.items():
                if q not in got_q:
                    problems.append(f"{lang}: {n} — нет quantity \"{q}\"")
                elif got_q[q] != txt:
                    problems.append(
                        f"{lang}: {n}[{q}] не совпадает:\n  RN:  {txt!r}\n  XML: {got_q[q]!r}"
                    )
            extra = sorted(set(got_q) - set(quantities))
            if n in KMP_PLURAL_NAMES:
                # KMP-плюрал RN-строки: полный набор форм CLDR обязан быть
                # (ru one/few/many сверх RN-"other"; en one/other уже в
                # expected); лишние сверх набора — нарушение.
                required = REQUIRED_PLURAL_QUANTITIES[lang]
                for q in sorted(required - set(got_q)):
                    problems.append(
                        f"{lang}: {n} — нет quantity \"{q}\" (KMP-плюрал RN-строки)"
                    )
                extra = [q for q in extra if q not in required]
            for q in extra:
                problems.append(f"{lang}: {n} — лишний quantity \"{q}\"")
        for n in sorted(kmp_only_p):
            if n not in got_plurals:
                problems.append(f"{lang}: нет KMP-плюрала <plurals name=\"{n}\">")
                continue
            got_q = set(got_plurals[n])
            required = REQUIRED_PLURAL_QUANTITIES[lang]
            for q in sorted(required - got_q):
                problems.append(f"{lang}: {n} — нет quantity \"{q}\" (KMP-плюрал)")
            for q in sorted(got_q - required):
                problems.append(f"{lang}: {n} — лишний quantity \"{q}\"")
        # KMP-only ключи обязаны быть в обеих локалях.
        for k in KMP_ONLY_KEYS:
            n = to_res_name(k)
            if n not in got_strings:
                problems.append(f"{lang}: нет KMP-ключа <string name=\"{n}\">")
        # Остатки неподконвертированной интерполяции.
        for n, txt in {**got_strings, **{f"{k}#{q}": t for k, qs in got_plurals.items() for q, t in qs.items()}}.items():
            if "{{" in txt or "}}" in txt:
                problems.append(f"{lang}: {n} — остались {{…}} скобки: {txt!r}")

    check_locale("en", exp_en, got_en)
    check_locale("ru", exp_ru, got_ru)

    # Симметричность локалей (кроме допустимых различий форм плюралов).
    en_strings, en_plurals = got_en
    ru_strings, ru_plurals = got_ru
    if set(en_strings) != set(ru_strings):
        only_en = sorted(set(en_strings) - set(ru_strings))
        only_ru = sorted(set(ru_strings) - set(en_strings))
        problems.append(f"строки: en-only={only_en} ru-only={only_ru}")
    if set(en_plurals) != set(ru_plurals):
        problems.append(
            f"плюралы: en-only={sorted(set(en_plurals) - set(ru_plurals))} "
            f"ru-only={sorted(set(ru_plurals) - set(en_plurals))}"
        )

    android_app_count = check_android_app_res(problems)

    total_rn = len(rn_en) + len(rn_ru)
    if problems:
        for p in problems:
            print("FAIL:", p)
        print(f"\n{i18n_check_result(False)} RN-ключей en={len(rn_en)}, ru={len(rn_ru)} — есть расхождения.")
        sys.exit(1)

    print(
        "OK: i18n паритет — RN-ключей en="
        f"{len(rn_en)}, ru={len(rn_ru)}; строк в XML en={len(en_strings)}, ru={len(ru_strings)}; "
        f"плюралов en={len(en_plurals)}, ru={len(ru_plurals)}; KMP-ключей session={len(KMP_ONLY_KEYS)}; "
        f"androidApp/res en=ru={android_app_count}"
    )


def i18n_check_result(ok):
    return "паритет подтверждён" if ok else "ПАРИТЕТ НАРУШЕН"


if __name__ == "__main__":
    main()
