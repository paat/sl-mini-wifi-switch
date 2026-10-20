# Estonian translation (#33)

The Estonian UI uses short sentences and informal singular instructions: **Vajuta**,
**Ühenda**, **Vali**. Status messages describe what the app is doing; buttons that
acknowledge a completed physical step use **Olen vajutanud**, **Olen oodanud** or **Kaabel on
ühendatud**. No device operations or English wording changed.

The default language is English. Estonian follows the phone language and is also
available in Android 13+ per-app language settings. The APK includes only English
and Estonian locale resources. Older Android versions follow the phone language;
there is no additional in-app language menu.

There are 197 base strings: 187 Estonian translations and 10 fixed strings marked
non-translatable in the base resources. These fixed strings are the app name,
STARLINK, Mini, Wi-Fi, SSID, 5 GHz, WPA2, WPA3, WPA2 / WPA3 and WAN / Internet.
The latter preserves the physical port markings. The localized frequency **2,4 GHz**
uses an Estonian decimal comma. Format arguments retain their indices and types.

The base resources declare `tools:locale="en"` so lint knows the fallback language.
English strings come from the default `values/strings.xml`; there is no duplicate
`values-en` resource or resource-deduplication override. `localeFilters` retains
English and Estonian, and `locales_config.xml` lists both for per-app selection.

## Glossary

| English | Estonian | Note |
|---|---|---|
| Ready to set up | Seadistamiseks valmis | Same phrase in Home and reset instructions. |
| Set up | Seadista | The one-time setup after a reset. |
| Already set up | Juba seadistatud | Distinct from readiness to begin setup. |
| Settings | Seaded | Ordinary settings; “seadistus” describes the overall setup. |
| Reset | Lähtesta / lähtestamine | Physical reset, distinct from restart. |
| Restart | Taaskäivitu / taaskäivitumine | Does not imply removing saved settings. |
| Wi-Fi off | Wi-Fi väljas | State label; action is “Lülita Wi-Fi välja”. |
| Wi-Fi on with password | Wi-Fi sees, parooliga | Owner wording, 2026-10-20. The confirmation still says “sisse”. |
| Wi-Fi name | Wi-Fi nimi | Avoids SSID in the main interface. |
| Password | Parool | Consistent in form, result, instructions and store text. |
| Show / hide password | Näita parooli / Peida parool | Explicit actions, no ambiguous toggle label. |
| Use the same Wi-Fi as last time | Kasuta sama Wi-Fi-d mis eelmine kord | Owner wording, 2026-10-20. Reuses the saved name and password. |
| Connect | Ühenda | Physical connection or the illustrated Android action. |
| Join STARLINK | Liitu STARLINKiga | Keeps the network name and adds the normal Estonian case ending. |
| Join on this phone | Liitu selles telefonis | The action happens on this phone; it does not join the phone. |
| Cancel | Tühista | Cancels the current choice. |
| Not available | Pole saadaval | Distinct from unknown. |
| I have pressed / waited | Olen vajutanud / Olen oodanud | Explicit acknowledgement of a completed physical step. |
| Use Wi-Fi / cable instead | Kasuta hoopis Wi-Fi võrku / Kasuta hoopis kaablit | Preserves the alternative connection choice. |
| Check again | Kontrolli uuesti | Read-only action. |
| Cable port | Kaablipesa | A visible socket, not the technical term “port”. |
| Internet port | Internetipesa | Guidance retains the physical WAN / Internet markings. |
| Ethernet cable / adapter | Võrgukaabel / võrguadapter | Everyday wording; USB-C remains explicit. |
| Router | Ruuter | Physical device and technical Details screen. |
| Dish | Antenn | Describes the Mini without implying a round dish. |
| Details | Üksikasjad | Technical information stays on this screen. |
| Reset help | Lähtestamise juhis | One consistent name for the illustrated instructions. |
| Keep the Mini powered on | Ära lülita Mini välja | Works for mains or portable power. |
| QR code | QR-kood | QR remains unchanged. |
| Quick Settings tile | Kiirseadete nupp | Store description; no developer terminology. |

Starlink, STARLINK, Mini, Wi-Fi and QR retain their spelling. Estonian grammatical
endings are used where needed (Starlinki, Starlinkiga, STARLINKiga, Miniga).
Branding remains **Mini Wi-Fi Switch** in both the UI and store title.

## Wording for owner review

- **Lähtesta** is concise and distinguishes reset from **taaskäivita**. It may be
  unfamiliar to first-time users. The guide explains both the physical action and
  the loss of the saved Wi-Fi name/password. Alternative: **Taasta tehaseseaded**,
  which is longer.
- **Võrguadapter**, **kaablipesa** and **internetipesa** avoid Ethernet/port jargon.
  Confirm these match how the intended field users describe their equipment.
- **Inglise tähed** follows the final English input rules. It means letters without
  Estonian diacritics for passwords; names can contain those letters but may need
  to be shorter. The underlying validation is unchanged.
- **Ühenda**, **Salvesta** and **unusta võrk** describe Android actions. Exact system
  labels can differ by Android version, manufacturer and system language, especially
  if only this app is switched to Estonian.
- Technical Details uses **Autentimine**, **Raadio** and **5 GHz ülemine sagedusala**.
  These are deliberately kept out of the main instructions.

The supplied reviewer corrections were applied in fix cycle 1, including
phone-local joining, explicit completed-step labels, cable direction and consistent
wording. The owner decided on 2026-10-20: the secure choice is **Wi-Fi sees, parooliga**
with **Sinu enda Wi-Fi nimi ja parool.**, and the saved-profile button is
**Kasuta sama Wi-Fi-d mis eelmine kord**. The owner
wording questions above remain available for review.

## Screenshot coverage

The shared ScreenshotHost renders light-theme Estonian resources using Robolectric
qualifier `et-w411dp-h891dp-xhdpi`. Each of these has normal and 200% text captures
under [screenshots/et/](screenshots/et/):

- `ready_light*.png`: ready to set up, both choices and Details.
- `already_set_up_light*.png`: configured Home, Wi-Fi status card, hidden-password
  explanation and reset action.
- `wifi_off_light*.png`: Wi-Fi off Home and reset action.
- `not_found_light*.png`: connection guidance, cable, Android dialog and actions.
- `reset_guide_light*.png`: reset warning, press, light, waiting and Back action.
- `wifi_password_light*.png`: input rules, saved profile, password entry, show/hide
  and review actions; visible-password shots use fictional credentials.
- `wifi_on_confirm_light*.png`: consequences, restart, Android dialog and actions.

Numbered `_step_N` images show checkpoint scroll positions (reset ends at Back); `_visible` shows the
password after the explicit show action. Long pages intentionally scroll. The
tests also check text layout overflow and minimum 48 dp click targets, and verify
that opening or cancelling confirmation does not invoke its confirm callback.

## Verification

Fix cycle 1, run on 2026-10-19 after rebasing onto `ff3de6d`, with `ANDROID_HOME=/home/dev/android-sdk`:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
/home/dev/android-sdk/build-tools/37.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
```

- Build: successful in 30 seconds (60 tasks; 13 executed, 47 up-to-date).
- Protocol: **52 tests** in 5 suites, no failures, errors or skips.
- App: **458 tests** in 30 suites, including 14 Estonian screenshot/layout cases; no failures,
  errors or skips.
- Roborazzi: **335 unchanged comparisons**: 279 existing English and 56 Estonian.
  Zero added, changed or recorded images during verification. English baseline
  files have no diff.
- Lint: **0 errors, 21 warnings**. The Android 13-only `localeConfig` warning is
  expected with minSdk 29; the other warnings concern existing code/resources.
- MissingTranslation: **0 issues**; all **187/187** translatable base strings have
  Estonian entries, including the new `reset_checking` and updated `reset_wait`.
- APK badging: `locales: '--_--' 'et'`. English uses the default resources
  (`--_--`); `localeFilters` and `locales_config.xml` still specify `en` and `et`.
- Resource audit: 187/187 translations, format arguments match, all 197 rebased
  English values unchanged. Store short description: 79/80 characters; changelogs
  1 and 2: 455 and 398 characters.
- Targeted recording: **6 tests**, **28 captures**, **17 changed Estonian PNGs**.
  Only `notFound*`, `reset*` and `confirm*` were selected. The reset fixture now
  checks Back because #32 removed Check again. No English baselines changed.
- Visual inspection: all **6 changed 200% PNGs** and the normal-size cable, reset
  wait and confirmation checkpoints were re-inspected. Corrected text fits;
  long pages scroll. Text-layout overflow and minimum 48 dp touch-target checks
  passed. The shortened Ready choice explanations remain unchanged.

No real Mini was contacted. Product Kotlin, protocol, CI, signing and versions
were not changed. Automated tests do not replace human language or hardware checks.

A human may additionally check Android 13+ Settings → Apps → Mini Wi-Fi Switch →
Language: select Estonian, return to the app, then select English. On older Android,
change the phone language. This does not require a Mini or a setup write.
