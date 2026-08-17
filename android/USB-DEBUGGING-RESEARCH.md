# USB Debugging on Windows 10 — Research Report

## TL;DR for your situation

Your device is `USB\VID_04E8&PID_6860` — that's Samsung's standard
"Mobile USB Composite Device" descriptor. Two things are almost
certainly true, and the fix is the same for both:

1. Windows loaded a **generic** driver for the composite device, not
   Samsung's ADB driver. That's why Device Manager shows it as
   "Unknown" and `adb devices` returns empty — `adb` only talks to a
   device when a USB interface is bound to `adbwinusb.dll` (i.e. the
   "Android Composite ADB Interface" / "SAMSUNG Android ADB
   Interface" driver).
2. **The Google USB Driver will not work for this device.** It
   explicitly excludes Samsung — Samsung uses a custom
   `android_winusb.inf` and ships its own. You need the **Samsung
   Android USB Driver for Windows** from `developer.samsung.com`
   ([source][samsung-driver]).

[samsung-driver]: https://developer.samsung.com/android-usb-driver

If you want to skip the driver mess entirely and you have a phone
running Android 11 or newer, **wireless ADB** sidesteps every USB
driver problem. See the last section.

## 1. Background — what USB debugging actually is

`adb` is a client-server protocol. The client is `adb.exe` on the PC;
the server is `adbd` on the phone, listening on a USB interface. For
the two to talk, four things all have to be true simultaneously:

1. The phone exposes a USB interface with class code that matches
   the `adb` driver (vendor-specific `0xFF/0x42/0x01`).
2. Windows has loaded the **Android Composite ADB Interface** driver
   (or a Samsung equivalent) on that interface. `adb.exe` opens a
   file handle through WinUSB / `adbwinusb.dll` to talk to it
   ([source][adbspec]).
3. USB debugging is **enabled in Developer Options** on the phone
   ([source][android-devoptions]).
4. The phone has **authorized the PC's RSA key** (an `adbd` policy
   gate, not a Windows or driver concern; introduced in Android 4.2)
   ([source][rsa-key]).

[adbspec]: https://developer.android.com/tools/adb
[android-devoptions]: https://developer.android.com/studio/debug/dev-options
[rsa-key]: https://stackoverflow.com/questions/23081263/adb-android-device-unauthorized

When `adb devices` returns empty, exactly one of these four is broken.
The Windows 10 / Samsung combination almost always fails at step 2.

## 2. Why your specific failure happens on Windows 10 + Samsung

**Google USB Driver** (`usb_driver_r13-windows.zip`) deliberately
excludes Samsung hardware IDs. The INF file `android_winusb.inf` only
matches a small whitelist of vendor IDs (Google Nexus / Pixel, HTC,
LG, Motorola, a few others) — `0x04E8` is not on it
([source][google-driver], [source][samsung-vidpid]).

[google-driver]: https://developer.android.com/studio/run/win-usb
[samsung-vidpid]: https://www.drivermax.com/Android-Composite-ADB-Interface-Google-Inc_USB-VID_04E8-PID-6860-MI-03-5_0_0_116-2012-10-03-982527-driver.htm

So on Windows 10, when you plug in a Samsung phone:

- Windows Update (or its built-in generic MTP driver) installs a
  driver for the **mass-storage interface** (so you can see the
  phone in Explorer as a media device) — but not the ADB interface.
- That's why Device Manager shows the phone under **"Portable
  Devices"** or **"Other devices"** with a yellow triangle, while
  `adb devices` says "no devices/emulators found."
- Multiple forum posts on this exact pattern (SAMSUNG Mobile USB
  Composite Device, `04E8:6860`, ADB empty) call out installing the
  Samsung driver as the fix ([source1][s1], [source2][s2]).

[s1]: https://stackoverflow.com/questions/41702264/android-adb-devices-does-not-detect-my-phone
[s2]: https://android.stackexchange.com/questions/47824/adb-devices-is-not-listing-my-phone

The Android Studio doc itself, in the OEM driver table, redirects
Samsung to `developer.samsung.com` ([source][oem-drivers]).

[oem-drivers]: https://developer.android.com/studio/run/oem-usb

## 3. The fix (in order of what to try)

### 3.1 Install the Samsung USB driver (the right answer for you)

Download the official installer from
`https://developer.samsung.com/android-usb-driver`. It is
`SAMSUNG_USB_Driver_for_Mobile_Phones.exe` (~35 MB), officially
signed by Samsung, and ships the `android_winusb.inf` that
matches `VID_04E8` for all Samsung models
([source][samsung-driver], [source][samsung-guide]).

[samsung-guide]: https://developer.samsung.com/mobile/galaxy-sdk-getting-started.html

**Steps** (validated against the Samsung Galaxy SDK getting-started
doc and the Windows 10 OEM driver install doc):

1. **Disconnect the phone first.** Don't have it plugged in during
   the installer run. The installer wants a clean slot in Device
   Manager to bind the driver to ([source][samsung-install-youtube]).
2. Run the installer as Administrator. Accept the EULA. The
   default install path is `C:\Program Files\SAMSUNG\USB Drivers`.
3. Reboot. Yes, really — the Samsung installer requires a reboot
   before the new driver will bind correctly to the interface
   ([source][samsung-reboot]).
4. Plug the phone in. **Pull down the notification shade on the
   phone, tap the "USB charging this device" / "USB for charging"
   notification, and select "File transfer / MTP"** — this is the
   step most people miss, because Android 6+ defaults to
   charge-only when the cable goes in, which is exactly the mode
   that hides the ADB interface from `adb` ([source][usbmode-default],
   [source][usbmode-so]).
5. **Unlock the phone screen** (so the RSA prompt can show), then
   run in an admin `cmd`:
   ```
   adb kill-server
   adb start-server
   adb devices
   ```
6. The phone should now show up as `SAMSUNG_Android` with a
   serial, possibly with the "unauthorized" tag if the RSA prompt
   hasn't been accepted yet. Unlock the phone — there will be a
   dialog "Allow USB debugging from this computer?" with an RSA
   fingerprint. Tick **"Always allow from this computer"** and
   tap **Allow** ([source][rsa-key]).

[samsung-install-youtube]: https://www.youtube.com/watch?v=NCrDYUTghgM
[samsung-reboot]: https://odindownload.com/samsung-usb-driver/
[usbmode-default]: https://www.youtube.com/watch?v=vZMofc8nbGA
[usbmode-so]: https://android.stackexchange.com/questions/111710/when-i-connect-via-usb-android-to-pc-it-automatically-starts-charging-how-do

You can pre-empt the default-mode step by going to
**Settings → System → Developer options → Default USB
configuration** and setting it to **File transfer / MTP** (Pixel
behavior) or the Samsung equivalent. Then the phone auto-selects
the right mode the moment the cable goes in.

### 3.2 If the Samsung driver alone isn't enough — Zadig / WinUSB replacement

Some Samsung devices (especially older models, or after a Windows
Update that already grabbed a "wrong" driver) refuse to bind the
official driver cleanly. The community fix is to force-bind
**WinUSB** on the ADB interface with **Zadig**
([source][zadig], [source][zadig-faq]).

[zadig]: https://zadig.akeo.ie/
[zadig-faq]: https://github.com/pbatard/libwdi/wiki/faq

**Steps:**

1. Plug the phone in, set the USB mode to "File transfer / MTP",
   unlock the screen.
2. Open Zadig **as Administrator**.
3. **Options → List all devices** (required, so your phone shows
   up in the dropdown).
4. From the dropdown, pick the **ADB** interface — it will read
   something like "SAMSUNG Mobile USB Composite Device" or
   "SAMSUNG Android ADB Interface" with hardware ID
   `USB\VID_04E8&PID_6860&MI_0X` (the `MI_0X` is the interface
   number; try `MI_00` first, then `MI_01`, `MI_02`, `MI_03`).
5. On the right, pick **WinUSB** (v6.1.7600.16385) as the target
   driver. Don't pick libusbK or libusb-win32 for ADB — they
   don't carry the WinUSB GUIDs `adb` needs.
6. Click **Replace Driver** (or "Install Driver"). Wait for it to
   finish.
7. Repeat for any other ADB interface that shows up under the
   same composite device.
8. Unplug, replug, run `adb kill-server && adb start-server &&
   adb devices`. Should now list your device.

**Rollback:** Zadig's FAQ documents how to restore the original
driver if Zadig replaces something it shouldn't have (Device
Manager → uninstall the device, check "Delete the driver software
for this device", replug) ([source][zadig-faq]).

### 3.3 Windows 10 N / KN edition — extra step

If your Windows 10 is the **N** or **KN** edition (the European
"no Media Player" SKU), Windows is missing the `wpdmtp.inf` that
drives MTP and the Android MTP device. Symptoms: phone shows in
Device Manager with a yellow `!` under "Other devices", or no
MTP device at all ([source][n-edition-1], [source][n-edition-2]).

[n-edition-1]: https://android.stackexchange.com/questions/232321/why-cant-windows-detect-phones-even-when-the-usb-driver-is-installed
[n-edition-2]: https://learn.microsoft.com/en-us/answers/questions/2754056/wpdmtp-inf-file-is-missing

The fix is the **Media Feature Pack**:

- **Windows 10 N**: Settings → Apps → Apps and Features →
  Optional features → Add a feature → "Media Feature Pack"
  ([source][mfp-10n]).
- **Windows 11 N**: Settings → Apps → Optional features → View
  features → "Media Feature Pack".

[mfp-10n]: https://support.microsoft.com/en-us/windows/experience/platform-variants/media-feature-pack-for-windows-n

**Check your edition first** — `winver` from a Run dialog, or
"Settings → System → About → Windows specifications → Edition."
On a plain Windows 10 Home or Pro this is not the issue.

### 3.4 The "Allow USB debugging?" prompt never appears

This is the most common second-level failure: `adb devices` shows
the device as `unauthorized`, and no dialog appears on the phone.

The prompt only fires when:

- The phone is **unlocked** (a locked screen suppresses it
  silently) ([source][rsa-key]).
- The cable is a **data** cable, not a charge-only cable. Try a
  different cable; cheap gas-station cables are often charge-only
  ([source][cable-so]).
- The USB mode on the phone is set to something other than
  charging (so ADB is actually trying to talk).

[cable-so]: https://android.stackexchange.com/questions/111710/when-i-connect-via-usb-android-to-pc-it-automatically-starts-charging-how-do

If it's still not showing, the standard fix sequence is:

1. On the phone: **Settings → System → Developer options →
   Revoke USB debugging authorizations**, then turn developer
   options off and back on ([source][rsa-key], [source][revoke-so]).
2. On the PC: delete the cached PC keys:
   `%USERPROFILE%\.android\adbkey` and
   `%USERPROFILE%\.android\adbkey.pub`
   ([source][rsa-key]).
3. `adb kill-server && adb start-server`.
4. Unplug, replug, accept the prompt.

[revoke-so]: https://stackoverflow.com/questions/25236960/running-adb-devices-showing-unauthorized-device

The GeekForGeeks guide has a four-method ladder for this exact
failure ([source][gfg-unauth]).

[gfg-unauth]: https://www.geeksforgeeks.org/android/how-to-resolve-when-adb-devices-shows-unauthorized-device/

## 4. The escape hatch — wireless ADB

If the driver story is a dead end (Samsung Smart Switch refuses
to coexist, your work laptop is locked down, you're in a hurry,
etc.), **skip USB entirely**. Android 11+ supports wireless
debugging with a 6-digit pairing code; it bypasses the cable, the
driver, and the RSA prompt at the same time
([source][android-wireless], [source][xda-wireless]).

[android-wireless]: https://developer.android.com/tools/adb
[xda-wireless]: https://xdaforums.com/t/adb-wireless-debugging-wi-fi-is-there-an-updated-xda-tutorial-yet-on-setting-up-adb-completely-wirelessly-as-of-android-11-no-usb-cable.4476819/

**Steps (one-time, ~30 seconds):**

1. Phone and PC on the same Wi-Fi network.
2. Phone: **Settings → System → Developer options → Wireless
   debugging → enable it.** Tap **"Pair device with pairing
   code"** — the phone shows an IP, a port, and a 6-digit code.
3. PC: in your platform-tools directory (or any shell with `adb`
   on the PATH), run:
   ```
   adb pair <ip>:<port>
   ```
   and type the 6-digit code when prompted.
4. After pairing, the phone shows a second IP+port under "IP
   address & Port". Connect with:
   ```
   adb connect <ip>:<port>
   ```
5. `adb devices` will list the phone. From here, install your APK
   the normal way:
   ```
   adb install -r app-debug.apk
   ```

The pairing persists until the phone reboots or you tap
**Revoke wireless debugging authorizations**. If you re-enable
wireless debugging later, the device auto-reconnects on the same
network (the `Always allow on this network` checkbox in the
Wireless debugging sheet is the relevant toggle)
([source][android-wireless]).

If you want QR-code pairing, VS Code's "Android ADB WLAN"
extension generates an AOSP-compatible QR (`WIFI:T:ADB;S:…;P:…;;`)
that the phone scans from
**Developer options → Wireless debugging → Pair device with QR
code** ([source][adb-wlan]).

[adb-wlan]: https://open-vsx.org/extension/HanWang/android-adb-wlan

## 5. Diagnostic command sheet (run on the PC, with the phone plugged in and unlocked)

Run these in order. They isolate the failure in <2 minutes.

```powershell
# 1. Is the device even visible to PnP?
Get-PnpDevice | Where-Object { $_.InstanceId -match "VID_04E8" } |
    Format-Table Status, FriendlyName, InstanceId

# 2. Which driver is bound to the ADB interface?
Get-PnpDeviceProperty -InstanceId "USB\VID_04E8&PID_6860&MI_00" `
    -KeyName "DEVPKEY_Device_DriverInfName"   # if 0x00 is the ADB MI; try 01/02/03
Get-PnpDeviceProperty -InstanceId "USB\VID_04E8&PID_6860&MI_00" `
    -KeyName "DEVPKEY_Device_DriverVersion"

# 3. Is adb-server running, and is the daemon reachable?
adb start-server
adb devices -l
adb get-state

# 4. If "unauthorized" — reset the keys on both sides
Remove-Item $env:USERPROFILE\.android\adbkey,
          $env:USERPROFILE\.android\adbkey.pub -ErrorAction SilentlyContinue
adb kill-server
adb start-server
# (then unplug, plug, accept the prompt on the phone)
```

## 6. Decision tree (what to try first, in what order)

```
adb devices returns empty
├── Phone shows up in Device Manager as "Android Phone" / "ADB Interface"?
│   ├── YES  → driver is bound. Skip to §3.4 (RSA prompt).
│   └── NO   → driver is not bound. Continue below.
├── Device Manager entry is "SAMSUNG Mobile USB Composite Device"
│   with a yellow !?
│   ├── YES  → Samsung driver not installed (or wrong driver bound).
│   │         → §3.1 (install Samsung driver from developer.samsung.com)
│   │         → if that fails: §3.2 (Zadig WinUSB replacement)
│   └── NO   → Device Manager doesn't see the phone at all.
│             → Try a different USB cable (data, not charge-only)
│             → Try a different USB port (rear-panel > front-panel > hub)
│             → Try a different phone (sanity check the cable/port)
│             → §3.1
├── Windows edition is N or KN? (§3.3)
│   ├── YES  → install Media Feature Pack
│   └── NO   → not the issue
├── Phone running Android 11+?
│   └── YES  → §4 (wireless ADB, fastest path to a working install)
└── RSA prompt not appearing on the phone?
    └── §3.4
```

## 7. Sources consulted (33)

1. Google — *Configure on-device developer options.*
   `https://developer.android.com/studio/debug/dev-options`
2. Google — *Get the Google USB Driver.* `https://developer.android.com/studio/run/win-usb`
3. Google — *Install OEM USB drivers.* `https://developer.android.com/studio/run/oem-usb`
4. Google — *Android Debug Bridge (adb).* `https://developer.android.com/tools/adb`
5. Samsung — *Samsung Android USB Driver for Windows.*
   `https://developer.samsung.com/android-usb-driver`
6. Samsung — *Getting Started with Galaxy SDK.*
   `https://developer.samsung.com/mobile/galaxy-sdk-getting-started.html`
7. Microsoft — *Media Feature Pack for Windows N.*
   `https://support.microsoft.com/en-us/windows/experience/platform-variants/media-feature-pack-for-windows-n`
8. Microsoft Q&A — *wpdmtp.inf file is missing.*
   `https://learn.microsoft.com/en-us/answers/questions/2754056/wpdmtp-inf-file-is-missing`
9. pbatard/libwdi — *Zadig: USB driver installation made easy.*
   `https://zadig.akeo.ie/`
10. pbatard/libwdi — *FAQ.* `https://github.com/pbatard/libwdi/wiki/faq`
11. pbatard/libwdi — *Zadig.* `https://github.com/pbatard/libwdi/wiki/Zadig`
12. Stack Overflow — *My Android device does not appear in the list of adb devices.*
    `https://stackoverflow.com/questions/21170392/my-android-device-does-not-appear-in-the-list-of-adb-devices`
13. Stack Overflow — *Android adb devices does not detect my phone.*
    `https://stackoverflow.com/questions/41702264/android-adb-devices-does-not-detect-my-phone`
14. Stack Overflow — *ADB Device Not Found on Windows.*
    `https://stackoverflow.com/questions/15721778/adb-no-devices-found`
15. Stack Overflow — *ADB + Samsung Galaxy.* `https://stackoverflow.com/questions/1848567/adb-samsung-galaxy`
16. Stack Overflow — *ADB Android Device Unauthorized.*
    `https://stackoverflow.com/questions/23081263/adb-android-device-unauthorized`
17. Stack Overflow — *running adb devices showing unauthorized device?*
    `https://stackoverflow.com/questions/25236960/running-adb-devices-showing-unauthorized-device`
18. Stack Overflow — *How to authorize and accept ADB RSA key with broken touch screen.*
    `https://stackoverflow.com/questions/30178911/how-to-authorize-and-accept-adb-rsa-key-with-broken-touch-screen-on-android`
19. Stack Overflow — *How to connect ADB over wifi network without usb cable?*
    `https://stackoverflow.com/questions/61401495/how-to-connect-adb-over-wifi-network-without-usb-cable`
20. Stack Overflow — *Using ADB to enable File transfer mtp, USB debugging enabled, device set to charging.*
    `https://stackoverflow.com/questions/71082875/using-adb-to-enable-file-transfer-mtp-usb-debugging-enabled-device-set-to-char/71190307`
21. Stack Exchange / android — *'adb devices' is not listing my phone.*
    `https://android.stackexchange.com/questions/47824/adb-devices-is-not-listing-my-phone`
22. Stack Exchange / android — *Why can't Windows detect phones, even when the USB driver is installed?*
    `https://android.stackexchange.com/questions/232321/why-cant-windows-detect-phones-even-when-the-usb-driver-is-installed`
23. Stack Exchange / android — *When I connect via USB (Android to PC), it automatically starts charging.*
    `https://android.stackexchange.com/questions/111710/when-i-connect-via-usb-android-to-pc-it-automatically-starts-charging-how-do`
24. Stack Exchange / android — *Why does my phone occasionally prompt a new RSA key fingerprint.*
    `https://android.stackexchange.com/questions/251082/why-does-my-phone-occasionally-prompt-a-new-rsa-key-fingerprint-for-my-computer`
25. helpdeskgeek — *How to Fix ADB Devices Not Showing in Windows 11.*
    `https://helpdeskgeek.com/how-to-fix-adb-devices-not-showing-in-windows-11/`
26. tencomputer — *Fixed: ADB Device Not Found Error on Windows 11, 10, 8, 7.*
    `https://tencomputer.com/adb-device-not-found/`
27. drivereasy — *[Solved] ADB Device Not Found Error on Windows.*
    `https://www.drivereasy.com/knowledge/solved-adb-device-not-found-error-on-windows/`
28. getandora — *Wireless ADB on Windows — Pair Over Wi-Fi, No Cable.*
    `https://getandora.in/blog/wireless-adb-windows`
29. getandora — *ADB device not found on Windows.*
    `https://getandora.in/blog/adb-device-not-found`
30. getandora — *Fix ADB "Unauthorized" — Bring Back the Allow Prompt (2026).*
    `https://getandora.in/blog/adb-unauthorized`
31. open-vsx — *Android ADB WLAN — Wireless Debugging (VS Code extension).*
    `https://open-vsx.org/extension/HanWang/android-adb-wlan`
32. XDA — *[SOLVED] ADB 'unauthorized', no RSA prompt, no 'Revoke' option.*
    `https://xdaforums.com/t/solved-adb-unauthorized-no-rsa-prompt-and-no-revoke-usb-debugging-option.3693961/`
33. XDA — *[adb] [Wireless debugging] Updated tutorial as of Android 11.*
    `https://xdaforums.com/t/adb-wireless-debugging-wi-fi-is-there-an-updated-xda-tutorial-yet-on-setting-up-adb-completely-wirelessly-as-of-android-11-no-usb-cable.4476819/`

## 8. What I'd try first, on your box

Assuming your phone is Android 11+ and is on the same Wi-Fi as the
PC:

1. **Skip USB for now.** Go to Settings → System → Developer
   options → Wireless debugging → enable it → "Pair device with
   pairing code" → note the IP, port, and 6-digit code.
2. On the PC: `adb pair <ip>:<port>` → enter the code → `adb
   connect <ip>:<port>`.
3. `adb devices` should show the phone. `adb install -r
   app-debug.apk` to install the SumatraPDF build.

That gets you running in 30 seconds without touching any driver.
You can come back to the USB story when you have time, since the
APK installs fine over wireless.

If the phone is on Android 10 or earlier (wireless ADB is 11+), or
you want the proper USB flow because you want to live-debug, then
go §3.1: download Samsung's driver, reboot, plug in, switch USB
mode on the phone, and you should be up.
