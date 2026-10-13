# DronePeak activation

Run `python3 tools/setup_activation.py` once. It creates two ignored, owner-readable files without printing the secret. In Google Authenticator, choose **Add → Enter a setup key**, use the name **DronePeak**, copy the key from `activation-setup.txt`, and select **Time based**. The `otpauth` URI in that file is also compatible with provisioning tools; never upload it to an online QR service.

Keep both files and an encrypted backup private. Users receive only the current six-digit code, never the setup key. The same key must remain in every subsequent release. Set the GitHub Actions secret `DRONEPEAK_ACTIVATION_SECRET` to that Base32 key before publishing. Local release builds read `activation.properties`; the environment variable takes precedence. Release builds fail if the key is missing or malformed.

Debug builds without a configured key use the public RFC test key, exclusively for development. Use a local TOTP tool with that key to test, or temporarily provision a separate Authenticator test entry. Do not distribute debug APKs as production builds.

Each installed versionCode requires activation once. App updates retain approval records but a new versionCode does not match the previous approval. Uninstall/data clearing resets approval. Old APKs remain usable. Activation needs no network; controller time must match real UTC time. Only the current 30-second period is accepted, so ask users to enter a fresh code promptly. The floating navigation shortcut is available before activation, but hardware operations remain gated. Closing the shortcut is installation-scoped: it stays closed across app launches and reboot, and reinstalling the same signed APK re-enables it without deleting activation. Stopping FCC keepalive does not require activation.

This is an offline convenience gate. A code can activate multiple installations during the same time period. The shared secret is embedded in the APK and can be extracted; changing the app or clock can bypass restrictions. A permanent shared key cannot provide user/device uniqueness, global single-use enforcement or remote revocation.

Before publishing, verify on a real controller that locked app launches, boot auto-FCC and sticky service restarts send no hardware commands; then activate and verify normal FCC/CE, LED, 4G and keepalive behavior. JVM tests cannot validate DJI hardware.
