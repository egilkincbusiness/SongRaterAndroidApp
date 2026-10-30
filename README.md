# Song Rater

A single-purpose Android player: it plays the MP3s in one folder in random order, lets you give each a 1–5 star rating, and moves rated songs to another folder. No playlists, sorting, internet access or other options.

## Behaviour

- **Songs to rate** folder: only MP3s directly inside it (no subfolders). Songs are played in a random order, then reshuffled once all have played.
- **Rating** is stored in the ID3v2 POPM frame (the same field TagScanner shows as "Rating"):
  1★ = 1, 2★ = 64, 3★ = 128, 4★ = 196, 5★ = 255 (the standard values used by MusicBee / Windows Media Player).
  Existing ratings are read in bands so odd values from other tools still display sensibly:
  1–31 = 1★, 32–95 = 2★, 96–159 = 3★, 160–223 = 4★, 224–255 = 5★.
- Tap a star at any time during the song (tap another to change it). When the song **ends or you press Next**, a rated song is copied to the destination folder with the new rating written, and the original is deleted. Unrated songs stay in the folder and in the rotation.
- A song that already has a rating is treated like any other: it stays in rotation, shows its current stars, and is only moved after you tap a star.
- If a file with the same name exists in the destination, the new one is named `name (1).mp3`, `name (2).mp3`, ...
- Only the rating is changed. Every other ID3v2 frame (including album art), the ID3v1 tag and the audio data are copied exactly as they were.
- Playback continues with the screen off (notification controls: play/pause, next).
- Settings: source folder, destination folder, theme (follow system / light / dark).

## Getting the APK

### Option A – Android Studio
1. Install Android Studio, choose **Open**, and select this folder. Let it sync (it downloads what it needs).
2. **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file is `app/build/outputs/apk/debug/app-debug.apk`.
3. Copy it to the phone and open it. Android will ask to allow installs from your file manager or browser.

### Option B – no software install (GitHub)
1. Create a free GitHub account and a new private repository; upload everything in this folder (including the hidden `.github` folder).
2. Open the **Actions** tab, run **Build APK**, then download `SongRater-apk` from the finished run. It contains `app-debug.apk`.

## First run
1. Tap **Settings** and choose the two folders. Android 14 does not let apps use the top-level `Download`, `Documents` or storage root, so use (or create) sub-folders such as `Music/ToRate` and `Music/Rated`.
2. Allow notifications when asked (needed for the playback notification).
