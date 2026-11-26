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

## Rewind Anytime

## Recordings

## Sports
- Low-memory TVs: Sports does less in the background while a channel plays, so it no longer runs the app out of memory
- Followed leagues with no game on, and every league's week ahead, now come from the smaller ready-made score files
- Finding channels for games is much faster on big playlists: the networks step reads only what's on each channel at the time, and later rounds only read the guide around new or changed games
- A game's page reads only what's on now and next for its channels, not a week of listings every minute

## Other
- Low-memory TVs: a channel downloads less ahead while memory is nearly full, and the freeze checker can no longer be what closes the app (crash while watching Live TV on a 224 MB Xiaomi TV)
