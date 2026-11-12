# Work log

## 2026-11-12: Reminders, freeze fix, resume after restart (issue #8)

Request (relayed from the user): reminders that fire once per trigger; fix the app freezing; start again by itself after the phone restarts. Do not push to main until the user says so.

### 1. Freeze ("แอปค้าง"): causes found in the code
1. **"คุณอยู่ที่ไหน?" floating window blocked the whole phone.** `WherePopup` added its window with `FLAG_DIM_BEHIND` and without `FLAG_NOT_TOUCH_MODAL`, so it took every touch on the screen, not only those on the box. Until the question was answered, nothing else on the phone could be tapped, which looks like a freeze.
   Fix: `FLAG_NOT_TOUCH_MODAL`, no dimming. Taps outside the box now reach the app underneath.
2. **Password check froze the screen.** `unlock` and `changePassword` ran PBKDF2 (120,000 rounds) inside a synchronous JavaScript bridge call, which blocks the page until it returns.
   Fix: `unlockAsync` / `changePasswordAsync` run it on a background thread and answer with an event.
3. **A new SQLite helper was opened on every GPS fix and never closed.** `new PlacesDb(...)` appeared in 5 places, including twice per fix. Each helper holds its own connection; connections piled up, and a write from one could wait on the lock of another (one of them on the main thread).
   Fix: a single shared helper, `PlacesDb.get(context)`.

Not confirmed on a phone: there are no crash or ANR logs from the device in this session. If the app still freezes, a screen recording or the time it happened would help narrow it down.

### 2. Reminders (issue #8)
- Button 5 "การเตือน": add, switch on or off, delete; "ลองเสียงและกล่องเตือน" plays a test alert.
- **Time:** fires at HH:MM, then again every time that time comes round (every day). Exact alarm per reminder (`Reminders`), set again after a restart, an update, a clock change, and whenever the app opens.
- **Place:** chosen from saved places. Fires once on entering the radius of any saved point with that name; re-arms only after leaving, so it fires again on the next entry. Checked on each GPS fix, so it needs recording on and responds at the recording interval.
- **Alert:** a box in the middle of the screen (over any app, with "display over other apps"; it does not block the phone), a short synthesised two-strike bicycle bell, and a notification as backup.
- **Storage:** new `reminders` table in `places.db` (database version 2).
- **Waiting for the user:** whether places can also be a new pin dropped on the spot, or only saved places. Only saved places are built.

### 3. Start again after a restart
- `BootReceiver` now also handles `QUICKBOOT_POWERON` (some brands) and `MY_PACKAGE_REPLACED`, which covers updates through the in-app button: those used to stop recording silently.
- Time reminders are set again every time.
- Recording that was on resumes by itself only when location is allowed "all the time" (`ACCESS_BACKGROUND_LOCATION`), because Android does not let an app start GPS in the background otherwise. Without it, a notification asks the person to tap and start again. Settings shows which case applies and opens the app's permission page.
- Some brands (TECNO among them) also need "auto-start" switched on for the app in the phone's settings; the app cannot change that.

### Checks
- Page tested in a browser at 400 px with a mock bridge: adding, switching off and deleting time and place reminders, the test alert, and wrong then right password through the new async unlock. No errors, no sideways scrolling.
- Java compiles only in CI (no Android SDK in this session). Not yet run on a phone.

## 2026-11-12 (later): correction from the user, pins, always running

The user clarified, through the Manager session:
- "ค้าง" did not mean the app freezes. It meant the app should **keep running all the time** and start again by itself after the phone restarts. Section 1 above was a misreading of the request; those three fixes stay because each is a real defect in the code (the overlay blocked every touch on the screen, the password check blocked the page, database connections leaked), but they were not what the user asked for.
- Place reminders: **a saved place or a new pin**.
- A "เตือนความจำ" button: write the message, then choose the trigger, time or place.

### Changes
- Button 5 renamed "เตือนความจำ".
- **New pin:** when "ถึงสถานที่" is chosen, pick "สถานที่ที่บันทึกไว้" or "ปักหมุดใหม่". A pin is placed by tapping a map (OpenStreetMap tiles, needs internet) or with "ใช้ตำแหน่งปัจจุบัน", with its own name and radius (default 150 m, minimum 20 m). Stored on the reminder (`pin_lat`, `pin_lng`, `pin_radius`, database version 3); it does not add a saved place.
- The map library (Leaflet 1.9.4, BSD-2-Clause, licence in `assets/www/leaflet/LICENSE`) is bundled in the app, so only the map tiles need the internet.
- **Keep running:**
  - the tracking service is now `START_STICKY`, so Android starts it again if it stops it to free memory while recording is on;
  - Settings has "ให้แอปทำงานตลอด", which asks Android to exempt the app from battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); without it, Android may stop the app in the background;
  - together with the restart handling in section 3.

### Checks
- Browser test with a mock bridge: pin from the current position, pin label and radius, the map drawing the pin and its radius, the reminder list showing pins, and the fallback text when the map cannot load. No errors, no sideways scrolling.
- Could not load real map tiles here: this session's network blocks tile.openstreetmap.org. On the phone they load over its own connection.
