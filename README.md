# Hearth TV

A fast, ad-free home screen for Fire TV smart TVs (Fire OS). Big clock, local weather, a favorites row and every installed app in a clean grid.

## Get it on a TV

1. On the TV, install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Settings → Device & Software → Developer Options → Install unknown apps → turn on for **Downloader**.
3. Open Downloader and enter:
   `https://github.com/vanstaverenalex/hearth-tv/releases/latest/download/HearthTV.apk`
4. Install. To update later, repeat step 3 — your favorites are kept.

## Using it

- **☰ (menu) button** on any app: add to favorites, move left/right, hide, app info.
- Hidden apps live behind the **Hidden apps** tile at the end of the grid.
- Weather is set to Kalamazoo, MI — change `LAT`/`LON` in `Weather.kt`.

## How it builds

Every push to `main` builds a signed APK on GitHub Actions and publishes it as the latest release. The signing key lives in the `KEYSTORE_B64` and `KEY_PASS` repo secrets so every version installs over the last one.

## Credits

- Fonts: Fraunces and Manrope, SIL Open Font License 1.1.
- Weather: Open-Meteo. Live TV listings (coming): TVmaze, CC BY-SA 4.0.
