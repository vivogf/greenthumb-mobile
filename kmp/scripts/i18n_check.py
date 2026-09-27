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
  - экранирование как в Android: \\' \\" \\\\ \\n \\t (& → &amp; обязателен XML-правилами).

Разрешённые надмножества RN (ключи, которых в RN нет):
  - session.* — экран «ключ не найден» (SessionState.KeyNotFound, Stage 3 п.6;
    в RN-версии экрана нет). Требуются в ОБОИХ локалях.

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
    "session.keyNotFoundTitle",
    "session.keyNotFoundBody",
    "session.keyNotFoundEnterKey",
    "session.keyNotFoundCreateAccount",
    # profile.regenerateWarning — RN profile.tsx:192-193 держит текст диалога
    # регенерации захардкоженным по-английски (пробел i18n RN); KMP заводит
    # ключ в обеих локалях (ru-текст собственный).
    "profile.regenerateWarning",
}

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
        extra_p = sorted(set(got_plurals) - set(exp_plurals))
        for n in missing_p:
            problems.append(f"{lang}: нет <plurals name=\"{n}\">")
        for n in extra_p:
            problems.append(f"{lang}: лишний <plurals name=\"{n}\">")
        for n, txt in exp_strings.items():
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
            for q in sorted(set(got_q) - set(quantities)):
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

    total_rn = len(rn_en) + len(rn_ru)
    if problems:
        for p in problems:
            print("FAIL:", p)
        print(f"\n{i18n_check_result(False)} RN-ключей en={len(rn_en)}, ru={len(rn_ru)} — есть расхождения.")
        sys.exit(1)

    print(
        "OK: i18n паритет — RN-ключей en="
        f"{len(rn_en)}, ru={len(rn_ru)}; строк в XML en={len(en_strings)}, ru={len(ru_strings)}; "
        f"плюралов en={len(en_plurals)}, ru={len(ru_plurals)}; KMP-ключей session={len(KMP_ONLY_KEYS)}"
    )


def i18n_check_result(ok):
    return "паритет подтверждён" if ok else "ПАРИТЕТ НАРУШЕН"


if __name__ == "__main__":
    main()
