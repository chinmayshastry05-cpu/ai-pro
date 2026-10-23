# CI emulator test (for whoever runs this)
Push this whole tree to the repo branch `ci-test`. The workflow
`.github/workflows/emulator-test.yml` fires on push to that branch (or via
Actions -> emulator-test -> Run workflow on ci-test). It builds the debug APK,
boots an arm64 API 34 emulator on macos-14, enables the app's accessibility
service, opens ci/testpage.html over http://10.0.2.2:8901, launches the overlay,
drag-selects a text line, taps a word, and saves verdicts + logcat + screenshots
to the `test-results` artifact. Report the run URL, verdict files, and any
FATAL/SIGSEGV logcat lines back.
v8 candidate build + C1 C2 C7 run.
