# Nuvio + IPTV 1.2.0-beta.2.1

## What's new

### Sports
- **Official highlights and condensed games** on finished games, for ESPN, NHL and MLB, plus YouTube highlights that play right in the app
- **Replays from your sports addons for every sport,** not just football, labelled Full game, Condensed or Highlights, best quality first. They load faster and show as each addon answers
- No replays for a game? If you don't have it, the game page suggests the **Nuvio Live Sports** addon
- When a highlight or replay finishes, you go back to the game's page. Live games that drop still keep reconnecting
- Team colours are the same everywhere, and games weeks ahead show the real colours

### Multiview
- **Hold OK on a pane to watch it full screen.** Back takes you to all your panes, still playing
- Pane names, numbers and the highlight fade away with the top bar
- **Filter the Sports tab:** My Sports, All Events, Live Now, or one sport at a time. Your choice is remembered

### Live TV
- **A guide for each playlist:** paste a guide link when you add a playlist (on the phone page too, Stalker portals included), and switch each playlist's guides on or off under **Guides**
- **On Demand from M3U playlists:** movies and series straight from the playlist, and the expiry date for Xtream providers
- **DNS setting:** System (default), Cloudflare or Google. Try it if channels say your provider's server can't be found or your internet company blocks IPTV. On System, the app tries Cloudflare and Google by itself when it needs to
- **Copy several channels to a group** at once
- **Hide Stray Entries:** leaves out odd leftovers some providers put in their playlists, like a single episode sitting in its own group
- **Airing Today** now lists shows it used to miss (lots of news shows), with their Live TV options
- **A cleaner look:** darkness options are now **Light / Standard / Dark**, overlay mode dims shows that already aired and shows what's on now in bold with a LIVE tag, Multiview uses the same background as the Guide and Sports, and the playlist menu shows account details under each name

### Profiles
- **Copy Settings** between profiles now includes Live TV favorites, groups, channel order, hidden items, and Sports and Calendar choices
- **Keep Matched:** keep profiles the same, so a change on one applies to all. **Stop Matching** is in Profile Options

### Calendar
- F1, NASCAR and IndyCar weekends show each session on its own day and time
- Golf and tennis tournaments show on every day they're on ("Day 2 of 4")

### Nuvio 1.2.0-beta.2
- Now built on Nuvio 1.2.0-beta.2: support for Stremio tracking addons, a subtitle outline width setting, styled subtitles that keep their colours and positions, your place in the video is kept when you switch sources, and more sources fall back properly when one won't play
- Arabic translation completed, plus Hebrew, Slovak and French updates

## Fixed
- **Update now** really re-downloads your playlists instead of keeping the old copy
- **Deleting a playlist** now clears everything it left behind (hidden groups, favorites, custom group channels, channel order, reminders and scheduled recordings). Leftovers from playlists you already deleted are cleared too
- **Sync:** a playlist or guide order you change is no longer undone, and favorites, hidden channels and your own groups now combine between TVs instead of one TV overwriting the other
- Stalker portal guides now save properly, and update errors no longer show your portal account details
- **Multiview:** holding OK in the channel list no longer sends a pane to full screen, and holding OK on a game in the Sports tab opens its options on every remote
- The VPN can no longer end up on without a profile chosen, even after a sync
- Sports division standings show in the right order (NBA divisions came out worst first)
- Turning on Recordings in Menu & Features no longer blinks and jumps back
