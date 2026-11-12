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
