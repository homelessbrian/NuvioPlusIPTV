# IPTV settings language verification

The primary captures, `tv-english.png` and `tv-spanish.png`, show the actual TV app's full Settings → Live TV → Guide screen. They include the settings rail and global TV sidebar. Source implementation: `4641970ee0b44d444e54ad22442694c07d5f3276`.

This verification covers the TV repository only. [Nuvio + IPTV Mobile](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile) is a separate codebase. The TV APK's phone launcher, a narrow test pane, and the phone setup web page do not establish coverage of that mobile application.

Validation on Android 16, x86_64 emulator:

- 304 Live TV JVM tests passed (33 suites, no failures/errors/skips).
- Five instrumentation tests passed: app-locale override and English/system fallback; quantities, decimal and recording-storage formatting; English/Spanish native Dialog content and return to English; phone-page locale snapshots/HTML/JavaScript escaping/RTL metadata; four translated tabs in a 250dp pane using D-pad Right and focus return from content.
- The resource checker passed: 884 base resources, all 879 required Spanish translations, 1,025 source references, matching format/plural signatures and no duplicate keys.

The Dialog fixture changes its Activity resource configuration and recreates the Dialog composition, then restores that configuration. That instrumentation test verifies native-window locale behavior. Physical phone and SHIELD acceptance were not performed.

Additional manual TV flow used the app's normal MainActivity on the `NuvioVpnTv36` Android TV image at 1920×1080. The image reports television, leanback and leanback-only features, with UI mode `0x24` (television/night). Navigation used D-pad input over scoped ADB, without activating a desktop window.

The app was opened without an account, using Advanced mode and the Modern layout. The English screen was reached through Home → Settings → Live TV → Guide. Spanish was selected through Appearance → Font and Language → App Language → Español; after Activity recreation, the same Guide settings page was captured. A cold restart retained Spanish. System-default English was then restored through the same language picker and the isolated preview was stopped. No playlists or provider accounts were added.

Runtime tests used isolated package `com.nuvio.livetv.localepreview`, with a build-only package override excluded from the product changes. The normal debug package ID was verified before installation; normal debug APKs were not installed over existing app data.

English resource defaults are retained. Spanish covers the new translatable entries; French covers the phone page. Other language packs use English fallback for missing entries. Raw provider names, URLs and diagnostic content are not translated.

Resource check:

```sh
python ci/checks/check_iptv_settings_localization.py
```

Full TV captures:

- `tv-english.png`: actual English Guide settings screen; SHA-256 `29173ac4466f491fcbeeabf30ea7c304e43a6ddb1ff9b3f0df0d5d218c1c5753`.
- `tv-spanish.png`: same TV screen after selecting Spanish in the app; SHA-256 `16ea09091e3a8d20435a90d02fedd7e86cb61706d4e349492b588792b1d887bd`.

Supplementary component-test captures, retained as test diagnostics rather than full-TV-flow evidence:

- `english.png`: SHA-256 `13b77297b7f60c7ab70580647cca5ca012d5cae3677ecd0a96785a0f536b4a1f`
- `spanish.png`: SHA-256 `4bd2f4379c76e9e084080b47f5fcf2daf6716f9885b4db516dc00132e03065be`
