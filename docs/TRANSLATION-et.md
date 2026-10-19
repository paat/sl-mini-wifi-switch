# Estonian translation (#33)

The Estonian UI uses short sentences and informal singular instructions: **Vajuta**,
**Ühenda**, **Vali**. Status messages describe what the app is doing; buttons that
acknowledge a completed physical step use **Vajutatud**, **Oodatud** or **Kaabel on
ühendatud**. No device operations or English wording changed.

The default language is English. Estonian follows the phone language and is also
available in Android 13+ per-app language settings. The APK includes only English
and Estonian locale resources. Older Android versions follow the phone language;
there is no additional in-app language menu.

There are 196 base strings: 186 Estonian translations and 10 fixed strings marked
non-translatable in the base resources. These fixed strings are the app name,
STARLINK, Mini, Wi-Fi, SSID, 5 GHz, WPA2, WPA3, WPA2 / WPA3 and WAN / Internet.
The latter preserves the physical port markings. The localized frequency **2,4 GHz**
uses an Estonian decimal comma. Format arguments retain their indices and types.

The base resources declare `tools:locale="en"` so lint knows the fallback language.
One identical English `ready_title` entry in `values-en/strings.xml` makes the
English locale explicit in APK badging. Keep it synchronized with the base title.
AAPT resource deduplication is disabled so it does not discard that identical
entry; no English text changes. Other English strings still come from the base.

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
| Wi-Fi on with password | Parooliga Wi-Fi | Short choice label; confirmation explicitly says “sisse”. |
| Wi-Fi name | Wi-Fi nimi | Avoids SSID in the main interface. |
| Password | Parool | Consistent in form, result, instructions and store text. |
| Show / hide password | Näita parooli / Peida parool | Explicit actions, no ambiguous toggle label. |
| Use the same Wi-Fi as last time | Kasuta eelmist Wi-Fi võrku | Short label for reusing the saved name and password. |
| Connect | Ühenda | Physical connection or the illustrated Android action. |
| Join STARLINK | Liitu STARLINKiga | Keeps the network name and adds the normal Estonian case ending. |
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
- **Parooliga Wi-Fi** keeps the main choice compact. It drops the literal “on”
  there; the confirmation and action use **Lülita Wi-Fi sisse** explicitly.
- **Võrguadapter**, **kaablipesa** and **internetipesa** avoid Ethernet/port jargon.
  Confirm these match how the intended field users describe their equipment.
- **Inglise tähed** follows the final English input rules. It means letters without
  Estonian diacritics for passwords; names can contain those letters but may need
  to be shorter. The underlying validation is unchanged.
- **Vajutatud** and **Oodatud** acknowledge a completed step without a long first-person
  sentence. Review whether the audience finds these sufficiently explicit.
- **Ühenda**, **Salvesta** and **unusta võrk** describe Android actions. Exact system
  labels can differ by Android version, manufacturer and system language, especially
  if only this app is switched to Estonian.
- Technical Details uses **Autentimine**, **Raadio** and **5 GHz ülemine sagedusala**.
  These are deliberately kept out of the main instructions.

Issue #33 requires review by a second, independent model **before merge**. That
language review is still pending; the implementation and layout checks here do not
claim to replace it.

## Screenshot coverage

The shared ScreenshotHost renders light-theme Estonian resources using Robolectric
qualifier `et-w411dp-h891dp-xhdpi`. Each of these has normal and 200% text captures
under [screenshots/et/](screenshots/et/):

- `ready_light*.png`: ready to set up, both choices and Details.
- `already_set_up_light*.png`: configured Home, Wi-Fi status card, hidden-password
  explanation and reset action.
- `wifi_off_light*.png`: Wi-Fi off Home and reset action.
- `not_found_light*.png`: connection guidance, cable, Android dialog and actions.
- `reset_guide_light*.png`: reset warning, press, light, waiting and check action.
- `wifi_password_light*.png`: input rules, saved profile, password entry, show/hide
  and review actions; visible-password shots use fictional credentials.
- `wifi_on_confirm_light*.png`: consequences, restart, Android dialog and actions.

Numbered `_step_N` images show successive scroll positions; `_visible` shows the
password after the explicit show action. Long pages intentionally scroll. The
tests also check text layout overflow and minimum 48 dp click targets, and verify
that opening or cancelling confirmation does not invoke its confirm callback.

## Verification

Run on 2026-10-19 with `ANDROID_HOME=/home/dev/android-sdk`:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
/home/dev/android-sdk/build-tools/37.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
```

- Build: successful (60 tasks; 15 executed, 45 up-to-date on the final run).
- Protocol: **52 tests**, no failures, errors or skips.
- App: **453 tests**, including 14 new Estonian screenshot/layout cases; no failures,
  errors or skips.
- Roborazzi: **335 unchanged comparisons**: 279 existing English and 56 Estonian.
  Zero added, changed or recorded images during verification. English baseline
  files have no diff.
- Lint: **0 errors, 21 warnings**. The Android 13-only `localeConfig` warning is
  expected with minSdk 29; the other warnings concern existing code/resources.
- Missing-translation negative check: temporarily removing Estonian `ready_title`
  made `:app:lintDebug` exit 1 with exactly one `MissingTranslation` error for
  `et`. The resource was restored byte for byte before the successful final suite.
- APK badging: `locales: '--_--' 'en' 'et'`.
- Resource audit: 186/186 translations, format arguments match, all 196 original
  English values unchanged. Store short description: 79/80 characters; changelogs
  1 and 2: 455 and 398 characters.
- Visual inspection: all **56 PNGs** reviewed, including scrolled sections and
  200% text. No clipped labels or overflowing text; long pages scroll. The two
  Ready choice explanations were shortened to keep their text inside the rounded
  buttons. All tested click targets are at least 48 dp in both dimensions.

No real Mini was contacted. Product Kotlin, protocol, CI, signing and versions
were not changed. The independent language review above remains a before-merge
follow-up, not a claim made by the test suite.

A human may additionally check Android 13+ Settings → Apps → Mini Wi-Fi Switch →
Language: select Estonian, return to the app, then select English. On older Android,
change the phone language. This does not require a Mini or a setup write.
