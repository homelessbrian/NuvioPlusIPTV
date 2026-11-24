# Nuvio + IPTV 1.2.0-beta.4.1

## What's new

### Live TV
- **Backups with headings:** the Backups list and a game's channels in Quick Channels are grouped by playlist, add-on or quality, in the order you pick (Settings → Live TV → Playback → Backups & Game Channels, or the play bar's gear)
- **Adaptive Live TV Buffer:** a new switch in Settings → Live TV → Playback that slowly builds a 20-second cushion by playing live TV a touch slower, so channels stall less (thanks to bArKEr)
- **Shows that already aired stay in the guide** when a guide update stops listing them (up to your Past Shows Kept setting), and catch-up keeps the shows in between
- **Find & stream** opens the movie or series page straight away when there's one clear match

### Recordings
- **Episode descriptions** under the highlighted episode in a show's folder and in Information. When the guide had none, the description comes from TMDB
- The play bar shows the **recording's poster** instead of the channel logo

### Sports
- **What you follow loads first** when the app opens, and followed leagues' rows (UFC, F1, golf) are saved between launches, with last week's results
- **Left** stops at the start of a row. **Back** takes you to the top

### Faster and lighter
- The guide, On Demand and Sports use less memory, On Demand rows appear as they load, and the app opens faster with your saved channels (thanks to bArKEr)
- The guide keeps only what's needed in memory while the full guide stays saved, so big guides no longer weigh the app down, and days ahead are still all there (thanks to bArKEr)
- Big M3U playlists reload without holding a second copy, and pausing live TV, rewinding and recording are lighter on the box (thanks to bArKEr)
- Copying recordings to a network folder is much lighter on the box

### Nuvio 1.2.0-beta.4
- Built on Nuvio 1.2.0-beta.4: built-in Usenet, Blu-ray disc titles, Simkl rewatches, a separate style for ASS/SSA subtitles, much faster audio sync, and the remote's audio button for picking audio tracks

## Fixed
- **Recordings** pick up from the same spot after a freeze or error instead of stopping, with a clear message and OK to try again
- Recordings from another TV no longer stay stuck as "recording"
- A scheduled recording whose folder can't be made no longer closes the app
- Each TV keeps its own network folder address instead of taking another TV's
- **VPN:** a quiet WireGuard connection no longer counts as broken, and pressing play checks the connection straight away (thanks to bArKEr)
- **Name (A–Z)** sorts every group, reordered ones included, and skips tags like "US:" (2 comes before 10)
- The full-screen **TV Guide** tile always opens the guide, even for a channel started from Sports or Recordings
- A slow DNS lookup can no longer hold up a playlist or channel
- Seek thumbnails on recordings no longer slow down playback
- A new poster from Fix Match shows on the Home card too
- Trailers play on detail pages opened from More like this
- A channel that hits a video error after the app comes back from the background tries again, and moves to the box's other video decoder if its own keeps failing (thanks to bArKEr)
- On boxes with 2 GB of memory or less (like many Fire TVs), a big Live TV buffer no longer uses more memory than the box can spare (thanks to bArKEr)
- Posters that failed to load try again later instead of staying blank, and posters already found still show while an add-on is slow (thanks to bArKEr)

## Special thanks
- [@ghbarker](https://github.com/ghbarker) (bArKEr) for the Adaptive Live TV Buffer, the VPN and playback fixes, and a big round of speed and memory work
