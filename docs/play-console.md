# Google Play setup notes

Answers and steps for publishing Hush Studio (`com.orailnoor.hush`) on Google Play. Check the wording in Play Console against Google's current policy pages before submitting.

## 1. Report button backend (Firebase Cloud Firestore)

The app sends reports with Firestore's REST API; no Firebase SDK or `google-services.json` is needed.

The project is `hush-studio-reports` (Firestore in asia-south1), owned by the slidekali@gmail.com Google account. The rules live in `firebase/firestore.rules`; to change them, edit that file and deploy from the `firebase/` folder:

```
firebase deploy --only firestore:rules --project hush-studio-reports --account slidekali@gmail.com
```

To set up a fresh project instead: create it in the Firebase console (Analytics not needed), create a Firestore database in production mode, update `firebase/.firebaserc`, and deploy the rules as above.

The app needs the **Project ID** and the **Web API key** (Project settings > General, under the web app "Hush Studio Android reports"). Add both to a Gradle properties file that is not committed (this repo uses `.gradle-home/gradle.properties`, which the build reads through `--gradle-user-home`), then rebuild:

```
hushReportProjectId=your-project-id
hushReportApiKey=your-web-api-key
```

A Firebase web API key identifies the project and is not a secret; the rules are what protect the database (anyone can create a report, nobody can read or change them through the API). Read reports in the Firebase console under Firestore > reports. Delete reports older than 12 months, as the privacy policy states (a Firestore TTL policy on `createdAt` can do this automatically).

Builds without these properties still show the Report button; reports are kept on the phone until a build with them is installed.

## 2. Data safety form

- Does the app collect or share user data? **Yes** (only reports, which the user chooses to send).
- Is all collected data encrypted in transit? **Yes** (HTTPS).
- Can users request deletion? **Yes** (via the issue tracker or contact address in the privacy policy).
- Data types collected:
  - **App activity > Other user-generated content**: the report reason, note and reported narration text. Collected, not shared. Optional. Purposes: **App functionality** (content moderation required by policy).
  - **App info and performance > Other app performance data** is not needed; the app version and Android version are diagnostic fields of the report. If the form asks, list them under **App info and performance > Diagnostics**, optional, App functionality.
- Not collected: audio, voice recordings, files, contacts, location, identifiers. Voice samples, scripts and generated audio are processed only on the device and never leave it, so they are not "collected" in Play's terms.

## 3. Permissions and foreground services

- `RECORD_AUDIO`: recording a voice sample to clone, only after the user taps Record.
- `POST_NOTIFICATIONS`: shows progress for long tasks.
- Foreground service declaration:
  - **dataSync**: downloading and verifying the speech model, and importing audio, which the user starts and can cancel.
  - **mediaProcessing**: generating speech, which runs for minutes and shows progress; the user can stop it.

## 4. AI-generated content policy

- In-app reporting: **Report this audio** on any generated chunk in the studio, and **Report** in the Library menu, without leaving the app.
- Misuse safeguards: a permission checkbox before saving a cloned voice (the time is stored with the voice); exported WAV files carry an "AI-generated speech" label in their metadata; the privacy policy and store listing tell users to clone only voices they have permission to use.

## 5. Signing

Use the existing key (`~/keys/hush-release.jks`, certificate SHA-256 `2c36404b...e25a`) as the **app signing key** in Play App Signing ("Use existing app signing key", exported with Play's PEPK tool), so the GitHub and Play versions can update each other. Build the bundle with:

```
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`.

## 6. Store listing assets

- App icon: `artifacts/store/play-icon-512.png` (512 x 512)
- Feature graphic: 1024 x 500, still to make
- Phone screenshots: at least 2 (Create, Voices, Studio work well)
- Privacy policy URL: https://github.com/orailnoor/hush-studio/blob/main/PRIVACY.md
