# Removed Android Apple TV remote module

These Android implementation and test files are kept only as local development history.
They are outside all app source sets and are not compiled or packaged. The app no longer
registers receiver services, exposes Apple remote management routes, packages ATV JNI,
or builds the native receiver. Existing installed private pairing data is left untouched.

The normal LAN remote and multiplayer gamepad remain separate supported features.
