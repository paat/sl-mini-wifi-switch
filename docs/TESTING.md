# Testing

## Unit tests (no hardware)

`tools/vectors/*.hex` hold frames as space-separated hex bytes:

| File | What it is |
|---|---|
| `get_device_info.request.hex` | read request, id 1 |
| `wifi_get_config.request.hex` | read request, id 1 |
| `wifi_get_status.request.hex` | read request (`get_status`), id 1 |
| `wifi_setup_bypass.request.hex` | write: setup with bypass, id 1 (sent to a device with id 4, otherwise identical) |
| `wifi_setup_name.request.hex` | write: setup with name `camera2.4`, id 1 (sent to a device with id 6) |
| `wifi_setup_name_password.request.hex` | write: name `ExampleNet`, password `example-pass-123`, id 1 (**never sent to a device**) |
| `wifi_set_config_disable_radios.request.hex` | write the device refuses; for parser and error-path tests only |
| `wifi_setup.response_ok.hex` | captured success response: data frame (id 4, api_version 132, empty `wifi_setup`) + trailer `grpc-status: 0` |

Every request builder must reproduce its vector byte for byte.

## Hardware tests (a human with the Mini in reach)

Rules: a person runs these, with the Mini within reach for a factory reset. An agent may prepare commands but must not send write requests to a device itself.

### Setup

- Phone with USB debugging and a USB-C Ethernet adapter cabled to the Mini, or joined to the Mini's Wi-Fi.
- Push vectors: convert a `.hex` file to binary and `adb push` it to `/data/local/tmp/`.
- Send from the phone so the request leaves through the right interface:

```sh
curl --interface eth0 -s -m 15 \
  -H "content-type: application/grpc-web+proto" -H "x-grpc-web: 1" \
  --data-binary @/data/local/tmp/wifi_get_config.bin \
  -D - -o /data/local/tmp/resp.bin \
  http://192.168.1.1:9001/SpaceX.API.Device.Device/Handle
```

Use `--interface wlan0` to test over Wi-Fi. Check the `Grpc-Status` header and the trailer at the end of `resp.bin`.

### Read-only checks (safe any time)

1. `wifi_get_config` → status 0; note `setup_complete`, SSIDs, auth.
2. `get_device_info` to the router (port 9001) and to the dish (`192.168.100.1:9201`).

### Write checks (each needs a factory reset first)

Factory reset: press the reset icon on the back of the Mini until it clicks, hold about 3 s until the LED blinks fast and goes off. Wait until the router answers at `192.168.1.1` (35–110 s seen) and `setup_complete` is false.

| Test | Send | Expect |
|---|---|---|
| Bypass | `wifi_setup_bypass` | status 0; Wi-Fi gone; Ethernet gets `100.64.0.0/10` |
| Name only | `wifi_setup_name` | status 0; router restarts; SSID set, `auth_open` |
| Name + password | a frame with your own name and password | **record result in FINDINGS.md**: status, auth type |

Restore the Mini to the state you want afterwards (another reset plus setup, or the official app).

### M2 app: Bypass write check over both connections (human only)

Run the following twice, with a fresh physical factory reset before **each** run:

1. **Ethernet run:** connect the phone's USB-C Ethernet adapter to the Mini.
   **Setup Wi-Fi run:** unplug the phone's Ethernet adapter and join `STARLINK`
   (or use the app's Join STARLINK action and Android's Connect confirmation).
2. Press the reset icon on the back until it clicks; hold about 3 seconds until the
   LED blinks fast and goes off. Wait for `192.168.1.1` to answer (35–110 seconds
   observed), with `setup_complete` false. The app must say “Ready to set up”.
3. Choose Wi-Fi off. Check the no-Wi-Fi/no-local-network warning, router requirement,
   and reset-to-undo wording. On Wi-Fi, also check the expected disconnection warning.
   Tap Cancel first: the Mini must remain pending with STARLINK available.
4. Choose Wi-Fi off again and tap **Turn Wi-Fi off** once. This is the Bypass
   `wifi_setup_bypass` write from the table above; do not also send the curl vector.
   Expect status 0 if the response arrives, visible progress, and Wi-Fi disappearing.
5. **Ethernet:** within about 60 seconds, expect an address in `100.64.0.0/10`,
   no router answer at `192.168.1.1:9001`, and read-only dish info at
   `192.168.100.1:9201`. Expect “Wi-Fi is off”.
   **Setup Wi-Fi:** expect the phone to disconnect and a result explaining that
   Wi-Fi is likely off. Note whether the response arrived before the disconnection.
   Connect Ethernet to check the same address/router/dish evidence independently.
6. Follow the result illustration: move the Mini's cable to the separate router's
   WAN/Internet/globe-labelled port. Check the port-label note and undo instructions.
   Interrupting verification or losing the connection without success evidence must
   offer reconnect/check guidance without automatically sending another write.
7. Restore the desired state with another physical reset plus setup or the official
   app. Record date, dish/router firmware, response status (or lost response), timing,
   and exact observations in FINDINGS.md. These M2 app checks remain unperformed
   until a human records them; the earlier protocol-only Ethernet test is not a substitute.

### M3 app: Name + password and auto-connect (human only)

These checks remain unperformed. A human must keep the Mini within reach. Run the
Name + password check over **Ethernet and the open STARLINK Wi-Fi**, with a fresh
physical factory reset before each write. Do not also send the curl vector.

1. Connect the phone by USB-C Ethernet adapter, or disconnect Ethernet and join
   STARLINK (including Android's Connect confirmation if requested).
2. Press the reset icon on the back until it clicks, hold about 3 seconds until
   the LED blinks fast and goes off. Wait for `192.168.1.1` to answer (35–110 seconds
   observed), with `setup_complete` false and “Ready to set up” in the app.
3. Choose **Wi-Fi on with password**. Check the generated name/password, show/hide,
   own-password option, and rules before typing. Cancel the confirmation first:
   STARLINK must stay open and setup must remain pending. Reopen, review the name,
   restart and reset-to-undo explanation, then tap **Turn Wi-Fi on** exactly once.
4. This is the **Name + password** write from the table above. Record status or lost
   response. Expect visible 1–2 minute progress, no duplicate write, and up to 3
   minutes of read-only verification. Over Wi-Fi, accept Android's Connect dialog
   for the new name. Verify `setup_complete` and secure auth on **every** BSS
   (WPA2/WPA3 observed previously); do not compare the masked read-back password.
5. Check the result name/password and scan the QR with a second phone. Join using
   both the QR and typed password. Reopen the app and, after another physical reset,
   check **Use the same Wi-Fi as last time** restores the correct credentials.
   Confirm that app private storage contains only the encrypted profile blob.
6. On a real **API 30+** phone, the connection explanation opens after success.
   Continue to Android and tap **Save** once (or use Back to view the password/QR,
   then **Join on this phone** to reopen the explanation). Check that the temporary STARLINK/new
   Wi-Fi request has been released, the network is saved in phone settings, and the
   phone joins automatically. Close the app and confirm the saved network remains.
   Exercise Cancel/declined and an already-saved name; messages must match and the
   QR/password must remain available. Check old-password guidance for an existing
   network. Android may avoid a no-internet network; record any manual selection needed.
7. If available, repeat on a real **API 29** phone. Allow the Wi-Fi suggestion
   notification and check automatic joining. Refuse the notification once and check
   the password/QR fallback. Record phone model, Android version and approval steps.
8. Separately test provisional limits, with a fresh reset for each accepted write:
   names of 1 and 32 UTF-8 bytes (including multibyte names), passwords of 8 and 63
   printable ASCII characters, spaces and symbols (especially `\ ; , : "`). Confirm
   the second phone joins and QR escaping works. In the app, 0/33-byte names,
   7/64-character passwords, non-ASCII passwords and control characters must keep
   Review disabled. Testing device acceptance of rejected boundary values requires
   a separate human-run protocol test; record actual device limits before changing
   the provisional rules. Never record real passwords in logs or FINDINGS.md.
9. Interrupt verification, decline temporary Connect, or leave the Mini unreachable
   for 3 minutes. Expect reconnect guidance and **no automatic repeat write**.
   Reconnect by cable or manually join the new saved-profile Wi-Fi and check again.
   Rotate during confirmation: preserve the choice and enable Confirm only after a fresh read.
   Rotate or background during progress: keep the same bounded verification and send no second write.
   Decline the temporary Connect dialog, tap Try connecting again, and confirm it only rejoins/reads.
   From Done/Back and a fresh launch, open Show my Wi-Fi password and check name, QR and Join on this phone.
10. Restore the desired state with a physical reset plus setup or the official app.
    Record date, dish/router firmware, connection path, phone/Android version,
    response status, timing, auth on each BSS, boundary results and auto-connect
    observations in FINDINGS.md. Do not tick hardware boxes until these tests run.


### M4 app: field guide and quick entry (human only)

These checks remain unperformed. Automated screenshots and contrast calculations
do not establish sunlight readability or TalkBack usability.

1. With no internet and the Mini within reach, open **Start here**. Follow one step
   at a time: press the reset icon on the back until it clicks; hold about 3 seconds
   until the light blinks fast and goes off. Wait about 2 minutes (35–110 seconds
   previously observed). Check the drawing against the real symbol and indicator.
   Confirm that the app only says ready after its read-only state check.
2. Follow both connection paths: USB-C Ethernet adapter into the phone and cable
   into the Mini; or join STARLINK and tap Connect in Android's message. Exercise
   Back at each step, including the first step and the cable/Wi-Fi alternatives.
   Check that the existing two choices appear after detection. No setup write is
   needed for this guide check. Use the M2/M3 procedures above for actual changes.
3. In airplane mode, open Reset help from Home and available error screens. Read
   every reset step and illustration. Back must return to the previous screen;
   Check again returns to detection. Do not mistake an unlit indicator for ready.
4. Enable TalkBack and walk through Home, both guide paths, reset help, setup
   confirmations, waits, results and errors. Check focus order, drawing descriptions,
   labelled controls, and that the still wait indicator does not announce a fake
   completion percentage. Enable **Remove animations**, including while a guide
   is open: diagrams must settle into still final poses.
5. At 200% font size on a real phone, scroll through both themes and operate every
   action. Check sunlight readability outdoors and touch use with gloves. Record
   phone model, font/display settings, theme and any obscured text or missed taps.
6. Add **Set up Mini** to Quick Settings and tap it. Long-press the app icon and
   launch its **Set up Mini** shortcut (pin it if the launcher supports this).
   Test a cold start and reopening from guide/help/details. Both must open Home and
   detect before showing choices; the tile must not change Wi-Fi or contact the
   Mini in the background. Check API 34+ and an older phone if available.
7. During the human-run M2/M3 progress checks, reopen using each quick entry point.
   The same progress screen must remain; no repeated write or discarded result.
   Record TalkBack, sunlight and Quick Settings observations in the PR. Record
   any confirmed hardware facts with date and firmware in FINDINGS.md.

### Robustness follow-up (#11, #14, #17; human only)

Use the existing M2 steps 1–7 and M3 steps 1–10 above; each accepted write still needs
its own physical reset and explicit confirmation tap. During M2 step 3 and M3 step 3,
rotate and background/return before confirming: no write-error message should appear,
and confirmation must wait for a fresh read. During M2 step 4 and M3 step 4, rotate and
background/return: the same verification should finish without another setup request.
After M2 step 5 or M3 step 4, open Details and check the last router data plus fresh
dish/config data. During M3 step 9, decline or time out Connect, retry with the visible
button, rotate the error screen and retry again; each retry must be read-only.

Read-only checks: attach an unrelated Ethernet LAN while STARLINK is available and
check that discovery reaches the Mini over Wi-Fi. With a configured Mini and reachable
dish, a failed router read must not claim Wi-Fi is off. In Settings grant a previously
denied Nearby devices permission and return; the denied banner must clear. After M3
step 5, Done/Back/relaunch must all retain Home access to the encrypted saved password,
QR, and phone join action. These hardware checks remain unperformed.

### Automated regression evidence (#11, #14, #17)

All controller/network tests use fake transports or fixtures; they send nothing to hardware.

| Item | Regression test (class.method) |
|---|---|
| #11.1 | `SetupViewModelNetworkTest.failedEthernetProbesFallThroughToAvailableStarlinkWifi` |
| #11.2 | `MiniStateDetectorTest.reachableDishAndOneRouterFailureCannotClaimWifiOff` |
| #11.3 | `MiniStateDetectorTest.intentionalNetworkReleaseClearsRestartHistory` |
| #11.4 | `StarlinkNetworksTest.requestedWifiIgnoresInitialLinkPropertiesUntilGatewayArrives`; `requestedWifiDhcpWaitIsBoundedAfterAndroidReportsAvailable` |
| #11.5 | `MiniStateDetectorTest.timedOutProbeRetainsDetailsAndMarksUncertaintyWithoutRecordingAbsence` |
| #11.6 | `StarlinkNetworksTest.zeroOrInvalidSubnetPrefixesAreNotRouterEvidence` |
| #11.7 | `OkHttpTransportTest.trailingDotAndEncodedHostsAreRejectedBeforeOpeningSocket` |
| #11.8 | `SetupViewModelLifecycleTest.nearbyPermissionGrantedInSettingsClearsTheCorrectDeniedBanner` |
| #14.1 | `SetupViewModelLifecycleTest.rotationRetainsBothConfirmationScreensAndUnsavedCredentialsWithoutAWrite`; `processDeathOnWifiOffConfirmationNeverReportsAnInterruptedWrite` |
| #14.2 | `SetupViewModelLifecycleTest.rotationAndBackgroundKeepAnInFlightWriteAndVerificationAliveWithoutResending` |
| #14.3 | `SetupViewModelLifecycleTest.returningToConfirmationRequiresFreshReadBeforeTheTapCanWrite` |
| #14.4 | `WifiOffFlowTest.unavailableAddressOnlyAllowsLikelySuccessAfterConfirmedWrite` |
| #14.5 | `WifiOffFlowTest.wifiLossAfterUnsentWriteDoesNotClaimLikelySuccessOrRetry`; `SetupViewModelNetworkTest.replacementStarlinkHandlePreventsWifiLossEvidence` |
| #14.6 | `SetupViewModelNetworkTest.wifiOffVerificationRefreshesDishDetailsAndKeepsLastRouterSnapshot` |
| #17.1 | `SetupViewModelLifecycleTest.savedPasswordCanBeReopenedAfterDoneAndAfterFreshLaunchWithoutClaimingVerification`; `ScreenshotsTest.savedPasswordHomeLight`; `savedPasswordHomeDark` |
| #17.2 | `SetupViewModelNetworkTest.secureWifiVerificationRefreshesDetailsWithReadBackConfig` |
| #17.3 | `SetupViewModelLifecycleTest.reconnectAfterBackgroundedErrorAndDeclinedDialogOnlyRequestsWifiAgain`; `WifiOnFlowTest.explicitReconnectVerificationCanRecoverAfterFailureWithoutSendingAnyWrite` |
| #17.4 | `StarlinkNetworksTest.savedNameRequiresStarlinkIdentityAtTheDiscoveredGateway`; `savedNameWithoutDhcpGatewayDoesNotProbeFixedAddresses` |
| #17.5 | `RobustnessScreensTest.unicodeNameLimitHasPlainRecoveryAndCannotContinue` |
| #17.6 | `SetupViewModelLifecycleTest.profileEncryptionAndPersistenceRunOffTheCallingThread`; `MainActivityRecoveryTest.saveFailureThenRetryAndConfirmSendsExactlyOneWrite` |

Validation on 2026-10-18:

- `ANDROID_HOME=/home/dev/android-sdk ./gradlew --no-daemon build test`: passed;
  311 app tests in 21 suites and 52 protocol tests in 5 suites, zero failures,
  errors or skipped tests. Android lint completed with 20 warnings and zero errors.
- Targeted `:app:recordRoborazziDebug`: 33 screenshot tests passed; 57 existing
  images updated and 8 new light/dark images added. Selection: `savedPasswordHome*`,
  `timedOutHome*`, `details*`, secure setup/progress/reconnect, Wi-Fi-off progress/likely
  result/200%-font result, and M4 restarting captures. No unrelated screenshots recorded.
- Hardware checks above were not run; no writes were sent to real hardware.


## PR #18 tribunal round 1 regression evidence

All network behavior below uses fake transports; no real Mini writes were performed.

| Finding | Regression evidence |
|---|---|
| T-001 | `MainActivityRecoveryTest.helpCheckAgainReturnsToResultAndPreservesCredentials`; `RobustnessScreensTest.savedPasswordHomeEntryReachesPasswordAndQrInEveryPostSetupState` follows Configured, Rebooting and NotFound Home entries through password and QR, then Done. Existing `savedPasswordEntryAvailableInEveryHomeState` covers visibility in all states. |
| T-002 | `FieldGuideScreenshotsTest.guideStepChangesStartAtTopAtLargeFontIncludingBackAndAlternatePaths` scrolls each step to the bottom and checks the next step starts at zero, including Back and cable/Wi-Fi alternatives at 200% text. |
| T-003 | `MainActivityRecoveryTest.quickLaunchRoutesOnlyIdleScreensHomeAndPreservesSetupAndResults` covers both results, phone-save guidance, editing, both confirmations and both progress screens. |
| T-004 | `ResetPressAnimationTest.pressHoldsThreeSecondsThenRemainsReleasedThroughCycleEnd` samples the entire three-second hold and the 4000–4500 ms released pause. |
| T-005 | `FieldGuideScreenshotsTest.homeRebootingLight` / `homeRebootingDark` assert no Start here action and visible waiting/recovery text; `SetupViewModelLifecycleTest.homeKeepsRebootDeadlineThroughStopStartUntilMiniAnswersAgain` verifies the same polling job reads through restart and recovery. |
| T-006 | `FieldGuideScreenshotsTest.reducedMotionShowsOffAndDoesNotClaimHalfFinished` asserts static waiting text and no progress-range semantics. Affected light/dark and 200%-text guide/wait screenshots show the clock and text. |
| T-007 (part of T-003) | `MainActivityRecoveryTest.quickLaunchKeepsPermissionStateEvenAfterDialogRequestIsConsumed`; `SetupViewModelLifecycleTest.quickLaunchDuringSecurePermissionPromptStillSendsExactlyOnceAfterGrant` exercises the permission request, quick launch, grant and one fake write, with no resend. |
| T-008 | `MainActivityRecoveryTest.coldQuickLaunchAppliesIdleRuleAfterSavedStateRestoration` and `coldQuickLaunchKeepsRestoredSavedCredentials` exercise actual Activity creation with the setup action and saved state. |

Screenshot selection: `ScreenshotsTest.rebooting*`,
`FieldGuideScreenshotsTest.homeRebooting*`, `lightLight`, `lightDark`,
`lightLarge*`, and `restarting*`. Only these 12 affected screenshot tests were
recorded: 22 existing images updated and 4 new Home restart images added.

Validation on 2026-10-18: `ANDROID_HOME=/home/dev/android-sdk ./gradlew --no-daemon build test`
completed successfully. App: **322 tests in 22 suites**; protocol: **52 tests in
5 suites**; total: **374 tests in 27 suites**, with **0 failures, 0 errors and
0 skipped**. Android lint: **20 warnings, 0 errors**. Human hardware and physical
animation checks remain the existing M4 procedures above.


## PR #18 tribunal round 2 regression evidence

All network tests use fake transports and Android network callbacks. No hardware
writes were performed.

| Finding | Regression evidence |
|---|---|
| T-001 | `MainActivityRecoveryTest.saveFailureThenRetryAndConfirmSendsExactlyOneWrite`: storage failure sends nothing, keeps credentials and setup evidence, then review/confirm (including a repeated tap) sends exactly once. |
| T-002 | `SetupViewModelLifecycleTest.consumedQuickLaunchKeepsGuideAndErrorOnRotation`: initial and new quick-launch intents are consumed; subsequent rotation keeps FieldGuide's step and WifiOffError. Existing cold-launch restoration tests still pass. |
| T-003, T-005 | `MiniStateDetectorTest.probeTimeoutAndReleasePreserveTheOriginalRestartDeadline`: timeout and release retain the original window until its exact expiry. `SetupViewModelLifecycleTest.homeKeepsRebootDeadlineThroughStopStartUntilMiniAnswersAgain`: stop/start preserves both recent reachability and an active wait, then recovery succeeds. `MiniStateDetectorTest.intentionalNetworkReleaseClearsRestartHistory` retains #11.3 behavior. |
| T-004 | `SetupViewModelNetworkTest.resumeKeepsTypedFormAndRequestsWifiOnlyAfterReconnectTap`: editing and both confirmations survive stop/start, no automatic request, declined and accepted explicit reconnect, unchanged credentials, fresh read before confirmation and no writes. `failedResumeProbeKeepsTypedFormAndConfirmationDisabled` covers failed reads. `SetupReconnectScreensTest` covers the tap-only button, disabled writes, retained password and light/dark/200% text. |

Only the 10 new disconnected editing/confirmation captures from
`SetupReconnectScreensTest` were recorded, in `docs/screenshots/m3/setup_reconnect_*`.
Existing screenshots were not re-recorded. Waiting/reconnect presentation is shared
by editing and both confirmation screens.

Guard audit against `07aa574`: **2 added, 2 removed, net 0** guard sites. Added:
active-reboot check on intentional history release and the UI's reconnect-availability
check. Removed: automatic request on missing STARLINK and the NotFound-to-Home
fallback message check. The existing resume condition now excludes a pending
user-requested connection. Sharing connection presentation replaces two duplicated
waiting checks with one waiting and one reconnect rendering check (net 0). As a
cross-check, all source `if (...)` sites in the five changed Kotlin production files
remain **109 before / 109 after**; tests are excluded.

Human follow-up (not performed): use the existing M2/M3 robustness procedures above.
During M3 steps 2–3, type a name/password, background and return with setup Wi-Fi gone.
The same form must remain with Reconnect to the Mini and no unsolicited Connect
dialog. Tap Reconnect, decline, retry and accept; credentials must remain and no
write occurs until the separate confirmation tap. Repeat on Wi-Fi-off confirmation.
Open from the tile/shortcut, visit the guide or an error, then rotate: keep that screen.
During the existing restart wait, background/return must not restart the deadline.

Validation: `ANDROID_HOME=/home/dev/android-sdk ./gradlew --no-daemon build test`
completed successfully in 36 seconds: **336 app tests in 23 suites**, **52 protocol
tests in 5 suites**, **388 total tests in 28 suites**, **0 failures, 0 errors,
0 skipped tests**. Protocol test outputs were up-to-date (protocol is unchanged).
Android lint: **20 warnings, 0 errors**. The earlier targeted test compile failed
because the new test lacked coroutine extension imports; those imports were added
before the passing targeted and full runs.
Final affected-image verification: `:app:verifyRoborazziDebug --tests
'*SetupReconnectScreensTest'` passed all **10 tests in 1 suite** against the recorded
images after the shared presentation refactor, with no image differences.


## #22: Read-only Home status

Configured Home now shows radio state, distinct Wi-Fi names and password availability
without opening Details. The real saved password stays behind the existing result
flow, offered on configured Home only for a matching secured Wi-Fi name. A matching
name does not prove that the saved password is still valid; the result remains
explicitly unverified. Other Home states keep the existing saved-profile recovery.
The protocol parser still discards masked password fields.

Configured, Wi-Fi-off and unreadable-settings Home states all explain that changing
setup requires a factory reset and open the existing Reset help with a primary
button. Unknown never enables setup. NotFound is reserved for silent endpoints,
subject to the existing bounded restart wait.

### Regression evidence

All probes used fake transports, and no real hardware was contacted.

| Area | Tests / evidence |
|---|---|
| Unreadable answers | MiniStateDetectorTest: router config refused initially/after setup; dish answering without CGNAT initially/after setup; unreadable dish information with CGNAT. All five failed before the fix. |
| State transitions and write refusal | Detector reachable/restart/recovery matrices now include Unknown. WifiOffFlowTest and WifiOnFlowTest each assert Unknown returns reset guidance with zero requests. Expanded state baseline: 72 tests, 10 failures, zero errors/skips. |
| Candidate selection | SetupViewModelNetworkTest preserves an Unknown answer when a later network is silent and preserves sole Unknown instead of connection guides. Both failed before the fix. A later readable setup still wins. An additional assertion reproduced an erroneous 180000 ms restart timer after an Unknown answer; excluding that answered scan from miss recording fixed it. |
| Password matching | SetupViewModelHomeStatusTest initially had six tests: four failed for mismatched names, stale/unreadable config, open authentication and unknown authentication; two preservation checks passed. Final nine tests also cover no saved profile, mixed authentication for one name, and a separate open network. |
| Home UI | HomeStatusTest: all 12 initial regressions failed before the UI fix. They cover quick status, equal/differing bands, globally disabled radios, disabled service sets, distinct names, hidden/open passwords, tap-only saved credentials, reset actions and 200% text. Five later checks cover Unknown heading/actions/accessibility, absent readings and direct-Starlink wording. The quick-status fixture includes the router's masked password bytes and confirms they never reach the UI. |
| Accessibility / screenshots | Labelled state icons, 64 dp reset action, 48 dp other targets, scrollable 200% text with no text-layout overflow. HomeStatusScreenshotsTest records configured with/without saved credentials, Wi-Fi off and Unknown in light/dark at 100%/200% fonts. |

**Already green before the fix:** matching secured credentials still opened the
existing unverified result; error-screen credential recovery still worked; a later
readable SetupPending candidate already won over an unreadable candidate; the
expanded stale-reachability test already ended in NotFound. These are preservation
checks, not reproduced failures. Eight further behavior checks (five UI and three
credential checks) and 16 screenshot cases were added after implementation, so no
pre-fix failure is claimed for them.

### Validation on 2026-10-19

The requested command was run, then repeated with all tasks forced after final
test edits:

    ./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks

Final result: **384 app tests in 26 suites**, **52 protocol tests in 5 suites**;
**436 tests in 31 suites**, with **0 failures, 0 errors, 0 skipped**. All 59
actionable Gradle tasks executed; build succeeded in 22 seconds. Android lint:
**20 warnings, 0 errors**. Debug APK assembled. An earlier successful build printed
an internal Kotlin lint analysis exception while a test file was being updated;
the stable final rerun completed without that exception.

Screenshot task selection (used for both record and verify):

    ./gradlew :app:recordRoborazziDebug --tests '*HomeStatusScreenshotsTest' \
      --tests '*ScreenshotsTest.configuredLight' --tests '*ScreenshotsTest.configuredDark' \
      --tests '*ScreenshotsTest.wifiOffLight' --tests '*ScreenshotsTest.wifiOffDark' \
      --tests '*ScreenshotsTest.savedPasswordHomeLight' --tests '*ScreenshotsTest.savedPasswordHomeDark'

Replace recordRoborazziDebug with verifyRoborazziDebug to verify the same selection.
This covers **22 tests in 2 suites**: **40 new PNGs** in screenshots/m5-status
and **6 updated M1 PNGs**. No unrelated screenshot baselines were re-recorded.
Recording passed in 9 seconds; verification passed in 8 seconds with **0 failures,
0 errors, 0 skipped tests** and no image differences.

### Human follow-up (not performed)

These checks are read-only and do not require a setup write:

1. On an already configured Mini, connect the phone by cable or its saved Wi-Fi.
   Use the existing read-only checks above to compare radio status, names and
   authentication with Home. If bands differ, check each displayed band; if they
   agree, check the single summary.
2. With a matching profile saved on the phone, tap Show my Wi-Fi password and check
   the existing unverified saved-name/password/QR result. With no matching profile,
   expect the hidden-password sentence. On an existing open network, expect
   No password. Do not record real credentials in logs or findings.
3. On a Mini already operating with Wi-Fi off, connect Ethernet. With the known
   router-silence/dish/CGNAT evidence, expect Wi-Fi off and direct Starlink wording.
4. If the connected firmware refuses configuration reads, expect unreadable settings,
   not cable/join guidance or setup choices. This case is otherwise covered by fake
   transports; do not change hardware merely to force it.
5. In all three states, tap How to reset the Mini: check the existing illustrated
   offline reset guide and return without performing a physical reset.
6. Repeat the three Home states with TalkBack and 200% font size; check reading
   order, reachable actions and field readability on the actual phone.

Physical Mini checks, real-device TalkBack and outdoor usability remain unperformed.
CI workflows, signing, versions and protocol messages were not changed.


## Configuration-first Home (#32)

Automated tests use fake transports only. `ConfigurationHomeTest` covers the exact
Configured → silent/Rebooting → SetupPending sequence through the ViewModel from
both Home and Reset help, stop/start, Activity recreation, every other screen and
every Start here page, suspended-probe cancellation, and the original restart
deadline. `ConfigurationHomeScreenTest` covers the title, body, explained choices,
small Details target, removed controls/footer, all Home retry states, restart
progress and guide/icon wording. Existing field accessibility tests check 200% text.

Before implementation the initial 16 tests produced 13 failures (5 ViewModel,
8 UI), with no errors/skips. The Home-only reset sequence and two stopped-check
retry UI tests already passed. Deadline, suspended-probe and progress-display
checks were added afterward; no pre-fix failure is claimed for those checks.
A later scroll regression was also run red (1 test, 1 failure) before fixing it:
that implementation re-keyed Reset help to reveal restart progress. Fix cycle 2
below supersedes this behavior with stable scrolling and persistent waiting text.
Bypass and Unknown continuous-polling checks were added as preservation tests.
The earlier cable-after-failed-join test now explicitly taps Check again, because
NotFound ends polling. No protocol or device-write behavior changed.

Human checks (not performed):

1. Connect by Ethernet to a configured Mini and open How to reset the Mini.
   Perform the physical reset from M2 step 2 above. Leave this guide open; expect
   waiting/progress at the top, then “Ready to set up” Home
   automatically, with both explained choices and a small Details action.
2. Repeat while staying on Home. Rotate and briefly background/return during the
   wait. The original three-minute deadline must remain; no setup write occurs.
3. Repeat over Wi-Fi, joining STARLINK through Android if the previous connection
   disappears. If checking times out, connect the cable or join STARLINK and tap
   Check again on Home. In Reset help opened from Home, leave the Mini unreachable
   for over three minutes: waiting must remain, with no Check again. Reconnect the
   cable and expect automatic readiness detection without another tap. Scroll down
   during the wait; state changes must not move the scroll position.
4. Use TalkBack and 200% fonts in both themes. Check the choice explanations and
   icon description, and ensure password editing, confirmation and every Start here
   page keep their context. Do not confirm a setup write for this read-only check.
5. Record date, firmware versions, connection path and actual reset/reconnect timing
   in FINDINGS.md only after a human completes these checks.

### Final software validation (2026-10-19)

Command (with `ANDROID_HOME=/home/dev/android-sdk`):

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
```

Output: `BUILD SUCCESSFUL in 39s`; `60 actionable tasks: 28 executed, 32 up-to-date`.
JUnit XML: **434 app tests / 29 suites**, **52 protocol tests / 5 suites**;
**486 tests / 34 suites**, **0 failures, 0 errors, 0 skipped**. The two new
regression classes contain 24 tests, with four additional restart-guide screenshot
cases. Roborazzi: **279 comparisons, 279 unchanged, 0 added, 0 changed** in the
final verification. Lint: **0 errors, 20 warnings**. Debug APK assembled.

Flow/action guard accounting: **4 added, 4 removed, net 0**. Added: SetupPending
auto-navigation, NotFound polling termination, and stopped-check visibility for the
two retry controls. Removed: the redundant Activity setup-state precheck, the
closeHelp Home-only restart gate, and the duplicated connection-null and
pending/present gates by sharing setup-choice entry. The same write/confirmation
validation remains. Five scattered polling cancellations now belong to the screen
setter's existing eligibility gate; start()/stop() still own foreground lifetime.

Only the 42 screenshot tests whose verification differed were selected for
`recordRoborazziDebug`. They passed; byte-identical captures are not part of the
diff. Updated **62 existing PNGs**, added **4 restart-wait PNGs**, and replaced
**2 obsolete setup-help captures with 2 Details captures**. Exact manifest below
(`M` updated, `A` added, `D` removed); no unrelated baselines were changed.

```text
M docs/screenshots/m1/home_configured_dark.png
M docs/screenshots/m1/home_configured_light.png
M docs/screenshots/m1/home_rebooting_dark.png
M docs/screenshots/m1/home_rebooting_light.png
M docs/screenshots/m1/home_saved_password_dark.png
M docs/screenshots/m1/home_saved_password_light.png
M docs/screenshots/m1/home_setup_pending_dark.png
M docs/screenshots/m1/home_setup_pending_font_200.png
M docs/screenshots/m1/home_setup_pending_light.png
M docs/screenshots/m1/home_wifi_off_dark.png
M docs/screenshots/m1/home_wifi_off_light.png
M docs/screenshots/m2/reset_guide_dark_actions.png
M docs/screenshots/m2/reset_guide_light_actions.png
M docs/screenshots/m2/reset_guide_light_font_200_actions.png
M docs/screenshots/m4/guide_check_dark.png
M docs/screenshots/m4/guide_check_dark_actions.png
M docs/screenshots/m4/guide_check_dark_font_200.png
M docs/screenshots/m4/guide_check_dark_font_200_actions.png
M docs/screenshots/m4/guide_check_dark_font_200_illustration.png
M docs/screenshots/m4/guide_check_light.png
M docs/screenshots/m4/guide_check_light_actions.png
M docs/screenshots/m4/guide_check_light_font_200.png
M docs/screenshots/m4/guide_check_light_font_200_actions.png
M docs/screenshots/m4/guide_check_light_font_200_illustration.png
M docs/screenshots/m4/home_rebooting_dark.png
M docs/screenshots/m4/home_rebooting_dark_help.png
M docs/screenshots/m4/home_rebooting_light.png
M docs/screenshots/m4/home_rebooting_light_help.png
M docs/screenshots/m4/home_setuppending_dark.png
D docs/screenshots/m4/home_setuppending_dark_help.png
M docs/screenshots/m4/home_setuppending_light.png
D docs/screenshots/m4/home_setuppending_light_help.png
M docs/screenshots/m4/offline_help_dark_actions.png
M docs/screenshots/m4/offline_help_dark_font_200_actions.png
M docs/screenshots/m4/offline_help_light_actions.png
M docs/screenshots/m4/offline_help_light_font_200_actions.png
M docs/screenshots/m5-status/bypass_dark.png
M docs/screenshots/m5-status/bypass_dark_actions.png
M docs/screenshots/m5-status/bypass_dark_font_200.png
M docs/screenshots/m5-status/bypass_dark_font_200_actions.png
M docs/screenshots/m5-status/bypass_light.png
M docs/screenshots/m5-status/bypass_light_actions.png
M docs/screenshots/m5-status/bypass_light_font_200.png
M docs/screenshots/m5-status/bypass_light_font_200_actions.png
M docs/screenshots/m5-status/configured_hidden_dark.png
M docs/screenshots/m5-status/configured_hidden_dark_actions.png
M docs/screenshots/m5-status/configured_hidden_dark_password.png
M docs/screenshots/m5-status/configured_hidden_light.png
M docs/screenshots/m5-status/configured_hidden_light_actions.png
M docs/screenshots/m5-status/configured_hidden_light_password.png
M docs/screenshots/m5-status/configured_saved_dark.png
M docs/screenshots/m5-status/configured_saved_dark_actions.png
M docs/screenshots/m5-status/configured_saved_dark_password.png
M docs/screenshots/m5-status/configured_saved_light.png
M docs/screenshots/m5-status/configured_saved_light_actions.png
M docs/screenshots/m5-status/configured_saved_light_password.png
M docs/screenshots/m5-status/unknown_dark.png
M docs/screenshots/m5-status/unknown_dark_actions.png
M docs/screenshots/m5-status/unknown_dark_font_200.png
M docs/screenshots/m5-status/unknown_dark_font_200_actions.png
M docs/screenshots/m5-status/unknown_light.png
M docs/screenshots/m5-status/unknown_light_actions.png
M docs/screenshots/m5-status/unknown_light_font_200.png
M docs/screenshots/m5-status/unknown_light_font_200_actions.png
A docs/screenshots/m4/home_setuppending_dark_details.png
A docs/screenshots/m4/home_setuppending_light_details.png
A docs/screenshots/m4/reset_waiting_dark.png
A docs/screenshots/m4/reset_waiting_dark_font_200.png
A docs/screenshots/m4/reset_waiting_light.png
A docs/screenshots/m4/reset_waiting_light_font_200.png
```


### #32 fix cycle 1 (2026-10-19)

Both review findings reproduced before the fix. The focused regression run reported
`50 tests completed, 6 failed`: repeated SetupPending reads dismissed Reset help,
help lost the save-failure draft/unverified-result origin, and SetupPending Home
exposed stale credentials. The two Compose cases also failed when wired through
`canShowHomeCredentials`, matching MainActivity's actual callback gate.

The poll handler now compares its previous observation with the new state and
only auto-navigates on entry into SetupPending from Home or eligible Reset help
(Home, AlreadySetUp error, or verified Wi-Fi result origin). Help from editing or
an unverified result stays open and Back retains its draft/credentials. No timer
or persistent flag was added. The existing Home credential gate excludes
SetupPending; Configured matching-name/authentication behavior is unchanged.

Regression coverage includes Configured → silence → SetupPending, several polls
while already pending, actual profile-save failure followed by reset help and Back,
unverified-result preservation across a readiness transition, eligible setup-done
origins, and hidden stale credentials in both the model and composed Home. Existing
navigation/cancellation tests cover editing, confirmation, writing and results.
The focused post-fix run passed **51 tests / 4 suites**.

Full command, with `ANDROID_HOME=/home/dev/android-sdk`:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
```

Output: `BUILD SUCCESSFUL in 29s`; `60 actionable tasks: 13 executed, 47 up-to-date`.
JUnit XML: **439 app tests / 29 suites**, **52 protocol tests / 5 suites**;
**491 tests / 34 suites**, **0 failures, 0 errors, 0 skipped**.
Roborazzi: **279 comparisons, 279 unchanged, 0 recorded, 0 added, 0 changed**.
No screenshot baseline was affected or re-recorded. Lint: **0 errors, 20 warnings**.
Debug APK assembled. No hardware requests were made; the existing human #32 checks
remain pending.

Guard audit against `66fb3d8`: **0 added, 0 removed, net 0** guard sites; two existing
conditions were modified (auto-navigation and Home credential eligibility).
SetupViewModel's syntactic counts are unchanged: **58 if**, **9 when**, and
**6 Elvis-return** sites. Screens.kt needs no additional guard because MainActivity
already omits the credentials callback when the model's eligibility gate is false.

### #32 fix cycle 2 — tribunal round 1 (2026-10-19)

T-001, T-002 and T-003 reproduced before production changes. The ViewModel red run
reported `18 tests completed, 6 failed`; the two UI regressions reported
`2 tests completed, 2 failed`. The failures cover continued detection after the
restart deadline and initial NotFound, excluded help origins (including stop/start),
Confirm after Back from an actual profile-save failure, immediate-dispatcher poll
ownership, waiting text after NotFound, and scroll preservation.

The single simplification is to share the existing auto-advance origin rule with
polling eligibility and guide presentation. Eligible help keeps the 3-second poll;
other help stays offline. Active polls survive eligible navigation and mark every
in-flight read as checking. The guide no longer re-keys its column or offers a
redundant Check again route; Back preserves its origin. Immediate-dispatcher tests
assert job identity, one probe per interval, and checking during a suspended read.
The Confirm regression repairs its fake profile store and asserts exactly one
explicit fake setup write after Back. No real hardware is accessed.

Guard audit against `a760137`: **1 added, 2 removed, net −1** guard sites.
Added: retain an active poll. Removed: reset-guide retry visibility and the
checkAfterHelp origin branch. Existing eligibility, auto-navigation, NotFound and
progress conditions were simplified/updated. The origin predicate moved out of
auto-navigation and is shared with polling and rendering; no persistent flag or
timer was added.

Existing English key changed: `reset_wait`. New key: `reset_checking`. No existing
string key was renamed or removed. Only reset-help and shared reset-wait-copy
screenshots are re-recorded. The human checks above remain pending.

Full verification, with `ANDROID_HOME=/home/dev/android-sdk`:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
```

Output: `BUILD SUCCESSFUL in 29s`; `60 actionable tasks: 13 executed, 47 up-to-date`.
JUnit XML: **444 app tests / 29 suites**, **52 protocol tests / 5 suites**;
**496 tests / 34 suites**, **0 failures, 0 errors, 0 skipped**.
Roborazzi: **279 comparisons, 279 unchanged, 0 recorded, 0 added, 0 changed**.
The targeted recording runs covered 36 affected captures; **27 baseline files**
changed. Light/dark and 200% waiting screenshots were inspected.
Lint: **0 errors, 20 warnings**. Debug APK assembled.
The first full run found an omitted 200% baseline and a lifecycle test that expected
start() to force another probe. That test now asserts the same poll job survives
and detects intentional release at the next 3-second interval; both follow-ups
passed in the full command above.
