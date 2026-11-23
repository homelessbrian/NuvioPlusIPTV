# Nuvio + IPTV 1.2.0-beta.3.1

## What's new

### Sports (redesigned)
- **A redesigned Sports hub.** A game preview and the league table sit beside the list, and My Sports follows the card you're on. Team colours show while a game plays
- **New game page:** a row of the day's games, tabs for Stats, Line-ups, Top Players, Table, Head to Head and more, and a line under every channel saying whether it's showing the game now or later
- **Top Players with photos:** the player of the game, every leader category, and a player profile when you press OK
- **Edit League List:** a bar on All Events removes leagues you never want to see, and brings them back
- **More highlights.** Races, fights, golf, tennis, darts and more get highlights and full replays from the sports' own YouTube channels. Older games keep theirs, and race, fight and tournament rows go back through the whole season. Turn them on or off with **Show Highlights** and **Show Condensed Games** in Settings → Sports
- **Games list while you watch:** Quick Channels has a Games list with the league's other live games. Sports add-on streams also show next to a game's other channels, in Quick Channels and in Multiview
- **Handball** plus Czech and Slovak leagues. College tables have conference tabs and real records
- Followed teams show results from every competition (Nations League, friendlies, cups), and league rows keep the last week's results
- **Back** on any Sports page brings the highlight to the top first, then opens the menu

### Recordings
- **Record anywhere:** to a USB drive, any folder on a drive, or a **network folder** on a NAS or PC (Settings → Live TV → Recordings → Save Recordings To). **Find on Network** looks for computers and NAS boxes on your home network. It works with the VPN in Full app mode too
- **One file per show,** like "Show - S01E02.ts", which plays in VLC and other apps
- **One library for every device.** Every TV using the same network folder sees the same recordings, and the phone app can play them while you're at home
- **Recorded, Scheduled and Series tabs** with posters, and a gear on the Recordings page for Recordings settings
- **Move To** shows real progress, and you can stop a move part way
- **Fix Match:** hold OK on a recording or an On Demand title to pick the right show or movie, or paste an IMDb ID or TMDB link
- **Delete Recordings After** (3 to 30 days), with **Keep Forever** for the ones you pick

### Back up & restore
- **Back up or restore with your phone:** scan the QR code on the TV to download your setup to your phone, or send a backup from your phone to the TV
- Or **save a backup file** (.nptv) on the TV and restore it later (Settings → Live TV → Backup File)

### Playback
- **Best quality:** a new auto-play option that picks the best source. Edit the **Quality rules**, or sort sources with **Best quality first** (Settings → Playback → Stream Selection)
- **Set up for this TV** (first section of Settings → Playback) checks your box, screen and sound and suggests settings, with Apply all and Undo
- **Seek thumbnails** fill in much sooner when they're turned on

### Live TV and Home
- **Live TV, Sports and On Demand rows on Home:** favorites, a playlist group, games on now, a movie or series category, Recently added or Continue Watching On Demand (Settings → Content & Discovery → Home Screen Rows → Add a row). They sync with your other TVs and your phone
- **Favorite groups:** hold OK on a group to star it and move it to the top
- **Show on Profiles:** choose which profiles see each playlist
- **Lock Playlist Logins** (under Parental Controls): when it's on, editing playlists and guides, backups and the network folder ask for your PIN
- **Hide Non-English Titles:** a new switch in Settings → Live TV → On Demand. On Demand also keeps its matches, so posters and details come back straight away
- Episode names show beside the title in the guide, and Up/Down reads a show's whole description
- A **User agent** box for guides, and a switch to leave movies and series out of M3U playlists

### Nuvio 1.2.0-beta.3
- Built on Nuvio 1.2.0-beta.3: automatic audio-based subtitle sync, trailer subtitles, star ratings, drag to reorder collections, season posters in the season tabs and more

## Fixed
- **Multiview** is smoother on more boxes, and panes restart by themselves when the VPN comes back
- **USB recordings** no longer fail when a drive is sleeping or busy
- **Sports highlights** close properly at the end, and a highlight in the Sports window no longer loses the TV's picture when you open another page
- **Race games** like MotoGP now get their channels, and **NFL game tables** show both teams' divisions in full
- Only real wrestling shows appear in Live & Upcoming, and long-finished games no longer show as live (thanks to dwk001)
- Turning a playlist off takes its channels off every game right away
- **Slow-starting channels** (4K, Dispatcharr) get time to load instead of reconnecting
- Shows marked new no longer show the mark twice
- A guide that can't finish updating because storage is full keeps the old guide, with a clear message
- Stalker portals that hand off to another server, or send a few bad listings, now load
