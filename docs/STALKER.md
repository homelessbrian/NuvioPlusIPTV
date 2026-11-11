# Stalker / Ministra sources

Add a **Stalker / Ministra** source in Live TV settings using the portal URL and the MAC address registered with your provider. Enter optional login and device identity fields only when your provider supplies them. The source uses the existing channel, guide and On Demand screens.

Supported paths are live channels, native guide data, provider-advertised catch-up, movies and series. Both native series and VOD-based series catalogues are supported. Guide imports stream programme rows into the existing guide pipeline and retain the configured coverage. Historical guide requests follow the channels being browsed rather than querying every channel at startup.

Source settings participate in the existing backup and restore flow. Portal session tokens and resolved temporary playback links stay in memory. Playback and recording resolve the saved provider reference again when opening or retrying a stream. Archive programme IDs are bound to their issuing portal identity so an edited source or a guide match from another portal cannot reuse them.

## Initial compatibility boundaries

- Android playback uses the internal ExoPlayer. MPV, native external players, external handoff and sharing portal stream links in watch parties are unavailable for these sources because those paths cannot enforce the same credential and routing policy.
- Supported VPN scopes are disabled protection, Full app, or HTTP + HTTPS. HTTP-only protection is rejected for Stalker sessions because portal and media requests can otherwise use different network routes. Settings are never changed automatically.
- Portal credentials apply only to the authenticated origin, including its scheme and port. They are removed on CDN redirects and on other-origin manifests, segments and keys. Secure requests cannot downgrade to plain HTTP.
- Automatic subtitle file hashing, native format preflight and automatic subtitle-sync media downloads are bypassed when they cannot use the scoped transport. Out-of-band Matroska chapter lookups are also unavailable. Embedded subtitles, normal subtitle lookup and manual subtitle timing remain available.
- Media must resolve to HTTP or HTTPS. Provider commands are opaque protocol data and are never executed as desktop commands.
- The parallel HTTP downloader on TV and the on-disk VOD cache in both apps are bypassed for Stalker playback. Their existing transports and cache metadata cannot safely carry temporary portal credentials. Ordinary sources keep their existing settings and behavior.
- Extensionless links use a protected format probe with a combined 1.2-second budget and at most 8 KiB of response data. Known media extensions need no probe. Providers with single-use links or unusual HEAD handling still need real-provider validation.
- Catch-up requires provider support and a real provider programme ID. A generated guide display ID never enables catch-up.
- An ambiguous multi-file movie returns an explicit error. The first file is not silently selected because it may be only one part of the movie.
- A provider-specific watchdog heartbeat is not implemented in this version. Portals that require one need further compatibility work.

Validation uses synthetic portals, generated large guides and local HTTP fixtures. It does not establish compatibility with a particular provider, physical-device playback performance, or VPN connectivity on a real network.
