# Aide

A personal Android app for a student's day: class timetable, attendance tracking with a 75% check, and reminders for tasks and daily practice. Built with Java, XML and Material 3. Everything is stored on the phone.

Aide started as a private app for one person. It is shared as it is.

## What it does

- **Timetable** for Monday to Friday, with the day view, a weekly editor, subjects (theory and lab, each with its own minimum attendance) and meal times.
- **Day changes** without touching the weekly timetable: holidays and exam periods, cancelled classes, moved classes, a Saturday that follows another weekday's timetable, and a faculty member taking a free slot.
- **Attendance** per subject with a present, absent and percentage view. For each subject it shows how many classes you can still skip, or how many you must attend, to stay above the minimum.
- **Reminders** for tasks and one-off items, a morning brief, and daily practice slots that ask at the end whether you did it.
- **Google Calendar sync** for the next 14 days of classes, with a reminder before the first class.
- **Home screen widget** showing the next item.
- **Optional AI features** that need a Gemini API key: quick add by typing or voice, plan my day, timetable changes in plain words, and reading a timetable from a photo. The rest of the app works without a key.

## Important: it comes with the author's timetable

A fresh install starts with the author's subjects, slots, rooms and meal times already loaded. There is no blank first-run setup yet. To make it your own, open Timetable, then More, then "Edit the weekly timetable" and "Subjects and attendance rules", and change or remove what you don't need. With a Gemini key you can also use "Replace timetable from a photo".

## Install

1. Open the Releases page and download the latest `Aide-vX.Y.Z.apk`.
2. Allow installing from that source when Android asks.
3. Open Aide and allow the permissions you want: notifications, calendar, and usage access for long-use nudges.

Android may warn that the app comes from an unknown developer. The source code is all here, so you can build it yourself instead.

## Optional: Gemini key

Create a free key in Google AI Studio, then open Aide, the three-dot menu, then Settings, and paste it. The key is stored encrypted on your phone and is only sent to Google's Gemini API. Free-tier limits apply, and Aide retries automatically when the limit is reached.

## Build from source

- Android Studio, JDK 17, Gradle 8.7, Android Gradle Plugin 8.6.1
- minSdk 26, targetSdk 34, compileSdk 35
- Open the folder that contains `settings.gradle`, then Run on a device.

Pushing a tag that starts with `v` builds a signed APK in GitHub Actions and publishes it as a Release. It needs these repository secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Privacy

Timetable, attendance and reminders stay in a local database on the phone. The only network use is the optional Gemini calls and Google Calendar sync through the phone's own calendar provider.
