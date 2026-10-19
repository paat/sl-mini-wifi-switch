# User experience requirements

These are product requirements, not suggestions. A feature isn't done until it meets them.

## Who uses the app

People with no technical background, often in the field: outdoors, in sunlight or rain, possibly wearing gloves, without internet, and in a hurry. They know how to plug in a cable and join a Wi-Fi network. They don't know what a subnet, bypass mode, gRPC or a factory reset is, and they shouldn't need to.

## Core requirements

1. **Foolproof.** The user can't put the Mini into a state they didn't intend, and can't get stuck.
   - The app detects the Mini's state itself and only offers actions that work in that state. It never offers a button that will fail.
   - Every screen has one clear next step. No dead ends: every error says what happened and what to do now, with a button for it.
   - Before anything that changes the Mini, a confirmation screen says in plain words what will happen and how to undo it. Example: "Wi-Fi will turn off. To turn it back on later, you'll need to reset the Mini again (about 3 minutes)."
   - Waiting is always visible: a progress indicator, the expected time ("usually about 2 minutes"), and what to do if it takes longer.
   - Invalid input can't be submitted. Rules are shown before the user types, not after.

2. **For non-technical people.**
   - Plain words only in the main interface. Use "Wi-Fi off" and "Wi-Fi on with password", never "bypass", "setup request", "subnet", "gRPC" or "API". Technical details live only in an Advanced or Details screen.
   - Short sentences, one idea each. Name things by what the user sees: "the reset symbol on the back of the Mini", "the cable port".
   - Two big choices on the main screen, matching the two field modes in the README.
   - After a reset, Home says “Ready to set up” and explains both choices in one short sentence each. Details is secondary; no manual check, reset help or read-only footer competes with setup.
   - Home and Reset help detect readiness automatically. A restarting Mini shows progress; “Check again” appears only after checking has stopped. Automatic detection never leaves editing, confirmation, writing or a Start here guide page.

3. **Visual guides.** Every physical step has an illustration, and a short animation where movement matters:
   - Where the reset symbol is on the back of the Mini, and how to press it (press until it clicks, keep holding for 3 seconds).
   - What the light on the Mini does during a reset, and what "done" looks like.
   - How to connect: USB-C Ethernet adapter into the phone, cable into the Mini's port. Or: join the Wi-Fi called STARLINK.
   - After Wi-Fi off: connecting the Mini's cable to the router's internet (WAN) port.
   - What Android's own "connect to device" dialog looks like, so the user knows to tap Connect.
   - The waiting screen shows what is happening ("The Mini is restarting").
   - All illustrations are bundled in the app and work offline.
   - Illustrations are drawn for this project (vector drawables or animated vector drawables). Don't use Starlink photos, logos or screenshots of the official app.

## Wi-Fi off specifics

- The confirmation screen says that afterwards the Mini has no Wi-Fi and no local network of its own, and that its cable must go to a router.
- **After success, the result screen tells the user to connect the Mini's Ethernet cable to their router's internet port (WAN port)**, with an illustration: Mini → cable → router port labelled "WAN" or "Internet" (often a different colour). Include a short note that this port may be labelled WAN, Internet, or with a globe symbol.
- The same screen says how to undo it: reset the Mini, then choose "Wi-Fi on with password" in this app.
- Details screen only: a single device (one laptop or phone) can also be plugged in directly and gets an internet address from Starlink, but only one at a time (FINDINGS.md test 7).

## Secure Wi-Fi specifics

- Offer a generated password by default (letters and digits, at least 12 characters), with an option to type your own.
- Show/hide toggle on the password field; show the password in large, easy-to-read type on the result screen.
- After success, show a **Wi-Fi QR code** so other phones can join by scanning.
- Remember the last Wi-Fi name and password (encrypted), and offer "Use the same Wi-Fi as last time" so the user's devices reconnect by themselves.
- Explain once, in the Details screen only, that the Mini's network uses 192.168.1.x after a reset and that changing it needs the official Starlink app.

## Field conditions

- Primary buttons at least 64 dp tall; all touch targets at least 48 dp.
- High contrast in light and dark themes; readable in direct sunlight.
- Works with system font size up to 200% without clipped text.
- No feature depends on internet access.
- No colour-only meaning: every state has an icon and words.

## Accessibility and language

- Every illustration has a content description; every step works with TalkBack.
- All text in `strings.xml`, ready for translation. English first.

## Acceptance test (before a public release)

Give the app to at least three people without technical background, with a Starlink Mini and no other help. Each must, without assistance:

1. Switch the Mini to Wi-Fi off.
2. Switch it back to Wi-Fi on with a password and join that Wi-Fi with a second device.

Record time taken, every place they hesitated, and every question they asked. Fix those before release.
