# IPTV settings language verification

Captures of the same Quick Channels component using English and Spanish Activity resources in `IptvSettingsLocaleTest`. They show the final localized UI; the English capture is not a before-Spanish screenshot. Source implementation: `4641970ee0b44d444e54ad22442694c07d5f3276`.

Validation on Android 16, x86_64 emulator:

- 304 Live TV JVM tests passed (33 suites, no failures/errors/skips).
- Five instrumentation tests passed: app-locale override and English/system fallback; quantities, decimal and recording-storage formatting; English/Spanish native Dialog content and return to English; phone-page locale snapshots/HTML/JavaScript escaping/RTL metadata; four translated tabs in a 250dp pane using D-pad Right and focus return from content.
- The resource checker passed: 884 base resources, all 879 required Spanish translations, 1,025 source references, matching format/plural signatures and no duplicate keys.

The Dialog fixture changes its Activity resource configuration and recreates the Dialog composition, then restores that configuration. It verifies native-window locale behavior; it does not exercise the full language preference persistence/restart flow. Physical phone and SHIELD acceptance were not performed.

Runtime tests used isolated package `com.nuvio.livetv.localepreview`, with a build-only package override excluded from the product changes. The normal debug package ID was verified before installation; normal debug APKs were not installed over existing app data.

English resource defaults are retained. Spanish covers the new translatable entries; French covers the phone page. Other language packs use English fallback for missing entries. Raw provider names, URLs and diagnostic content are not translated.

Resource check:

```sh
python ci/checks/check_iptv_settings_localization.py
```

Captures:

- `english.png`: SHA-256 `13b77297b7f60c7ab70580647cca5ca012d5cae3677ecd0a96785a0f536b4a1f`
- `spanish.png`: SHA-256 `4bd2f4379c76e9e084080b47f5fcf2daf6716f9885b4db516dc00132e03065be`
