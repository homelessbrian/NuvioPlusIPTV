# Test build notes

<!--
What's changed on dev since the last test build. When Build Test App's "What changed" box is left
empty, the test build uses this list (headings with nothing under them are left out), and then
removes the lines it used, so the next test build only shows what's new since. Add lines under the
headings as changes land on dev. Typing in the box uses the box instead and leaves this file alone.
-->

## Live TV
- Channels from add-ons: Settings › Live TV › Playlists › Add channels from an add-on adds an add-on's channel list (like CNCVerse Bridge's Clown IPTV or EasyPlay) as a playlist, with its countries as groups. Links are asked from the add-on when you tune; listings come from your guides by channel name. Hold OK on its playlist heading in the guide (or open it in Settings) to turn Guide matching off or update it
- Simple Live TV (Settings › Live TV › Playback › Moving Around): OK in full screen opens big Recent Channels rows, OK again opens the guide over the video
- Subtitle size and position: player gear › Subtitles (Left/Right to change) and Settings › Live TV › Playback › Picture & Sound
- Your remote moved to Settings › Live TV › Setup
- Channel Order (was Backups & Game Channels): new Preferred groups to break ties; with no headings the list now follows your order once one is set

## Rewind Anytime

## Recordings
- Keyword recording: + New Recording › Keyword records every upcoming show on that channel whose title contains your words (e.g. college football)

## Sports
- Sports now also looks in add-on PPV lists for games, and no longer treats "Live TV" channel lists as game lists
- Sports: the same snooker, darts or golf event listed under different names on several channels now shows as one card with all its channels
- Sports uses your Channel Order everywhere: game page list, the channel reminders and auto-switch tune, and which channels a game keeps when it has many. A game's Sort channels button and Sports settings › Channels open the same screen
- Channel checks: new Auto with preview (keeps checking other feeds while one plays, uses a second provider connection)
- Hide Scores / Show Scores button in the Sports bar, next to All Events
- No more false score flashes when moving between games; smoother moving between My Sports rows; scores stay readable beside the game preview

## Other
- Watch Stats under Account (off until Track viewing activity is turned on)
- On Demand downloads show the file size first
- Adaptive Buffer Limit (Experimental) in Playback › Buffer & Network, off by default
- Setup Sync no longer re-applies the same VPN profiles over and over
- A Live TV channel that was still starting no longer starts playing after you open a movie or the stream picker
