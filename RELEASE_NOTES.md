# Nuvio + IPTV 1.2.0-beta.4.2

## What's new

### Recordings
- **Extra time per series:** hold OK on a series in Recordings › Series to give it its own Start Recording Early and Keep Recording After times. Default keeps using your Recordings setting, and episodes already scheduled move to the new times
- A show's folder now shows its episode count, watched/new, length and size on the Episodes line

### Sync and backups
- Live TV sync and backups now carry your **Usenet** providers and indexers (per profile) and your subtitle AutoSync and audio sync choices (thanks to bArKEr)
- **Back up now** and backup files also keep this TV's playback choices (passthrough, frame rate, buffer, picture size and more), so a new TV can be set up the same way. Automatic sync still keeps these separate on each TV (thanks to bArKEr)

### Faster and lighter
- Live channels connect to your provider once instead of three times, in full screen and Multiview. This may stop streams cutting out when Multiview or another device starts (thanks to bArKEr)
- Sports finds game channels in the guide with less work, so the TV stays calmer (thanks to bArKEr)
- A guide you've added twice in different forms (like a playlist's Xtream guide link and the one in its playlist file) is downloaded and read only once
- Guide clean-up runs quietly in the background instead of holding up the guide

## Fixed
- The app no longer closes every few minutes for some people with very big playlists while the guide updates
- Catch-up recordings to a network folder no longer freeze the app or make the box laggy
- **Pause and Rewind Anytime:** channels no longer play, show Loading, then play again when they start, and switching channels quickly no longer mixes up the rewind buffer. Some channels may take a few seconds longer to start (thanks to bArKEr)
- Recordings opens right away when its drive is asleep, and a show's folder no longer jumps when you open it
- Match Frame Rate Off is really off now, guide and Sports previews included, and audio passthrough Off now turns Dolby/DTS into normal sound on every HDMI output (thanks to bArKEr)
- Stalker portals that hand the login over to another server keep playing (thanks to bArKEr)
- My Sports cards that showed no channels now get the channels you saved for their league (thanks to bArKEr)
- Leaving Multiview opened from Sports brings back the channel in the Sports video window (thanks to bArKEr)
- Sources that failed while the VPN or network was still connecting are searched again once it's ready, so a missing add-on shows up by itself (thanks to bArKEr)
- Leaving Live TV for a movie stops the catch-up download in the background (thanks to bArKEr)
- Removing an add-on no longer crashes the add-on list (thanks to bArKEr)
- Automatic sync keeps working when the copy it compares against on the TV can't be read (thanks to bArKEr)
- In the guide's display menu (⚙), Left no longer slips out and opens Nuvio's menu
- With only one profile, a playlist hidden on that profile can be shown again, and the playlist now says it's hidden

## Special thanks
- [@ghbarker](https://github.com/ghbarker) (bArKEr) for Usenet and playback settings in sync and backups, the Rewind Anytime, live channel, VPN and Stalker fixes, and lighter Sports channel finding
