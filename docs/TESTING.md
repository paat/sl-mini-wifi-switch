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
   observed), with `setup_complete` false. The app must say “Your Mini is ready”.
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
   observed), with `setup_complete` false and “Your Mini is ready” in the app.
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
