# Ember Audio Intelligence

Native Android implementation of the Ember audio assistant, using the supplied `ember_audio_intelligence_v2.html` prototype as the interface reference.

## Build

GitHub Actions builds a debug APK on pushes to `main` and manual dispatch. Download the `Ember-debug-apk` workflow artifact. This is a development build, not a signed Play Store release.

## Implemented foundation
- Native Android WebView host for the supplied dark Ember interface
- JavaScript bridge for enumerating Android audio input/output devices
- User-initiated foreground microphone service with persistent notification
- Android runtime permission requests for microphone, Bluetooth connect and notifications
- Native Android text-to-speech bridge
- Vosk Android dependency included; a compatible offline acoustic model still needs to be installed from the downloads repository

## Important limits
The HTML prototype contains simulated device connection, profile matching, wake listening, automation, translation and permissions. The bridge is the first native integration layer; these simulated screens must be connected to platform APIs before those features can be considered complete. Android does not let an ordinary app silently connect to arbitrary Bluetooth devices or bypass lock-screen protections.
