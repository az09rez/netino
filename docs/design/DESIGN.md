# Netino UI design

Source of truth for the Netino redesign (Android + Windows). The live, interactive version is
the Design canvas at https://claude.ai/artifact/996CgfwSEVwY8FMVcTbJr6 (two pages: فارسی — RTL
and English — LTR). `canvas/` holds a copy of every artboard so the design lives next to the code.

The `.dc.html` files are design sources, not app code: read them for exact layout, sizes, colors
and copy. They only render inside the canvas (they load the canvas runtime `support.js`). The
image `/_blob/5644abea1ce12769d11482a20bb3cea4` used in them is
`app/src/main/res/drawable-nodpi/logo.png`.

## Where things are

| Design artboard (`canvas/`) | Code |
|---|---|
| `Tokens.dc.html` | `app/.../ui/Theme.kt`, `desktop/.../ui/Theme.kt` |
| `Components.dc.html` | `app/.../ui/Components.kt` (Android), `desktop/.../ui/*` |
| `Motion.dc.html` | a shared `LocalMotion` in both Theme files |
| `A-Home.dc.html` (+ `A-Home-*` state boards) | `app/.../ui/HomeScreen.kt` |
| `A-Large.dc.html`, `A-Large-200.dc.html` | `HomeScreen.kt` at font scale 1.3 / 2.0 |
| `A-Onboarding.dc.html` | `IntroScreen.kt` |
| `A-ServerSheet.dc.html`, `A-Servers.dc.html`, `A-Add.dc.html` | `ServersScreen.kt`, `AddServer.kt` |
| `A-Usage.dc.html` | `StatsScreen.kt` |
| `A-Settings.dc.html`, `A-Settings-Anti.dc.html` | `SettingsScreen.kt`, `ToolsUi.kt` |
| `A-National.dc.html` | `NationalUi.kt` |
| `A-Widgets.dc.html` | Glance widgets + ongoing notification |
| `W-Home.dc.html`, `W-Narrow.dc.html` | `desktop/.../ui/HomeScreen.kt` |
| `W-Servers.dc.html` | `desktop/.../ui/ServersScreen.kt`, `ServerPicker.kt` |
| `W-Tools.dc.html` | `desktop/.../ui/ToolsScreen.kt` |
| `W-Settings.dc.html` | `desktop/.../ui/SettingsScreen.kt`, `SettingsCards.kt` |
| `W-Tray.dc.html` | desktop tray menu + Windows notifications |
| `EN-*` | the same screens in English (LTR) |

`app/...` = `app/src/main/java/com/netino/vpn`, `desktop/...` = `desktop/src/main/kotlin/com/netino/desktop`.

## Tokens

### Color (Material 3 `ColorScheme`)

| Role | Light | Dark |
|---|---|---|
| primary | `#1A73D9` | `#6AB0FF` |
| onPrimary | `#FFFFFF` | `#00315F` |
| primaryContainer | `#D6E7FF` | `#0D3F78` |
| onPrimaryContainer | `#0A3A73` | `#D6E7FF` |
| secondary (brand green, text-safe) | `#3B7A0C` | `#9BE05A` |
| secondaryContainer | `#DDF3C8` | `#22420A` |
| background / surface | `#F6F8FC` | `#0A0F1C` |
| surfaceContainerLowest (cards) | `#FFFFFF` | `#0F1626` |
| surfaceContainer | `#EFF3F9` | `#131B2D` |
| surfaceContainerHigh | `#E6ECF5` | `#1A2438` |
| onSurface | `#0F172A` | `#E5EAF3` |
| onSurfaceVariant | `#4B5567` | `#9AA6BA` |
| outlineVariant | `#DCE3EE` | `#253047` |
| outline (input borders) | `#C3CDDC` | `#3A4660` |

Changes from the current `Theme.kt`: `secondary` light goes from `#4E9A12` to `#3B7A0C` (the old
value fails AA for text); `onPrimaryContainer` light becomes `#0A3A73`.

Status colors — use the *fill* for shapes, the *text* value for words on light surfaces:

| Status | Fill | Text (light) | Dark | Container light / dark |
|---|---|---|---|---|
| Good / connected | `#22A447` | `#1B7F38` | `#4ADE80` | `#E3F5E8` / `#123A22` |
| Warning / connecting | `#F59E0B` | `#9A5B00` | `#FBBF24` | `#FEF3DC` / `#3A2A06` |
| Error / disconnected | `#E5484D` | `#C2272D` | `#FF7A7E` | `#FDE4E5` / `#3D1416` |

Latency pill: green ≤ 600 ms, orange ≤ 1500 ms, red above or failed, grey when untested.
Brand gradient `#1E88E5 → #42A5F5 → #7CC531` at 135°, only on the connected button and logo tile.

Protocol avatars (light bg / fg): vless `#D6E7FF/#0A3A73`, vmess `#CCF0EE/#0B5550`,
trojan `#EBDDFF/#4A2A8A`, ss `#FDE2EE/#8A1F4C`, hysteria2 `#FFE0CC/#8A3B00`,
WireGuard/WARP `#DDF3C8/#2C5A08`. Dark variants are in `A-Servers.dc.html`.

### Type — Vazirmatn only

| Style | Size / weight | Use |
|---|---|---|
| displaySmall | 34sp Bold | connection timer |
| headlineSmall | 24sp SemiBold | screen title |
| titleLarge | 22sp Bold | status ("متصل و امن") |
| titleMedium | 18sp SemiBold | card and section titles |
| bodyLarge | 17sp Regular | main text |
| bodyMedium | 15sp Regular | row supporting text |
| labelLarge | 15sp Medium | buttons |
| labelMedium | 13sp SemiBold | pills, small labels |

Numbers that change live use `fontFeatureSettings = "tnum"`. Persian UI shows Persian digits
(`NumberFormat` with `Locale("fa")`).

### Shape, spacing, elevation

- Corners: small 12, medium 18, large 24, extra-large 32 (dp). Pills and switches are full-round.
- 4dp grid. Screen margin 16dp (phone), 24dp (Windows). Card gap 12dp. Touch targets ≥ 48dp.
- No heavy shadows: separate surfaces with `surfaceContainer` tones.

### Icons

Material Symbols Outlined. Main-section icons (bottom bar, rail, settings categories, section
headers) are **28dp**; others 24dp, inline 18–20dp. Directional icons (chevrons, back) must be
`AutoMirrored` so they flip in LTR.

## Components (Material 3 mapping)

| Component | Implementation |
|---|---|
| Connect button | Custom. `Box` with halo drawn on `Canvas` (228dp), `Surface(CircleShape, onClick)` 184dp (140–156dp on small screens / large text), icon `power_settings_new` 56–60dp. States: off = `surfaceContainerHigh` fill; connecting/searching = warning container + 3dp warning ring; connected = brand gradient; error = error container + 3dp error ring + `error` icon. While searching, a 4-segment progress ring (`drawArc`, 6dp) sits on the halo. `semantics { role = Button; stateDescription = … }` |
| Current-server card | `Card(onClick)` on `surfaceContainer`, 24dp corners, min height 72dp; protocol avatar 44dp (14dp corners); latency pill; mirrored chevron. Opens the server bottom sheet. |
| Server row | `ListItem`: avatar, name + "protocol · address", latency pill, selected check, `IconButton(more_vert)` + `DropdownMenu`. Swipe-to-dismiss (`SwipeToDismissBox`): toward the reading end deletes (red `#B3261E` background), the other way adds to group. Selected row = solid `primaryContainer` tint. |
| Latency pill | `Surface(shape = CircleShape)` 28dp high, `labelMedium`, colors from the status table. While testing: shimmer placeholder, 64dp wide. |
| Progress card | `Card` + row of 4 (Android) / 5 (Windows) `LinearProgressIndicator`s, 6dp; "Step x of y · Testing servers a/b" + `TextButton("Cancel")`. |
| Banner | Custom `Surface(18dp)` in a status container + icon + `FilledTonalButton`. National internet (warning), update (primary), kill switch active (error). |
| Chips | `FilterChip` for scope selection, `InputChip` for direct-route apps. |
| Actions | WARP = `Button` 56dp full width; Fastest = `FilledTonalButton` + scope dropdown. In the error state they swap (Fastest becomes the filled one). Emergency = underlined `TextButton`. |
| Stat tile | `Surface(surfaceContainer, 18dp)`; `labelMedium` + `titleLarge`. The connected stats row is tappable and expands the 60-second chart (`animateContentSize`). Speed-test card is collapsed by default. |
| Last successful connection | Outlined card with `history` icon + tonal "Reconnect". Only when disconnected. |
| Snackbar | M3 `Snackbar` with "Undo" after delete / connection-type change. |
| Dialogs | `AlertDialog` (icon, title, text, confirm/dismiss). Emergency dialog shows numbered steps with state. |
| Bottom bar (Android) | 4 tabs: Home `shield`, Servers `dns`, Usage `data_usage`, Settings `settings`. Icons 28dp, indicator 72×36, labels 14sp (capped at 130% font scale). M3 `NavigationBarItem` has a fixed 64×32 indicator, so build a custom item. |
| Navigation rail (Windows) | `NavigationRail` 88dp (72dp narrow, labels hidden), logo header, items 64dp, indicator 64×36, icons 28dp. Home, Servers, Stats `bar_chart`, Tools `build`, Settings, Log `receipt_long`. |
| Connection type (Windows home + settings) | `SingleChoiceSegmentedButtonRow`: System proxy / Full tunnel (with `shield_person`). Choosing full tunnel without admin opens "Full tunnel needs admin access" → "Restart as admin". |
| Starred servers | Star `IconToggleButton` per row in the server sheet; starred rows sort first. |

## Motion

See `canvas/Motion.dc.html` for live samples. Easing: standard `cubic-bezier(0.2,0,0,1)`,
emphasized decelerate `(0.05,0.7,0.1,1)` for entering, emphasized accelerate `(0.3,0,0.8,0.15)`
for leaving.

| Where | Animation | Duration |
|---|---|---|
| Connect button state | color/ring cross-fade, icon 0.92→1 | 250ms |
| Button press | scale 0.96 + haptic | 100ms |
| Connecting | orange halo grows and fades (loop) | 1600ms |
| Connected | one green wave, then calm halo (loop) | 600ms, then 2400ms |
| Error | horizontal shake ±6dp ×3 + error haptic | 300ms |
| Timer / speed numbers | vertical slide per digit (`AnimatedContent`) | 150ms |
| Search progress | ring/bar fill | 300ms per update |
| Banners | `expandVertically + fadeIn` / reverse | 250 / 200ms |
| Bottom sheets | `ModalBottomSheet` default | 300ms |
| Collapsible sections | `animateContentSize` + chevron 180° | 250ms |
| Latency pill result | color change + small pop | 200ms |
| Test all | shimmer placeholders, in reading direction (loop) | 1200ms |
| Tabs | cross-fade, indicator grows from center | 200ms |
| Sub-screen navigation | horizontal slide in reading direction | 300ms |
| Onboarding dots | active dot 10→28dp | 300ms |
| Switch / dialogs / segmented button | M3 defaults | 150 / 200 / 200ms |

Battery saver (`PowerManager.isPowerSaveMode`), `ANIMATOR_DURATION_SCALE == 0`, or Windows
"Animation effects" off: looping animations stop and state changes become instant. Haptics stay.

## Large text

`canvas/A-Large.dc.html` (130%, 150%, 200% via its `scale` tweak). Rules:

1. All text in `sp`; rely on Android 14 nonlinear scaling (big text grows less than small).
2. Reflow, never clip: rows use wrapping (`FlowRow`), buttons have min height 56dp and no fixed height.
3. At ≥150% the connect button shrinks to 124dp so content stays visible.
4. Bottom-bar labels and icons are capped at 130% (`fontScale.coerceAtMost(1.3f)` locally).
5. Screens scroll.

## RTL / LTR and copy

Persian (RTL) is the default; every layout must also work in English (LTR) — compare the
`fa` and `EN-` boards. Key strings (fa / en):

| Use | فارسی | English |
|---|---|---|
| Connected | متصل و امن | Connected |
| Connecting | در حال اتصال… | Connecting… |
| Disconnected | متصل نیست | Not connected |
| Error | اتصال برقرار نشد | Couldn't connect |
| Connect button | برای اتصال لمس کنید | Tap to connect |
| Fastest | اتصال به سریع‌ترین | Connect to fastest |
| WARP | اتصال فوق‌سریع | Super-fast connect |
| Emergency | هیچ‌کدام وصل نمی‌شود؟ همهٔ راه‌ها را امتحان کن | Nothing connects? Try every way |
| National internet | اتصال در حالت اینترنت ملی | Connect in national internet mode |
| Free list | کانفیگ‌های رایگان نتینو | Netino free configs |
| Kill switch | قطع امن (Kill Switch) | Kill switch |

All other copy is in the artboards.

## Suggested implementation order

1. Theme: update both `Theme.kt` files to the tokens above (colors, type scale, shapes, `LocalMotion`).
2. Shared components: connect button, server card/row, latency pill, banners, progress card.
3. Android home (every state), then servers + sheet + add, usage, settings, onboarding, national.
4. Windows home (with connection-type switch), servers, tools, settings, tray.
5. Widgets and the ongoing notification.
6. Check each screen in light/dark, fa/en, small screen (360×640dp) and 200% font scale.
