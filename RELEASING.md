# Releasing the companion app on Google Play

## Each release

1. Bump `versionCode` (Play rejects a number it has seen before) and `versionName` in `android/app/build.gradle.kts`. Bump the watchapp's version in `watch/package.json` to match.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max). This is the "What's new" text.
3. Merge to main. The Android workflow builds `nacashu-android-release` (a signed `.aab`) alongside the debug APK.
4. In Play Console, create a release on the track you want (internal, closed or production), upload the `.aab` and paste the changelog.

## Store listing

The listing lives in `fastlane/metadata/android/en-US/` in the layout [fastlane supply](https://docs.fastlane.tools/actions/supply/) and [F-Droid](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) both read. For now it's copied into Play Console by hand.

| File | Play Console field | Limit |
|---|---|---|
| `title.txt` | App name | 30 characters |
| `short_description.txt` | Short description | 80 characters |
| `full_description.txt` | Full description | 4000 characters |
| `images/icon.png` | App icon | 512 x 512 PNG |
| `images/featureGraphic.png` | Feature graphic | 1024 x 500 PNG |
| `images/phoneScreenshots/` | Phone screenshots | 2 to 8 |

Keep "Pebble" out of the app name (Pebble's trademark rules); saying the app works with Pebble watches in the description is fine.

## First-time setup in Play Console

Done once, in this order:

1. **Developer account.** Sign up at [play.google.com/console](https://play.google.com/console) as a personal account (one-time US$25 fee, ID verification).
2. **Create the app.** Name "Nacashu", free, app (not game). The package name `io.github.staindk.nacashu` is fixed once the first bundle is uploaded.
3. **App signing.** Use Play App Signing. Upload the CI key (`NACASHU_KEYSTORE_BASE64`) as the **upload key** and let Google generate the app signing key. Play builds are then signed by Google, so a CI-built APK installed by hand can't update to the Play version (or the other way round): uninstall one before installing the other.
4. **App content** (Policy > App content):
   - Privacy policy URL: the GitHub page for `PRIVACY.md`. The repo has to be public first, or the policy hosted somewhere public.
   - Accessibility API declaration: Nacashu is not an accessibility tool, so declare what the service does (presses the shutter in the chosen camera app when the watch asks), and attach a short video showing the in-app disclosure, the consent, and a photo taken from the watch.
   - Data safety: no data collected or shared.
   - Content rating questionnaire, target audience (18+ is simplest), ads: none, app access: no login needed (explain the app needs a Pebble watch to do anything).
5. **Store listing.** Copy in the files above. Category: Photography.
6. **Closed test.** New personal accounts must run a closed test with at least 12 testers opted in for 14 days in a row before they can apply for production access ([Google's help page](https://support.google.com/googleplay/android-developer/answer/14151465)). Create a closed testing track, add testers by email or a Google Group, and share the opt-in link.
7. **Production access.** After the 14 days, apply from the Dashboard. Google asks a few questions about the test. Once approved, promote the release to production.
