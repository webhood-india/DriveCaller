# Drive Caller — Option B

Drive Caller is a minimal hands-free calling assistant built as a real Android default Phone/Dialer app.

## What this version does

- Proper `ACTION_DIAL` activity with a clean keypad.
- Proper Android `InCallService`.
- Incoming call UI with Answer / Decline.
- Ongoing call UI with End Call.
- Drive Mode ON/OFF.
- Bluetooth headset status.
- Caller name lookup from Contacts.
- Text-to-Speech announcement:
  - `Call coming from Rahul.`
  - `Call coming from unknown.`
- Voice commands:
  - Answer: `accept`, `answer`, `pick up`
  - Reject: `reject`, `decline`, `hang up`
- GitHub Actions cloud APK build.

## Important Android requirement

For Android to grant the `ROLE_DIALER` / default Phone role, the app must handle `ACTION_DIAL` and implement `InCallService` with incoming and ongoing call UI. This project includes those components.

## Build

Push the project to GitHub. The workflow:

`.github/workflows/build-apk.yml`

will build:

`DriveCaller-OptionB-debug-apk`

The APK is a debug build for testing.

## First test

1. Install the APK.
2. Open Drive Caller.
3. Grant Contacts and Microphone permissions.
4. Tap `Set as default Phone app`.
5. Accept Drive Caller as the default Phone app in Android's role dialog.
6. Connect Bluetooth earbuds.
7. Turn Drive Mode ON.
8. Call the phone from a saved contact.
9. Confirm the app announces the caller.
10. Test `Accept`, `Answer`, `Pick up`.
11. Test `Reject`, `Decline`, `Hang up`.

## Note about production

This project is intended for device testing first. Android/OEM behavior around background speech recognition, Bluetooth microphone routing, battery optimization, and telecom UI should be validated on the target devices before production release.
