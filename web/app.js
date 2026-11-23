"use strict";
/* Klien web NetQuiz. TIDAK ada soket di sini: semua aksi dikirim ke NetQuizWebBridge (HTTP),
   dan peristiwa dari server tiba lewat Server-Sent Events. UI tidak pernah memblokir. */

const CATEGORIES = ["Acak (Semua Kategori)","General Knowledge","Books","Film","Music","Musicals & Theatres",
  "Television","Video Games","Board Games","Science & Nature","Computers","Mathematics","Mythology","Sports",
  "Geography","History","Politics","Art","Celebrities","Animals","Vehicles","Comics","Gadgets",
  "Anime & Manga","Cartoon & Animations"];
const DIFFS = [["any","Acak (Semua Tingkat)"],["easy","Mudah"],["medium","Sedang"],["hard","Sulit"]];
const ROUND_SECONDS = 20;
const SCORE_RE = /(\d+)\.\s+(\S+):\s+(-?\d+)\s+poin(\s+\(online\))?/g;
const COLORS = { good:"var(--good)", bad:"var(--bad)", warn:"var(--warn)", muted:"var(--muted)", pink:"var(--pink)", text:"var(--text)", accent:"#818CF8" };

const $ = id => document.getElementById(id);
let sid = null, myName = "", roundActive = false, udpOk = false, es = null;
let inLobby = false, lobbyMembers = [];

// ------------------------------------------------------------------ util
function api(path, body) {
  return fetch(path + (sid ? "?sid=" + sid : ""), {
    method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body || {})
  }).then(r => r.json());
}
/** Kirim perintah protokol, kembalikan string balasan (null bila gagal/timeout). */
function req(cmd) {
  return api("/api/request", {cmd}).then(j => j.reply || null).catch(() => null);
}
const stripCode = r => (r || "").trim().replace(/^\d{3}\s+/, "").replace(/^(SUCCESS|ERR|SENT|BYE)\s+/, "");
const hhmm = () => new Date().toTimeString().slice(0, 5);
const hhmmss = () => new Date().toTimeString().slice(0, 8);

function setStatus(text, color) { const s = $("status"); s.textContent = text || ""; s.style.color = COLORS[color] || COLORS.muted; }
function setLoginStatus(text, color) { const s = $("loginStatus"); s.textContent = text; s.style.color = COLORS[color] || COLORS.muted; }

function chat(text, color) {
  const log = $("chatLog"), d = document.createElement("div");
  const t = document.createElement("span"); t.className = "t"; t.textContent = hhmm();
  const m = document.createElement("span"); m.textContent = text; m.style.color = COLORS[color] || COLORS.text;
  d.append(t, m); log.append(d); log.scrollTop = log.scrollHeight;
}
function logLine(m) {
  const l = $("logPane"); l.textContent += hhmmss() + "  " + m + "\n"; l.scrollTop = l.scrollHeight;
}

// ------------------------------------------------------------- init UI
CATEGORIES.forEach((n, i) => $("cat").add(new Option(n, i)));
DIFFS.forEach(([v, n]) => $("diff").add(new Option(n, v)));
const categoryId = () => { const i = +$("cat").value; return i === 0 ? 0 : i - 1 + 9; };

const answerBtns = ["A","B","C","D"].map(L => {
  const b = document.createElement("button"); b.dataset.letter = L; b.textContent = "-"; b.disabled = true;
  b.onclick = () => answer(L); $("answers").append(b); return b;
});
document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => {
  document.querySelectorAll(".tabs button").forEach(x => x.classList.toggle("sel", x === b));
  ["scorePane","playersPane","logPane"].forEach(p => $(p).classList.toggle("hidden", p !== b.dataset.tab));
});

function setAnswersEnabled(on) { answerBtns.forEach(b => b.disabled = !(on && b.textContent !== "-")); }
/** Tombol & pilihan mengikuti status lobby/ronde. */
function updateControls() {
  $("soloBtn").disabled = roundActive;
  $("soloBtn").classList.toggle("hidden", inLobby);       // di lobby tidak ada mode solo (soal bisa tertimpa ronde multi)
  $("joinBtn").classList.toggle("hidden", inLobby);
  $("startBtn").classList.toggle("hidden", !inLobby);
  $("leaveBtn").classList.toggle("hidden", !inLobby);
  $("startBtn").disabled = roundActive;
  $("cat").disabled = $("diff").disabled = inLobby && roundActive;
  rebuildTo();
  const bar = $("lobbyBar");
  bar.classList.toggle("hidden", !inLobby);
  if (inLobby) {
    bar.textContent = "";
    const b = document.createElement("b"); b.textContent = "Lobby (" + lobbyMembers.length + "): ";
    bar.append(b, lobbyMembers.map(n => n === myName ? n + " (Anda)" : n).join(", "));
  }
}

// --------------------------------------------------------------- login
$("loginForm").onsubmit = async e => {
  e.preventDefault();
  const host = $("host").value.trim(), name = $("name").value.trim();
  const tcp = parseInt($("tcp").value, 10), udp = parseInt($("udp").value, 10);
  if (isNaN(tcp) || isNaN(udp)) return setLoginStatus("Port harus berupa angka.", "bad");
  if (!host) return setLoginStatus("Alamat server wajib diisi.", "bad");
  if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) return setLoginStatus("Nama harus 3-16 karakter: huruf, angka, atau underscore.", "bad");
  $("loginBtn").disabled = true;
  setLoginStatus("Menghubungkan ke " + host + "...", "muted");
  try {
    const r = await api("/api/connect", {host, tcp, udp, name});
    $("loginBtn").disabled = false;
    if (!r.ok) return setLoginStatus(r.message || r.error || "Gagal terhubung.", "bad");
    sid = r.sid; myName = name; udpOk = r.udpOk;
    enterMain(r.message);
  } catch (err) {
    $("loginBtn").disabled = false;
    setLoginStatus("Bridge tidak dapat dihubungi. Pastikan run-web.sh berjalan.", "bad");
  }
};

function enterMain(msg) {
  $("player").textContent = myName;
  resetQuiz();
  $("chatLog").textContent = ""; $("logPane").textContent = "";
  chat("Terhubung sebagai " + myName + ". " + msg, "muted");
  if (!udpOk) chat("UDP tidak aktif: buzzer & timer tidak tersedia.", "warn");
  $("login").classList.add("hidden"); $("main").classList.remove("hidden");
  openEvents();
  refreshLists();
}

function openEvents() {
  if (es) es.close();
  es = new EventSource("/api/events?sid=" + sid);
  es.onmessage = ev => handleEvent(JSON.parse(ev.data));
  es.onerror = () => { if (sid) { es.close(); backToLogin("Koneksi ke bridge terputus.", "bad"); } };
}

function backToLogin(msg, color) {
  if (es) { es.close(); es = null; }
  sid = null; roundActive = false; inLobby = false; lobbyMembers = []; updateControls();
  setLoginStatus(msg, color);
  $("main").classList.add("hidden"); $("login").classList.remove("hidden");
}

$("logoutBtn").onclick = () => {
  const s = sid; if (!s) return;
  fetch("/api/disconnect?sid=" + s, {method: "POST", body: "{}"}).catch(() => {});
  backToLogin("Anda telah keluar.", "muted");
};
window.addEventListener("pagehide", () => { if (sid) navigator.sendBeacon("/api/disconnect?sid=" + sid, "{}"); });

// ---------------------------------------------------- aksi kuis
$("soloBtn").onclick = async () => {
  $("soloBtn").disabled = true;
  const r = await req("REQ_QUESTION " + categoryId() + " " + $("diff").value);
  updateControls();
  if (r && r.startsWith("200 QUESTION")) showQuestion(r.substring(12).trim(), false);
  else setStatus(r ? stripCode(r) : "Server tidak merespons.", "bad");
};
$("startBtn").onclick = async () => {
  const r = await req("START");
  if (!r || !r.startsWith("200")) {
    const msg = r ? stripCode(r) : "Server tidak merespons.";
    setStatus(msg, "warn"); chat(msg, "warn");               // mis. hanya satu pemain di lobby
  }
  // soal tiba lewat PUSH QUESTION
};

// ----------------------------------------------------------- lobby
const catIndexOf = id => id === 0 ? 0 : id - 9 + 1;
/** Terapkan pengaturan lobby dari server ke pilihan di layar (tanpa memicu event change). */
function applyLobbyConfig(cat, diff) {
  const ci = catIndexOf(parseInt(cat, 10));
  if (ci >= 0 && ci < CATEGORIES.length) $("cat").value = ci;
  if (DIFFS.some(d => d[0] === diff)) $("diff").value = diff;
}
function applyLobbyState(membersCsv, cat, diff) {
  lobbyMembers = membersCsv ? membersCsv.split(",").filter(Boolean) : [];
  applyLobbyConfig(cat, diff);
  updateControls();
}
$("joinBtn").onclick = async () => {
  $("joinBtn").disabled = true;
  const r = await req("JOIN_LOBBY");
  $("joinBtn").disabled = false;
  if (!r || !r.startsWith("200 LOBBY")) return setStatus(r ? stripCode(r) : "Server tidak merespons.", "bad");
  const [members, cat, diff] = r.substring(9).split("||").map(x => x.trim());
  const localCat = categoryId(), localDiff = $("diff").value;
  inLobby = true;
  if (members.split(",").filter(Boolean).length <= 1) {   // pembuat lobby: pilihan lokalnya jadi pengaturan awal
    lobbyMembers = [myName]; updateControls();
    await req("LOBBY_SET " + localCat + " " + localDiff);
  } else {
    applyLobbyState(members, cat, diff);                    // bergabung: ikuti pengaturan lobby
  }
  clearQuestion();                                          // soal solo yang tersisa dibersihkan
  lobbyReadyStatus();
  refreshLists();
  chat("Anda masuk lobby multiplayer.", "muted");
};
$("leaveBtn").onclick = async () => {
  const r = await req("LEAVE_LOBBY");
  if (!r || !r.startsWith("200")) return setStatus(r ? stripCode(r) : "Server tidak merespons.", "bad");
  inLobby = false; lobbyMembers = []; roundActive = false;
  setAnswersEnabled(false); $("buzzBtn").disabled = true; setTimer(0, "Waktu: -");
  updateControls();
  clearQuestion();
  $("meta").textContent = "Belum ada soal. Pilih Soal Solo atau masuk Lobby Multiplayer.";
  setStatus("Anda keluar dari lobby.", "muted");
  chat("Anda keluar dari lobby.", "muted");
  refreshLists();
};
/** Pesan status lobby mengikuti jumlah anggota (dipanggil tiap isi lobby berubah). */
function lobbyReadyStatus() {
  if (!inLobby || roundActive) return;
  if (lobbyMembers.length < 2) setStatus("Menunggu pemain lain (minimal 2 pemain untuk memulai).", "warn");
  else setStatus("Lobby siap (" + lobbyMembers.length + " pemain). Tekan Mulai Ronde Multi.", "good");
}
/** Saat di lobby, mengubah kategori/kesulitan berlaku untuk semua anggota. */
async function shareLobbyConfig() {
  if (!inLobby) return;
  const r = await req("LOBBY_SET " + categoryId() + " " + $("diff").value);
  if (!r || !r.startsWith("200")) setStatus(r ? stripCode(r) : "Server tidak merespons.", "bad");
}
$("cat").onchange = shareLobbyConfig;
$("diff").onchange = shareLobbyConfig;
$("buzzBtn").onclick = async () => {
  $("buzzBtn").disabled = true;
  const r = await api("/api/buzz").then(j => j.reply || null).catch(() => null);
  switch (r) {
    case null: setStatus("Server tidak membalas BUZZ (UDP).", "bad"); $("buzzBtn").disabled = !roundActive; break;
    case "BUZZ_WIN": setStatus("Anda paling cepat! Jawab sekarang.", "good"); setAnswersEnabled(true); break;
    case "BUZZ_LATE": setStatus("Terlambat: pemain lain lebih dulu.", "warn"); $("buzzBtn").disabled = !roundActive; break;
    case "BUZZ_LOCKED": setStatus("Anda sudah salah di ronde ini.", "bad"); break;
    case "BUZZ_NOROUND": setStatus("Tidak ada ronde aktif. Tekan Mulai Ronde Multi.", "warn"); break;
    case "BUZZ_NOLOBBY": setStatus("Anda tidak berada di lobby ronde ini.", "bad"); break;
    default: setStatus("UDP: " + r, "bad"); $("buzzBtn").disabled = !roundActive;
  }
};
async function answer(letter) {
  setAnswersEnabled(false);
  const r = await req("ANSWER " + letter);
  if (!r) return setStatus("Server tidak merespons.", "bad");
  const msg = stripCode(r).replace(/^(CORRECT|WRONG)\s+/, "");
  if (r.startsWith("200 CORRECT")) setStatus(msg, "good");
  else if (r.startsWith("200 WRONG")) setStatus(msg, "bad");
  else setStatus(msg, "warn");
  refreshLists();
}

// -------------------------------------------------------------- chat
$("chatForm").onsubmit = async e => {
  e.preventDefault();
  const msg = $("chatInput").value.trim(); if (!msg) return;
  const to = $("to").value, toLobby = to === LOBBY_OPT, priv = to !== "Semua" && !toLobby;
  $("chatInput").value = "";
  const r = await req(toLobby ? "LOBBY_MSG " + msg : priv ? "MSG " + to + " " + msg : "BROADCAST " + msg);
  if (r && r.startsWith("200")) chat(toLobby ? "[Anda -> Lobby] " + msg : priv ? "[Anda -> " + to + "] " + msg : "[Anda] " + msg, toLobby ? "good" : priv ? "pink" : "accent");
  else chat(r ? stripCode(r) : "Pesan gagal terkirim.", "bad");
};

// ------------------------------------------------------ sertifikat PDF
$("certBtn").onclick = async () => {
  const b = $("certBtn"); b.disabled = true; b.textContent = "Mengunduh...";
  try {
    const res = await fetch("/api/cert?sid=" + sid);
    if (!res.ok) { const j = await res.json().catch(() => ({})); throw new Error(j.error || "Unduhan gagal."); }
    const cd = res.headers.get("Content-Disposition") || "";
    const name = (/filename="([^"]+)"/.exec(cd) || [])[1] || "sertifikat.pdf";
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a"); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    chat("Sertifikat diunduh: " + name, "good");
  } catch (err) { chat(err.message, "bad"); }
  b.disabled = false; b.textContent = "Unduh Sertifikat PDF";
};

// ----------------------------------------------------- daftar & skor
async function refreshLists() {
  const [sc, li, me] = await Promise.all([req("SCOREBOARD"), req("LIST"), req("MYSCORE")]);
  if (sc && sc.startsWith("200 SCORES")) updateScores(sc.substring(10));
  if (li && li.startsWith("200 USERS")) updatePlayers(li);
  if (me && me.startsWith("200 SOLO")) $("soloScore").textContent = me.substring(8).trim();
}
function updateScores(text) {
  const body = $("scoreBody"); body.textContent = "";
  for (const m of text.matchAll(SCORE_RE)) {
    const tr = document.createElement("tr"); if (m[2] === myName) tr.className = "me";
    [m[1], m[2], m[3], m[4] ? "online" : "offline"].forEach((v, i) => {
      const td = document.createElement("td"); td.textContent = v;
      if (i === 2) td.className = "num"; if (i === 3 && !m[4]) td.className = "off"; tr.append(td);
    });
    body.append(tr);
  }
}
function updatePlayers(reply) {
  const ul = $("players"); ul.textContent = "";
  const i = reply.indexOf("online:"), list = i < 0 ? "" : reply.substring(i + 7).trim();
  const names = [];
  if (list) list.split(/,\s*/).forEach(s => {
    const n = s.replace(/\(.*$/, ""), li = document.createElement("li");
    li.append(n === myName ? n + " (Anda)" : n);
    if (s.includes("(lobby)")) {
      const b = document.createElement("span"); b.className = "badge"; b.textContent = "Lobby"; li.append(b);
    }
    if (n !== myName) { li.onclick = () => { $("to").value = n; $("chatInput").focus(); }; names.push(n); }
    ul.append(li);
  });
  rebuildTo(names);
}
const LOBBY_OPT = "__lobby__";
let lastNames = [];
/** Isi pilihan penerima: Semua, [Lobby saja - hanya bila di lobby], lalu tiap pemain. */
function rebuildTo(names) {
  if (names) lastNames = names;
  const to = $("to"), sel = to.value; to.textContent = "";
  to.add(new Option("Semua", "Semua"));
  if (inLobby) to.add(new Option("Lobby saja", LOBBY_OPT));
  lastNames.forEach(n => to.add(new Option(n, n)));
  if ([...to.options].some(o => o.value === sel)) to.value = sel;
}

// ------------------------------------------------------------- soal
function clearQuestion() {
  $("question").textContent = "";
  $("meta").textContent = "Lobby multiplayer: tekan Mulai Ronde Multi untuk memulai.";
  answerBtns.forEach(b => { b.textContent = "-"; b.disabled = true; b.style.display = ""; });
}
function resetQuiz() {
  roundActive = false; inLobby = false; lobbyMembers = []; updateControls();
  $("question").textContent = "";
  $("meta").textContent = "Belum ada soal. Pilih Soal Solo atau masuk Lobby Multiplayer.";
  answerBtns.forEach(b => { b.textContent = "-"; b.disabled = true; b.style.display = ""; });
  $("buzzBtn").disabled = true;
  setTimer(0, "Waktu: -");
  setStatus("", "muted");
  $("scoreBody").textContent = ""; $("players").textContent = ""; $("soloScore").textContent = "0";
  lastNames = []; rebuildTo();
}
function setTimer(sec, label) {
  $("timerFill").style.width = Math.max(0, Math.min(100, sec / ROUND_SECONDS * 100)) + "%";
  $("timerFill").style.background = sec <= 5 && sec > 0 ? "var(--bad)" : "var(--accent)";
  $("timerLbl").textContent = label;
}
/** body: "[kategori / tingkat] teks || A. x || B. y || (petunjuk)" */
function showQuestion(body, multi) {
  const parts = body.split(/\s*\|\|\s*/);
  let head = parts[0], meta = "";
  const m = /^\[(.*?)\]\s*([\s\S]*)$/.exec(head);
  if (m) { meta = m[1]; head = m[2]; }
  const opts = parts.slice(1).filter(p => /^[A-D]\.\s/.test(p));
  $("meta").textContent = (multi ? "Ronde multiplayer" : "Mode solo") + (meta ? "  |  " + meta : "");
  $("question").textContent = head;
  answerBtns.forEach((b, i) => { b.textContent = i < opts.length ? opts[i] : "-"; b.style.display = (i < opts.length || i < 2) ? "" : "none"; });
  if (multi) {
    roundActive = true; updateControls(); setAnswersEnabled(false);
    $("buzzBtn").disabled = !udpOk;
    setTimer(ROUND_SECONDS, "Waktu: " + ROUND_SECONDS + " dtk");
    setStatus(udpOk ? "Tekan BUZZ! dulu, lalu jawab." : "UDP nonaktif: tidak bisa menekan buzzer.", udpOk ? "warn" : "bad");
    chat("Soal ronde baru dimulai.", "muted");
  } else {
    roundActive = false; updateControls();
    $("buzzBtn").disabled = true; setTimer(0, "Waktu: -");
    setAnswersEnabled(true); setStatus("Pilih jawaban Anda.", "muted");
  }
}

// ------------------------------------------- event dari server (SSE)
function handleEvent(ev) {
  switch (ev.t) {
    case "log": logLine(ev.m); break;
    case "tick": setTimer(ev.s, "Waktu: " + Math.max(0, ev.s) + " dtk"); break;
    case "disc": backToLogin(ev.reason, "bad"); break;
    case "push": handlePush(ev.type, ev.parts); break;
  }
}
function handlePush(type, p) {
  switch (type) {
    case "JOIN": case "LEAVE": chat(p[0], "muted"); refreshLists(); break;
    case "LOBBY":                                   // [anggota, kategori, kesulitan, pesan]
      if (!inLobby) break;
      applyLobbyState(p[0], p[1], p[2]);
      if (p[3]) chat(p[3], "muted");
      lobbyReadyStatus();
      refreshLists();
      break;
    case "LOBBYINFO": refreshLists(); break;                // isi lobby berubah (untuk pemain di luar lobby)
    case "LOBBYCHAT": chat("[Lobby] " + p[0], "good"); break;
    case "BROADCAST": chat(p[0], "text"); break;
    case "PRIVATE": chat("[Privat] " + p[0], "pink"); break;
    case "QUESTION": showQuestion(p.join(" || "), true); break;
    case "BUZZER": {
      const who = p[0].split(/\s+/)[0];
      setStatus(p[0], who === myName ? "good" : "warn");
      if (who === myName) setAnswersEnabled(true); else { setAnswersEnabled(false); $("buzzBtn").disabled = true; }
      break;
    }
    case "BUZZER_OPEN": {
      const who = p[0].split(/\s+/)[0];
      setStatus(p[0], "warn");
      const lockedMe = who === myName && p[0].includes("salah");
      setAnswersEnabled(false);
      $("buzzBtn").disabled = !(roundActive && !lockedMe && udpOk);
      if (who === myName) refreshLists();
      break;
    }
    case "ROUND_END":
      roundActive = false; updateControls(); setAnswersEnabled(false); $("buzzBtn").disabled = true; setTimer(0, "Waktu: -");
      setStatus("Ronde selesai: " + p[0], "warn"); chat("Ronde selesai: " + p[0], "warn");
      if (p.length > 1) updateScores(p.slice(1).join(" || "));
      refreshLists();
      break;
    default: chat(type + " " + p[0], "muted");
  }
}