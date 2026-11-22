# Nuvio + IPTV 1.2.0-beta.3.1

## What's new

### Recordings
- **Record to a network folder (NAS or shared folder).** Settings → Live TV → Recordings → Save Recordings To → Network Folder. **Find on Network** searches your home network for computers and NAS boxes, you sign in and pick a folder. It works with the VPN in Full app mode too
- **One file per show,** like "Show - S01E02.ts", that plays in VLC and other apps. Every TV on the same folder shares one library, and the folder follows your Setup Sync
- **Record to any folder on a USB drive,** for boxes that don't give the app its own folder on a drive. Save Recordings To and Move To now list each drive once
- **Recorded, Scheduled and Series tabs** with posters, and a gear on the Recordings page for Recordings settings (also its own tab in Settings → Live TV)
- **Fix Match:** hold OK on a recording or an On Demand title and search for the right show or movie, or paste an IMDb ID or TMDB link. A pick covers every recording of that show and syncs to your other devices
- **Delete Recordings After** (3 to 30 days), with **Keep Forever** for the ones you pick

### Sports
- **A new Sports hub.** A game preview and league table sit beside the list. The game page has a row of the day's games, tabs and a line under every channel that says if it's showing the game now or later. My Sports follows the card you're on, and team colours show while a game plays
- **Top Players with photos:** the player of the game, every leader category and a profile when you press OK
- **Pick Leagues:** on All Events, see several leagues together, with a Live only switch
- **More highlights.** Races, fights, golf, tennis, darts and more get highlights and full replays from the sports' own YouTube channels. Older games and past races keep theirs, and race, fight and tournament rows go back through the whole season
- **Show Highlights** and **Show Condensed Games** switches in Settings → Sports
- Sports add-on streams show next to a game's other channels in the backup list, Quick Channels and Multiview. A new **Games** list in Quick Channels shows the league's other games while you watch one
- **Handball,** Czech and Slovak leagues added. College tables have conference tabs and real records
- **Back** on any Sports page brings the highlight to the top first, then opens the menu
- Followed teams show results from every competition (Nations League, friendlies, cups), and league rows keep the last week's results

### Home
- **Live TV, Sports and On Demand rows on Home.** Settings → Content & Discovery → Home Screen Rows → Add a row. Favorites, a playlist group, games on now, a movie or series category, Recently added or Continue Watching On Demand. They work in every Home layout and keep their place on all your TVs
- **On Demand** keeps its matches, so posters and details come back straight away, and has a **Hide Non-English Titles** switch (Settings → Live TV → On Demand)

### Playback
- **Best quality auto-play** with your own **Quality rules** and a **Best quality first** source order (Settings → Playback → Stream Selection)
- **Set up for this TV** (first section of Settings → Playback) checks your box, screen and sound and suggests settings, with Apply all and Undo
- **Seek thumbnails** fill in much sooner

### Live TV
- **Favorite groups:** hold OK on a group to add it. Favorites sit at the top with a star
- **Show on Profiles** for each playlist, and a **Backup File** (.nptv) you can save on the TV or with your phone and restore (Settings → Live TV)
- **Lock Playlist Logins** under Parental Controls: editing playlists and guides, backups and the network folder ask for your PIN
- A **User agent** box for guides, and a switch to leave movies and series out of M3U playlists
- Read a show's whole description with Up/Down in overlay mode and the guide. Episode names show beside the title in the guide

### Nuvio 1.2.0-beta.3
- Built on Nuvio 1.2.0-beta.3: automatic audio-based subtitle sync, trailer subtitles, star ratings, drag to reorder collections, season posters in the season tabs and more

## Fixed
- **Multiview** no longer stutters on some boxes
- **USB recordings** no longer fail when a drive is sleeping or busy
- **Sports highlights** that reach the end now close properly, and a highlight in the Sports window no longer loses the TV's picture when you open another page
- **Race games** like MotoGP now get their channels, and **NFL game tables** show both teams' divisions in full
- Only real wrestling shows appear in Live & Upcoming, and long-finished games no longer show as live (thanks to dwk001)
- **Slow-starting channels** (4K, Dispatcharr) get time to load instead of reconnecting
- Shows marked new no longer show the mark twice
- A guide that can't finish updating because storage is full keeps the old guide, with a clear message
- Stalker portals that hand off to another server, or send a few bad listings, now load
