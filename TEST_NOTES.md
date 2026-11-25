# Test build notes

<!--
What's changed on dev since the last test build. When Build Test App's "What changed" box is left
empty, the test build uses this list (headings with nothing under them are left out), and then
removes the lines it used, so the next test build only shows what's new since. Add lines under the
headings as changes land on dev. Typing in the box uses the box instead and leaves this file alone.
-->

## Live TV
- Fixed the app closing every few minutes for some people with very big playlists (thousands of channels) while the guide was updating
- Guide display (the guide's ⚙) keeps the highlight: Left from the left column no longer slips out and opens the menu
- Live channels connect to the provider once instead of three times (full screen and Multiview), which may stop streams cutting out when Multiview or another device starts
- Sources that failed while the VPN (Full app) or network was still connecting are searched again once it's ready, so a missing add-on shows up without leaving and coming back

## Rewind Anytime

## Recordings
- A series can have its own Start early and Keep recording after times (hold OK on it in Recordings › Series); Default keeps using the Recordings setting, and episodes already set change too
- Opening a series folder no longer jumps; its episode count, watched/new, length and size now sit on the Episodes line
- Recordings opens straight away when the drive it saves to is asleep (the free space shows once the drive wakes); a recording's menu and Information no longer wait for drives either

## Sports
- Leaving Multiview opened from Sports brings back the channel that was playing in the Sports video window
- Sports looks through the TV guide for game channels with less work, so the TV stays calmer while Sports finds channels

## Other
- Live TV sync and backups now carry Usenet providers and indexers (per profile); a Backup now / backup file also keeps this TV's playback choices (passthrough, frame rate, buffer, picture size), which automatic sync still leaves per TV
- A TV that can't open its saved Usenet logins keeps syncing everything else, and gets its Usenet logins back from the cloud copy
- The copied Live TV report now shows VPN speed and how each Multiview screen is playing (buffering, dropped frames), to help work out slow Multiview with the VPN on
