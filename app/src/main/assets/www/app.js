(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const A = window.Android || null;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const pad = n => String(n).padStart(2, "0");
  const fmt = ms => { const d = new Date(ms); return `${pad(d.getDate())}-${pad(d.getMonth() + 1)}-${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const hm = ms => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const intervalLabel = m => m < 60 ? `${m} นาที` : `${m / 60} ชั่วโมง`;
  const MONTHS = ["มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน", "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"];

  const handlers = {};
  // Show any unexpected error instead of failing silently.
  window.addEventListener("error", e => { try { toast("เกิดข้อผิดพลาด: " + (e.message || e)); } catch {} });
  window.__native = (ev, payload) => { try { handlers[ev] && handlers[ev](payload); } catch (e) { console.error(e); } };

  let state = { recording: false, count: 0, checkEvery: 6, intervalMin: 10, radiusM: 100, lastAt: 0, places: 0, permission: true };
  let toastTimer;
  function toast(msg) { $("toast").textContent = msg; $("toast").hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $("toast").hidden = true, 3500); }

  // ---------- status ----------
  function render() {
    const s = state, on = !!s.recording;
    $("statusCard").classList.toggle("on", on);
    $("stateText").textContent = on ? "กำลังบันทึก" : "หยุดบันทึก";
    $("intervalText").textContent = `จับพิกัดทุก ${intervalLabel(s.intervalMin)}`;
    $("countText").textContent = `${s.count}/${s.checkEvery}`;
    $("meter").style.width = `${Math.min(100, (s.count / Math.max(1, s.checkEvery)) * 100)}%`;
    $("lastPos").textContent = s.lastAt ? `${(+s.lastLat).toFixed(5)}, ${(+s.lastLng).toFixed(5)}` : "-";
    $("lastAt").textContent = s.lastAt ? fmt(s.lastAt) : "-";
    $("lastPlace").textContent = s.lastPlace || "-";
    $("nearText").textContent = s.nearName && s.nearM >= 0 ? `ใกล้ ${s.nearName} ${Number(s.nearM).toLocaleString("th-TH")} เมตร` : "-";
    $("nextAt").textContent = on && s.nextAt ? hm(s.nextAt) : on ? "กำลังจับพิกัด…" : "-";
    $("errText").hidden = !s.lastError; $("errText").textContent = s.lastError || "";
    $("placesText").textContent = `สถานที่ที่จำไว้แล้ว ${s.places || 0} แห่ง` + (s.battery >= 0 ? ` · แบตตอนบันทึกล่าสุด ${s.battery}%` : "");
    $("b1").classList.toggle("primary", !on); $("b1").classList.toggle("stop", on);
    $("b1t").textContent = on ? "หยุดบันทึก" : "เริ่มบันทึก";
    $("b1d").textContent = on ? "หยุดจับพิกัดและการทำงานเบื้องหลัง" : "เริ่มจับพิกัดตามความถี่ที่ตั้งไว้";
    $("permCard").hidden = s.permission !== false || on;
    const needPopup = s.overlay === false, needNotif = s.notifications === false;
    $("popupCard").hidden = !(needPopup || needNotif);
    $("popupAllow").hidden = !needPopup; $("notifAllow").hidden = !needNotif;
    $("popupText").textContent = needNotif && !needPopup ? "การแจ้งเตือนของแอปถูกปิดอยู่ ถ้าแอปอยู่เบื้องหลังจะไม่รู้ว่ามีคำถาม \"คุณอยู่ที่ไหน?\""
      : "ให้แอปเด้งหน้าต่างถามชื่อสถานที่ขึ้นกลางจอได้ แม้กำลังใช้แอปอื่นอยู่ ไม่อย่างนั้นจะมีแค่การแจ้งเตือนเล็ก ๆ ด้านบน";
    $("popupSetting").textContent = (s.overlay ? "เด้งหน้าต่างกลางจอได้แล้ว" : "ยังไม่ได้อนุญาตให้แสดงทับแอปอื่น จะเห็นแค่การแจ้งเตือน")
      + (s.notifications === false ? " · การแจ้งเตือนถูกปิดอยู่" : "");
    if (!$("mAsk").hidden && !s.pending) $("mAsk").hidden = true;
  }
  handlers.state = s => {
    if (!s) return;
    if (s.locked) { if (handlers.lock) handlers.lock({ enabled: true, locked: true }); return; }
    state = { ...state, ...s }; render();
  };
  handlers.warn = msg => toast(msg);
  handlers.needPermission = () => { state.permission = false; render(); };
  function pullState() { if (A) { try { handlers.state(JSON.parse(A.state())); } catch {} } }

  // ---------- views (buttons 3 and 4) ----------
  const VIEWS = ["vFiles", "vFile", "vSettings"];
  function showView(id) {
    VIEWS.forEach(v => $(v).hidden = v !== id);
    document.body.classList.toggle("sheet-open", !!id);
    $("b3").setAttribute("aria-current", id === "vFiles" || id === "vFile" ? "page" : "false");
    $("b4").setAttribute("aria-current", id === "vSettings" ? "page" : "false");
    if (id) setTimeout(() => $(id).scrollIntoView({ behavior: "smooth", block: "start" }), 30);
  }

  // ---------- button 1 ----------
  $("b1").addEventListener("click", () => {
    if (!A) { toast("ใช้ได้เฉพาะในแอปบนมือถือ"); return; }
    A.toggle();
  });
  $("openSettings").addEventListener("click", () => A && A.openAppSettings());
  ["popupAllow", "popupAllow2"].forEach(id => $(id).addEventListener("click", () => A && A.openOverlaySettings && A.openOverlaySettings()));
  ["notifAllow", "notifAllow2"].forEach(id => $(id).addEventListener("click", () => A && A.openNotificationSettings && A.openNotificationSettings()));

  // ---------- button 2 ----------
  $("b2").addEventListener("click", () => {
    if (!state.recording) { toast("ไม่ได้เริ่มบันทึกการเดินทาง"); return; }
    $("placeName").value = ""; $("placeStatus").textContent = ""; $("placeSave").disabled = false;
    $("mPlace").hidden = false; setTimeout(() => $("placeName").focus(), 50);
  });
  $("placeSave").addEventListener("click", () => {
    const name = $("placeName").value.trim();
    if (!name) { $("placeStatus").textContent = "พิมพ์ชื่อสถานที่ก่อน"; return; }
    if (!A) { $("placeStatus").textContent = "ใช้ได้เฉพาะในแอปบนมือถือ"; return; }
    $("placeSave").disabled = true; $("placeStatus").textContent = "กำลังจับพิกัด… (อาจใช้เวลาถึง 45 วินาที)";
    A.savePlace(name);
  });
  handlers.placeSaved = r => {
    $("placeSave").disabled = false;
    if (r && r.ok) { $("mPlace").hidden = true; toast(`บันทึก "${r.name}" แล้ว`); }
    else { $("placeStatus").textContent = (r && r.error) || "บันทึกไม่สำเร็จ"; if (r && r.error === "ไม่ได้เริ่มบันทึกการเดินทาง") { $("mPlace").hidden = true; toast(r.error); } }
  };
  document.querySelectorAll("[data-close]").forEach(b => b.addEventListener("click", () => $(b.dataset.close).hidden = true));

  // ---------- automatic check: "คุณอยู่ที่ไหน?" ----------
  handlers.ask = p => {
    if (!p) return;
    $("askWhere").textContent = `ยังไม่มีสถานที่ที่บันทึกไว้ใกล้จุดนี้ (${(+p.lat).toFixed(5)}, ${(+p.lng).toFixed(5)} เวลา ${hm(p.at)})`;
    if ($("mAsk").hidden) { $("askName").value = ""; $("mAsk").hidden = false; setTimeout(() => $("askName").focus(), 50); }
  };
  $("askSave").addEventListener("click", () => {
    const name = $("askName").value.trim();
    if (!name) { $("askName").focus(); return; }
    $("mAsk").hidden = true; if (A) A.answer(name); toast(`บันทึก "${name}" แล้ว`);
  });
  $("askClose").addEventListener("click", () => {
    $("mAsk").hidden = true; if (A) A.answer(""); toast("บันทึกเฉพาะวันเวลาและพิกัด");
  });
  [["placeName", "placeSave"], ["askName", "askSave"]].forEach(([i, b]) => $(i).addEventListener("keydown", e => { if (e.key === "Enter" && !e.isComposing) $(b).click(); }));

  // ---------- button 3: files ----------
  let files = [], current = null;
  $("b3").addEventListener("click", () => { showView("vFiles"); renderFolderHint(); loadFiles(); });
  $("refreshFiles").addEventListener("click", loadFiles);
  let filesTimer;
  function loadFiles() {
    $("fileTree").innerHTML = '<p class="small muted">กำลังโหลด…</p>';
    if (!A) { handlers.files([]); return; }
    clearTimeout(filesTimer);
    filesTimer = setTimeout(() => { $("fileTree").innerHTML = '<div class="empty">โหลดรายการไฟล์ไม่สำเร็จ กด "โหลดใหม่" อีกครั้ง</div>'; }, 10000);
    try { A.listFiles(); } catch (e) { clearTimeout(filesTimer); $("fileTree").innerHTML = `<div class="empty">เปิดรายการไฟล์ไม่ได้: ${esc(e.message)}</div>`; }
  }
  function renderFolderHint() {
    const has = A && A.hasFolder ? A.hasFolder() : false;
    $("folderHint").hidden = has;
  }
  $("pickFolder").addEventListener("click", () => { if (A && A.pickFolder) A.pickFolder(); });
  handlers.folderPicked = () => { toast("แสดงไฟล์ทั้งหมดในโฟลเดอร์แล้ว"); renderFolderHint(); loadFiles(); };
  handlers.filesError = msg => { clearTimeout(filesTimer); $("fileTree").innerHTML = `<div class="empty">อ่านรายการไฟล์ไม่ได้: ${esc(msg)}</div>`; };
  handlers.files = list => {
    clearTimeout(filesTimer);
    // One entry per day: an earlier install can leave a day split over "…-track.csv", "…-track (1).csv", …
    const byDay = {};
    for (const f of (Array.isArray(list) ? list : [])) {
      const day = String(f.name).slice(0, 10);
      const e = byDay[day] || (byDay[day] = { id: "", ids: [], name: day + "-track.csv", folder: f.folder, size: 0 });
      e.ids.push(f.id); e.size += +f.size || 0;
    }
    files = Object.values(byDay).map(e => { e.ids.sort((x, y) => String(x).localeCompare(String(y), undefined, { numeric: true })); e.id = e.ids.join("|"); return e; })
      .sort((x, y) => y.name.localeCompare(x.name));
    if (!files.length) { $("fileTree").innerHTML = '<div class="empty">ยังไม่มีข้อมูลการเดินทาง</div>'; return; }
    const groups = {};
    for (const f of files) {
      const m = String(f.folder).match(/GPS-Track\/(\d{4})\/(\d{2})\//);
      const key = m ? `${m[1]}-${m[2]}` : "อื่น ๆ";
      (groups[key] = groups[key] || []).push(f);
    }
    $("fileTree").innerHTML = Object.keys(groups).sort().reverse().map(k => {
      const [y, mo] = k.split("-");
      const title = mo ? `${MONTHS[+mo - 1]} ${y}` : k;
      return `<div class="month"><h3>${esc(title)} · ${groups[k].length} วัน</h3><div class="files">${groups[k].map(f =>
        `<button type="button" data-file="${esc(f.id)}"><span><b>${esc(f.name)}</b><br><span class="p">${esc(f.folder)}</span></span><span class="small muted">${Math.max(1, Math.round(f.size / 1024))} KB</span></button>`).join("")}</div></div>`;
    }).join("");
  };
  $("fileTree").addEventListener("click", e => {
    const b = e.target.closest("[data-file]"); if (!b) return;
    current = files.find(f => String(f.id) === b.dataset.file);
    if (!current) return;
    $("fileName").textContent = current.name; $("fileFolder").textContent = current.folder;
    $("fileSummary").textContent = "กำลังเปิดไฟล์…"; $("fileRows").innerHTML = "";
    showView("vFile");
    A.readFile(String(current.id));
  });
  handlers.file = r => {
    if (!current || String(r.id) !== String(current.id)) return;
    if (r.error) { $("fileSummary").textContent = "เปิดไฟล์ไม่ได้: " + r.error; return; }
    // Rows from split files, oldest first by their dd-MM-yyyy HH:mm:ss time.
    const key = t => { const m = String(t || "").match(/^(\d{2})-(\d{2})-(\d{4}) (\d{2}:\d{2}:\d{2})/); return m ? `${m[3]}${m[2]}${m[1]}${m[4]}` : String(t); };
    const rows = (r.rows || []).slice().sort((x, y) => key(x[0]).localeCompare(key(y[0])));
    const named = rows.filter(x => (x[3] || "").trim()).length;
    $("fileSummary").textContent = rows.length ? `บันทึก ${rows.length} ครั้ง · มีชื่อสถานที่ ${named} ครั้ง` : "ไฟล์นี้ยังไม่มีข้อมูล";
    // Date above time, so the whole row fits a phone screen without scrolling sideways.
    const when = v => { const [d, t] = String(v || "").split(" "); return t ? `<span class="t">${esc(t)}</span><span class="dd">${esc(d)}</span>` : esc(v); };
    $("fileRows").innerHTML = rows.map(x => `<tr><td class="num when">${when(x[0])}</td><td class="num pos"><span>${esc(x[1])}</span><span>${esc(x[2])}</span></td><td class="place">${esc(x[3] || "")}${x[4] ? `<span class="near">ใกล้ ${esc(x[4])} · ${esc(x[5])} ม.</span>` : ""}${x[6] ? `<span class="near">แบต ${esc(x[6])}%</span>` : ""}</td></tr>`).join("")
      || '<tr><td colspan="3" class="empty">ยังไม่มีข้อมูลการเดินทาง</td></tr>';
  };
  $("backFiles").addEventListener("click", () => showView("vFiles"));
  document.querySelectorAll("[data-sheet-close]").forEach(b => b.addEventListener("click", () => showView(null)));
  $("shareFile").addEventListener("click", () => { if (A && current) A.shareFile(String(current.id), current.name); });

  // ---------- button 4: settings ----------
  $("b4").addEventListener("click", () => {
    $("sInterval").value = String(state.intervalMin); $("sCheck").value = state.checkEvery; $("sRadius").value = state.radiusM;
    updateHint(); $("sStatus").textContent = ""; showView("vSettings");
  });
  function updateHint() {
    const m = +$("sInterval").value * Math.max(1, +$("sCheck").value || 1);
    $("checkHint").textContent = `ถ้าหยุดอยู่ที่ใหม่ที่ยังไม่มีชื่อนานราว ${m < 60 ? m + " นาที" : (m / 60).toFixed(m % 60 ? 1 : 0) + " ชั่วโมง"} จะถามว่า "คุณอยู่ที่ไหน?" ระหว่างเดินทางจะไม่ถาม`;
  }
  ["sInterval", "sCheck"].forEach(id => $(id).addEventListener("input", updateHint));
  $("sSave").addEventListener("click", () => {
    const interval = +$("sInterval").value, check = Math.round(+$("sCheck").value), radius = Math.round(+$("sRadius").value);
    if (!(check >= 1 && check <= 999)) { $("sStatus").textContent = "จำนวนครั้งต้องอยู่ระหว่าง 1 ถึง 999"; return; }
    if (!(radius >= 5 && radius <= 50000)) { $("sStatus").textContent = "รัศมีต้องอยู่ระหว่าง 5 ถึง 50,000 เมตร"; return; }
    if (A) A.setSettings(interval, check, radius);
    state = { ...state, intervalMin: interval, checkEvery: check, radiusM: radius }; render();
    $("sStatus").textContent = state.recording ? "บันทึกแล้ว ใช้ตั้งแต่รอบการบันทึกถัดไป" : "บันทึกแล้ว";
  });

  // ---------- app update (OTA from GitHub Releases) ----------
  let latest = null;
  $("appVer").textContent = A && A.version ? A.version() : "-";
  $("checkUpd").addEventListener("click", () => {
    if (!A) { $("updState").textContent = "ตรวจหาอัปเดตได้เฉพาะในแอปบนมือถือ"; return; }
    $("checkUpd").disabled = true; $("updNow").hidden = true; $("updState").textContent = "กำลังตรวจ…";
    A.checkUpdate();
  });
  handlers.update = u => {
    $("checkUpd").disabled = false;
    if (!u) return;
    $("updBanner").hidden = !(u.available && u.url);
    if (u.available) { $("bannerVer").textContent = u.latest; }
    if (u.error) { $("updState").textContent = "ตรวจหาอัปเดตไม่สำเร็จ: " + u.error; $("updNow").disabled = false; $("updNow").textContent = "อัปเดตเลย"; return; }
    if (u.available) {
      latest = u;
      $("updState").textContent = `มีเวอร์ชันใหม่ ${u.latest}` + (u.notes ? ` · ${String(u.notes).split("\n")[0]}` : "");
      $("updNow").hidden = false; $("updNow").disabled = false; $("updNow").textContent = "อัปเดตเลย";
    } else {
      $("updState").textContent = u.message || "เป็นเวอร์ชันล่าสุดแล้ว";
    }
  };
  function startInstall() {
    if (!A || !latest || !latest.url) return;
    $("updNow").disabled = true; $("updNow").textContent = "กำลังดาวน์โหลด…";
    $("bannerBtn").disabled = true; $("bannerState").textContent = "กำลังดาวน์โหลด…";
    A.installUpdate(latest.url);
  }
  $("updNow").addEventListener("click", startInstall);
  $("bannerBtn").addEventListener("click", startInstall);
  // Check for a new version every time the app opens; Android still asks before installing.
  if (A && A.checkUpdate) A.checkUpdate();
  handlers.updateProgress = pct => {
    $("updNow").textContent = pct < 100 ? `กำลังดาวน์โหลด ${pct}%` : "ติดตั้ง";
    $("bannerState").textContent = pct < 100 ? `กำลังดาวน์โหลด ${pct}%` : "ดาวน์โหลดแล้ว กดติดตั้งในหน้าต่างที่ขึ้นมา";
    if (pct >= 100) $("bannerBtn").disabled = false;
    if (pct >= 100) { $("updNow").disabled = false; $("updState").textContent = "ดาวน์โหลดแล้ว กดติดตั้งในหน้าต่างที่ขึ้นมา"; }
  };

  // ---------- app password ----------
  let lockEnabled = false;
  function renderPw() {
    $("pwState").textContent = lockEnabled ? "ตั้งรหัสผ่านแล้ว แอปจะล็อกทุกครั้งที่ออกจากแอป" : "ยังไม่ได้ตั้งรหัสผ่าน ใครเปิดเครื่องก็เข้าแอปได้";
    $("pwCurField").hidden = !lockEnabled; $("pwRemove").hidden = !lockEnabled;
    $("pwSave").textContent = lockEnabled ? "เปลี่ยนรหัสผ่าน" : "ตั้งรหัสผ่าน";
  }
  handlers.lock = l => {
    lockEnabled = !!(l && l.enabled); renderPw();
    const show = !!(l && l.locked);
    if (show && $("lockScreen").hidden) { $("lockPw").value = ""; $("lockStatus").textContent = ""; }
    $("lockScreen").hidden = !show;
    if (show) { $("mAsk").hidden = true; $("mPlace").hidden = true; setTimeout(() => $("lockPw").focus(), 60); }
    else pullState();
  };
  $("lockBtn").addEventListener("click", () => {
    if (!A) return;
    $("lockBtn").disabled = true; $("lockStatus").textContent = "กำลังตรวจ…";
    setTimeout(() => {
      const err = A.unlock($("lockPw").value);
      $("lockBtn").disabled = false;
      if (err) { $("lockStatus").textContent = err; $("lockPw").select(); return; }
      $("lockScreen").hidden = true; $("lockPw").value = ""; $("lockStatus").textContent = "";
      pullState();
    }, 30);
  });
  $("lockPw").addEventListener("keydown", e => { if (e.key === "Enter" && !e.isComposing) $("lockBtn").click(); });
  function savePw(remove) {
    if (!A) { $("pwStatus").textContent = "ใช้ได้เฉพาะในแอปบนมือถือ"; return; }
    const cur = $("pwCur").value, next = remove ? "" : $("pwNew").value;
    if (!remove) {
      if (next.length < 4) { $("pwStatus").textContent = "รหัสผ่านต้องยาวอย่างน้อย 4 ตัว"; return; }
      if (next !== $("pwNew2").value) { $("pwStatus").textContent = "รหัสผ่านใหม่ 2 ช่องไม่ตรงกัน"; return; }
    }
    $("pwStatus").textContent = "กำลังบันทึก…";
    setTimeout(() => {
      const err = A.changePassword(cur, next);
      if (err) { $("pwStatus").textContent = err; return; }
      lockEnabled = !remove; renderPw();
      ["pwCur", "pwNew", "pwNew2"].forEach(id => $(id).value = "");
      $("pwStatus").textContent = remove ? "เอารหัสผ่านออกแล้ว" : "ตั้งรหัสผ่านแล้ว ครั้งหน้าที่เปิดแอปจะต้องใส่รหัสนี้";
    }, 30);
  }
  $("pwSave").addEventListener("click", () => savePw(false));
  $("pwRemove").addEventListener("click", () => savePw(true));
  if (A && A.lockEnabled) { lockEnabled = A.lockEnabled(); renderPw(); }

  // ---------- clock ----------
  function tick() { $("clock").textContent = hm(Date.now()); }
  tick(); setInterval(tick, 10000);
  setInterval(pullState, 30000);

  pullState(); render();
})();
