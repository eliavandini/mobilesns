# MySNS – Android client for my.scientificnet.org

A Jetpack Compose app that shows your **upcoming lectures**, **exam enrolments** (with enrol / unenrol)
and **grades** from [my.scientificnet.org](https://my.scientificnet.org).

## How sign-in works

1. On first launch the app asks you to **Add account**.
2. It opens the real website in an in-app browser (`WebView`). You sign in with your Microsoft
   account exactly as on the website.
3. Once the website is loaded and starts calling its own APIs, a small script
   (`app/src/main/assets/token_capture.js`, injected only into `https://my.scientificnet.org`)
   hands the bearer tokens the website sends to the app, and the browser closes.
4. The app then calls the same REST endpoints directly:

| Data | Endpoint | Token audience |
|------|----------|----------------|
| Profile / student id | `webservices.scientificnet.org/rest/cm/core/api/Userdata/GetUserdata` | `CockpitMobileTest` |
| Lectures | `webservices.scientificnet.org/rest/cm/student/api/v2/Timetable?from&to` | `CockpitMobileTest` |
| Grades / credits | `api.scientificnet.org/studyplan-course-selection/api/Student/{id}/career`, `/creditpoints` | `CockpitMobileTest` |
| Exams | `api.scientificnet.org/exam-enrolment-evaluation//api/student/{id}/exams` | `mysns-exam-enrolment-evaluation-api` |
| Enrol / unenrol | `POST` / `DELETE …/api/student/{id}/exams/enrol` `{examSessionId, examId}` | `mysns-exam-enrolment-evaluation-api` |

Tokens last about an hour. When one expires, the app silently reopens the website in the
background (your Microsoft session cookie is kept), captures fresh tokens, and only shows the
browser if Microsoft actually needs you to sign in again.

## Builds

Every push to `main` or a `claude/**` branch runs `.github/workflows/release-apk.yml`, which runs
the unit tests, builds a signed release APK and publishes it as a GitHub Release (`build-N`).
APKs are signed with `keystore/sideload.jks` (committed on purpose so updates install over each
other — keep this repository private).

Local build:

```bash
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```
