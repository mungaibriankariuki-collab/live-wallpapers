# Live Wallpapers

An Android live wallpaper app. Pick a built-in wallpaper, any photo, or any video from your phone and it becomes a moving wallpaper:

- **Videos** loop silently, cropped to fill the screen.
- **Photos** come alive with a slow breathing zoom, 3D tilt parallax that follows your phone, and glowing particles tinted with the picture's own colours. The effects can be switched on or off and also work over videos.

Rendering is OpenGL ES 2. Photos animate at about 30 fps and videos at their own frame rate, and everything pauses while the wallpaper is hidden, to save battery.

## Install

Download the latest APK: https://github.com/mungaibriankariuki-collab/live-wallpapers/releases/latest/download/LiveWall.apk

Open the app, choose a wallpaper, tap **Set as live wallpaper**, then **Set wallpaper** in the preview. Some phones then ask whether to use it for the home screen, the lock screen, or both.

## Build

The APK is built by GitHub Actions on every push to `main` (`.github/workflows/build.yml`) and published as a release. Signing uses the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repo secrets.

## Built-in images

The five built-in wallpapers (`app/src/main/assets/wallpapers/`) were saved from a TikTok wallpaper post and show characters owned by Disney/Pixar and Square Enix. They are included for personal use only.
