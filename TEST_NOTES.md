# Test build notes

<!--
What's changed on dev since the last test build. When Build Test App's "What changed" box is left
empty, the test build uses this list (headings with nothing under them are left out), and then
removes the lines it used, so the next test build only shows what's new since. Add lines under the
headings as changes land on dev. Typing in the box uses the box instead and leaves this file alone.
-->

## Live TV
- Guide display menu (the guide's gear): Up, Down, Left and Right move the highlight again, and Left at the edge still doesn't open Nuvio's menu
- DNS setting (Cloudflare/Google) now also applies when playing channels, Multiview, catch-up, rewind and recordings, not only to loading playlists and guides
- "Keep Lists Open When Changing Channel" now also keeps the Backups lists and the Multiview channel picker open while you switch, and the switch shows even when overlay mode is off
- Guide follows AMOLED Mode / Pure Black Surfaces (black guide, tiles and info panel)
- Guide day labels (Today, Tomorrow, weekdays) follow the app language
- Adaptive Live TV Buffer slows down more gently or more strongly depending on how little video is left
- Large Stalker portals that were too big to load in one go now load page by page
- Settings → Playback → Your remote: picture of the remote, Find a button, and optional button changes for Live TV, the guide, Sports, Multiview and the movie player (each change is reviewed and saved by hand)
- On Demand: optional switch to clean movie and series group names with the same cleanup words as channels
- Setup Sync: a new TV now gets seek preview choices (on/off, when, storage limit) and more player and app choices; Include 4K and anything tied to the TV's screen, sound or memory stays on each TV

## Rewind Anytime
- Test builds only: with Rewind Anytime on, channels (plain MPEG-TS links) should show a picture right away instead of waiting about 8 seconds while rewind history saves; check channel start speed, pausing and rewinding right after starting, and changing channels quickly

## Recordings
- Rename a finished recording (hold OK → Rename)
- Recordings keep the episode's guide description even after the guide moves on

## Sports
- Low-memory TVs: Sports does less in the background while a channel plays, so it no longer runs the app out of memory
- Followed leagues with no game on, and every league's week ahead, now come from the smaller ready-made score files
- Finding channels for games is much faster on big playlists: the networks step reads only what's on each channel at the time, and later rounds only read the guide around new or changed games
- A game's page reads only what's on now and next for its channels, not a week of listings every minute
- Sports no longer redoes its pages and channel search every minute when nothing changed (team colours on season games were being copied again each minute)
- Clock in the Sports header
- Possession / batting icons, NFL and college records, college football Top 25 ranks
- Fix channel → Find Channels searches the guide for just that game
- Watch live channel on a game marked Final (for overtime)
- Follow Teams & Leagues: Exclude titles… hides guide shows like radio or paddock shows
- Follow Teams & Leagues: per-league Event listings (Scheduled events / TV guide listings / Both)
- My Sports preview no longer flickers through leagues while moving fast
- Sports settings → Watching → Channel checks: checks a game's feeds in the background (off by default); with Best Channel First, working and higher quality feeds come first, with a quality badge

## Other
- Low-memory TVs: a channel downloads less ahead while memory is nearly full, and the freeze checker can no longer be what closes the app (crash while watching Live TV on a 224 MB Xiaomi TV)
- Download playlist movies and episodes to a folder, TV storage, SD card or USB drive (Downloads in Library / On Demand)
- Lighter subtitles and artwork on low-memory TVs; custom movie buffer sizes are respected
- Startup/crash report: download as a .txt from the phone page, plus more detail on movie startup freezes, source searches and VPN waits
