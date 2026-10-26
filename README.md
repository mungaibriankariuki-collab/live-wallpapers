# Live Wallpapers

An Android live wallpaper app. Pick a built-in wallpaper, any photo, or any video from your phone and it becomes a moving wallpaper.

- **4K Gallery:** 102 free-licence portrait photos in 11 categories (space, landscapes, sky & weather, birds, insects, wildlife, flowers, fungi, ceilings & interiors, architecture, cities), downloaded only when you pick one.
- **Videos** loop silently, cropped to fill the screen, at 0.5x–2x speed.
- **Particles:** sparkles, snow, rain, fireflies, bubbles or stars, with amount and speed controls. Sparkle, star and bubble colours come from the picture itself.
- **Touch:** tap an empty spot for a burst of light; drag to scatter particles.
- **Motion:** a breathing zoom, 3D tilt parallax that follows the phone, and sliding as you swipe between home screen pages, each with its own strength control.
- **Look:** Vivid, B&W, Warm, Cool, Dreamy, Noir and Sunset filters, plus brightness and a vignette.
- **My collection:** photos and videos you add are kept for later. Long-press one to remove it.
- **Auto change:** a new wallpaper each time the screen turns on, or every 5 min, 15 min, 1 hour or 1 day, in order or shuffled. You can also double-tap the home screen for the next one.
- **Transitions:** each new wallpaper fades in.
- **Battery:** photos animate at 30 fps (60 fps optional) and slow down further in Battery Saver. Everything pauses while the wallpaper is hidden.

Every effect works on photos and on videos. Rendering is OpenGL ES 2.

## Install

Download the latest APK: https://github.com/mungaibriankariuki-collab/live-wallpapers/releases/latest/download/LiveWall.apk

Open the app, choose a wallpaper, tap **Set as live wallpaper**, then **Set wallpaper** in the preview. Some phones then ask whether to use it for the home screen, the lock screen, or both.

## Build

The APK is built by GitHub Actions on every push to `main` (`.github/workflows/build.yml`) and published as a release. Signing uses the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repo secrets.

## 4K Gallery

The gallery photos are Wikimedia Commons *Featured pictures*, limited to public domain, CC0, CC BY and CC BY-SA licences. They're portrait shots at least 2160 px wide. They are hosted as assets of the `gallery-v1` release, together with `catalog.json`, which lists every photo's author, licence and source page. The app shows these credits on each photo. To rebuild the gallery, run `tools/build_catalog.py` and then `tools/finalize.py`.

## Built-in images

The five built-in wallpapers (`app/src/main/assets/wallpapers/`) were saved from a TikTok wallpaper post and show characters owned by Disney/Pixar and Square Enix. They are included for personal use only.
