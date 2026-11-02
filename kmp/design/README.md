# Design previews

## Dark theme palettes — `dark-theme-palettes.html`

A color-only comparison of four dark application-theme palettes on the current app design.
It is a **preview only**. It changes no app theme, no settings and no Android code. It is not a
Google Play card or store asset, and it calls no API. Choosing a palette and moving it into the
app is a separate later decision of the owner.

### Open it

Open the file directly in any modern browser, for example:

```bash
open kmp/design/dark-theme-palettes.html   # macOS, from the repository root
```

The file is self-contained. CSS, JavaScript, icons and illustrations are inline, so it needs no
network, server, package install or account.

### What it shows

- **Вид: Сравнение всех четырёх** shows the four palettes side by side (2×2 or one column on a
  narrow window). **Осмотреть** on a variant, or a palette button, opens **Осмотр одной палитры**:
  one phone plus a table of every color role with its value.
- **Экран: Главная / Карточка растения** switches the screen for all variants at once.
- Both screens use one shared template and the same synthetic data. Fonts, sizes, spacing, radii,
  icons and pictures are identical; only CSS color variables change. Nothing is animated.
- The screens follow the current KMP design, including the M12a details: the grid day count above
  the plant name, and the separate «О растении» and «Запись в журнал» cards.
- Plant pictures are synthetic SVG drawings made for this file, not photos or app screenshots.

### The four palettes

| Label | Meaning | Source |
|---|---|---|
| Прежняя | Warm charcoal colors the app used before the «Лес» theme. Colors only; the layout is the current one. | `GreenThumbColors.kt` / `GreenThumbExtendedColors.kt` at `dd84fff36a3b78debefe128414699264b78e71eb` (parent of the «Лес» commit `6047ecf`) |
| Текущий Лес | Dark «Лес», the colors in the code now. | Same files at `2a8da8fb0d4d092b3f5d58034c39d37c552f0319` |
| Графит | New proposal: near-neutral charcoal; green only on actions and selection. | New values |
| Серо-шалфейная | New proposal: low-saturation green-grey surfaces with muted sage accents. | New values |

The seven reference roles of the two real palettes are exact copies of the source:

| Role | Прежняя | Текущий Лес |
|---|---|---|
| background | `#15130E` | `#0E1610` |
| surface | `#1C1A14` | `#152018` |
| surfaceVariant | `#2A2822` | `#1C2A1F` |
| text | `#F3F2EF` | `#EEF4EC` |
| muted | `#A89F92` | `#A8B9A6` |
| primary | `#4A9A5A` | `#277A40` |
| error | `#A33535` | `#EE8E72` |

The preview also uses page, border, onPrimary, onError, accent, accentText, chipSelected and amber.
For «Прежняя» these come from the same old files (secondary and tertiary were equal to primary,
so accent and link text are `#4A9A5A`; amber was `#D97706`; page `#0A100B`). The old theme had no
selected-chip fill, so the preview uses primary at 20 % and marks that role «роль макета».
The historical colors are kept as they were, even where the contrast is low (for example the
old error red on a dark card). The two new palettes keep main text at 7:1 or more and muted text,
links, overdue text and button labels at 4.5:1 or more; this is a basic check, not an
accessibility certification.

### Test

```bash
node --test kmp/design/dark-theme-palettes.test.mjs
```

The test uses only built-in Node modules. It checks the four labelled palettes, the fourteen
exact reference values, the contrast and saturation of the new palettes, the shared color-only
templates, no animation, no remote loads, and that the inline JavaScript parses.
