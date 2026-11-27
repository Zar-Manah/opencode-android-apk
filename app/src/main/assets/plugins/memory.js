// memory.js — capa cognitiva del arnés: memoria persistente + journal + sueño NREM + tool `memory`.
// Un solo archivo, sin dependencias obligatorias: funciona con node:fs/path.
// Si existen `zod` y `@opencode-ai/plugin` resolubles en el config dir, los usa
// para declarar la tool; si no, registra un descriptor plano equivalente.
const fsMod = "node:fs";
const pathMod = "node:path";
const fs = (typeof require === "function") ? require(fsMod) : await import(fsMod);
const path = (typeof require === "function") ? require(pathMod) : await import(pathMod);

const CANON = "/data/user/0/a.opencode/files/server-home";
function home() {
  const h = process.env.HOME;
  if (h) {
    try {
      if (fs.existsSync(path.join(h, "toolchain", "usr"))) return h;
    } catch {}
  }
  try {
    const fileUrl = typeof import.meta !== "undefined" && import.meta.url ? import.meta.url : null;
    if (fileUrl && fileUrl.startsWith("file://")) {
      const candidate = path.resolve(path.dirname(decodeURIComponent(fileUrl.slice(7))), "../../..");
      if (fs.existsSync(candidate)) return candidate;
    }
  } catch {}
  if (typeof __dirname === "string") {
    try {
      const candidate = path.resolve(__dirname, "../../..");
      if (fs.existsSync(candidate)) return candidate;
    } catch {}
  }
  return CANON;
}
const cfgDir = () => path.join(home(), ".config", "opencode");
const memDir = () => path.join(cfgDir(), "memory");
const memFile = () => path.join(memDir(), "memory.json");
const bakFile = () => path.join(memDir(), "memory.json.bak");
const journalFile = () => path.join(memDir(), "journal.jsonl");
const stateFile = () => path.join(memDir(), "state.json");

// Sueño: consolida la memoria en la ventana nocturna (03:30 locales) y se
// recupera si la app estaba apagada; nunca toca nada sin copia previa.
const DREAM_HOUR = 3;
const DREAM_MINUTE = 30;
const DREAM_CATCHUP_MS = 26 * 3600000;
const DREAM_MIN_KEYS = 5;
const SNAPSHOTS_KEEP = 10;

function readText(f) {
  try { return fs.readFileSync(f, "utf8"); } catch { return null; }
}
function writeAtomic(f, text) {
  fs.mkdirSync(path.dirname(f), { recursive: true });
  const tmp = f + ".tmp-" + process.pid;
  fs.writeFileSync(tmp, text);
  fs.renameSync(tmp, f);
}
function validJson(s) {
  if (typeof s !== "string") return false;
  const t = s.trim();
  if (!t.startsWith("{") || !t.endsWith("}")) return false;
  try { JSON.parse(t); return true; } catch { return false; }
}
function journal(ev, extra) {
  try {
    const line = JSON.stringify(Object.assign({ ts: new Date().toISOString(), ev }, extra || {}));
    let cur = readText(journalFile()) || "";
    cur += line + "\n";
    if (cur.length > 1048576) cur = cur.slice(cur.length - 1048576);
    writeAtomic(journalFile(), cur);
  } catch {}
}
function readState() {
  try { return JSON.parse(readText(stateFile()) || "{}") || {}; } catch { return {}; }
}
function writeState(s) {
  try { writeAtomic(stateFile(), JSON.stringify(s, null, 1) + "\n"); } catch {}
}

// Escritura canónica: memory.json y .bak siempre con la última copia buena.
function writeMemory(text) {
  writeAtomic(memFile(), text);
  try { writeAtomic(bakFile(), text); } catch {}
}

// Auto-reparación al crear instancia: nunca dejar memory.json inválido.
function ensure() {
  try {
    fs.mkdirSync(memDir(), { recursive: true });
    const cur = readText(memFile());
    if (cur === null) return { status: "absent" };
    if (validJson(cur)) {
      try {
        const bak = readText(bakFile());
        if (bak !== cur) writeAtomic(bakFile(), cur);
      } catch {}
      return { status: "ok" };
    }
    try { fs.renameSync(memFile(), path.join(memDir(), "memory.json.corrupt-" + Date.now())); } catch {}
    const bak = readText(bakFile());
    if (validJson(bak)) {
      writeMemory(bak);
      journal("repair", { from: "bak" });
      return { status: "restored-bak" };
    }
    journal("repair", { from: "none-valid" });
    return { status: "invalid-no-backup" };
  } catch (e) {
    return { status: "error", error: String((e && e.message) || e) };
  }
}

// ---- Sueño NREM (consolidación nocturna) ----
function dreamWindow(now) {
  const w = new Date(now);
  w.setHours(DREAM_HOUR, DREAM_MINUTE, 0, 0);
  return w.getTime();
}
function dreamDue(now) {
  const last = Number(readState().lastDream || 0);
  const win = dreamWindow(now);
  if (now >= win && last < win) return true;      // la noche de hoy aún no se ha consolidado
  if (now - last > DREAM_CATCHUP_MS) return true; // se la saltó (app apagada) → se recupera
  return false;
}
function pruneSnapshots() {
  try {
    const dir = memDir();
    const snaps = fs.readdirSync(dir).filter((f) => f.startsWith("memory.json.snap-")).sort();
    for (const f of snaps.slice(0, Math.max(0, snaps.length - SNAPSHOTS_KEEP))) {
      try { fs.unlinkSync(path.join(dir, f)); } catch {}
    }
  } catch {}
}
function runDream(force) {
  ensure();
  const raw = readText(memFile());
  if (!validJson(raw)) return "sueño abortado: memoria inválida";
  const d = JSON.parse(raw);
  const keys = Object.keys(d);
  if (!force && keys.length < DREAM_MIN_KEYS) return null;
  let snap = null;
  try {
    snap = "memory.json.snap-" + Date.now();
    fs.copyFileSync(memFile(), path.join(memDir(), snap));
  } catch { snap = null; }
  const seen = new Map();
  const pruned = [];
  for (const k of keys) {
    let sig;
    try { sig = JSON.stringify(d[k]); } catch { sig = "s:" + String(d[k]); }
    if (seen.has(sig)) { delete d[k]; pruned.push(k); }
    else seen.set(sig, k);
  }
  const alive = Object.keys(d).length;
  writeMemory(JSON.stringify(d, null, 1) + "\n");
  pruneSnapshots();
  const st = readState();
  st.lastDream = Date.now();
  st.dreams = (Number(st.dreams) || 0) + 1;
  writeState(st);
  const note = "Sueño NREM: " + alive + " claves vivas (podadas " + pruned.length + " redundantes).";
  journal("dream", { before: keys.length, alive, pruned, snap, note });
  return note;
}
function checkDream() {
  try {
    if (dreamDue(Date.now())) runDream(false);
  } catch {}
}

async function cmd(action, a) {
  a = a || {};
  const ensureRes = ensure();
  if (action === "get") {
    const d = JSON.parse(readText(memFile()) || "{}");
    const k = a.key;
    if (k === undefined || k === null || k === "") return JSON.stringify(d, null, 1).slice(0, 4000);
    const v = d[String(k)];
    return v === undefined ? "(sin clave " + k + ")" : (typeof v === "string" ? v : JSON.stringify(v, null, 1).slice(0, 4000));
  }
  if (action === "set") {
    const raw = readText(memFile());
    const d = validJson(raw) ? JSON.parse(raw) : {};
    d[String(a.key)] = a.value;
    writeMemory(JSON.stringify(d, null, 1) + "\n");
    journal("set", { key: String(a.key) });
    return "ok";
  }
  if (action === "del") {
    const raw = readText(memFile());
    const d = validJson(raw) ? JSON.parse(raw) : {};
    delete d[String(a.key)];
    writeMemory(JSON.stringify(d, null, 1) + "\n");
    journal("del", { key: String(a.key) });
    return "ok";
  }
  if (action === "list") {
    const d = JSON.parse(readText(memFile()) || "{}");
    return Object.keys(d).join("\n") + "\n(" + Object.keys(d).length + " claves)";
  }
  if (action === "validate") {
    return ensureRes.status === "ok" ? "VALID" : "REPARADO:" + ensureRes.status;
  }
  if (action === "snapshot") {
    const snap = path.join(memDir(), "memory.json.snap-" + Date.now());
    fs.copyFileSync(memFile(), snap);
    return snap;
  }
  if (action === "journal") {
    const all = (readText(journalFile()) || "").split("\n").filter(Boolean);
    return all.slice(-(a.n || 20)).join("\n") || "(vacío)";
  }
  if (action === "dream") {
    const note = runDream(true);
    return note || "sueño: nada que consolidar";
  }
  return "acciones: get|set|del|list|validate|snapshot|journal|dream";
}

const getRequire = () => (typeof require === "function") ? require : null;
let toolFn = null;
try { const r = getRequire(); toolFn = r ? r("@opencode-ai/plugin").tool : null; } catch {}
let zmod = null;
try { const r = getRequire(); zmod = r ? r("zod") : null; } catch {}

function descriptor() {
  const d = {
    description: "Memoria persistente del dispositivo: get/set/del/list/validate/snapshot/journal/dream sobre memory.json (.bak, snapshots y journal; el sueño NREM consolida y poda redundantes, automático ~03:30).",
    execute: async (args) => {
      try {
        const out = await cmd(args && args.action ? args.action : "list", args || {});
        return { output: String(out) };
      } catch (e) {
        return { output: "error: " + String((e && e.message) || e) };
      }
    },
  };
  if (zmod) {
    d.args = {
      action: zmod.string().optional(),
      key: zmod.string().optional(),
      value: zmod.any().optional(),
      n: zmod.number().optional(),
    };
  }
  return toolFn ? toolFn(d) : d;
}

// Journal del ciclo de sesión + sueño en los momentos de reposo.
function onEvent({ event }) {
  try {
    const type = String((event && event.type) || "");
    const props = (event && event.properties) || {};
    const id = props.sessionID || (props.info && props.info.id) || null;
    if (type === "session.idle" || type === "session.error" || type === "session.deleted") {
      journal(type, { id });
    }
    if (type === "session.idle") checkDream();
  } catch {}
}
function onToolAfter(input, output) {
  try {
    journal("tool", { tool: input && input.tool, title: output && output.title });
  } catch {}
}

let dreamTimerArmed = false;
function armDreamClock() {
  if (dreamTimerArmed) return;
  dreamTimerArmed = true;
  try {
    const t = setTimeout(checkDream, 45000);
    if (t && typeof t.unref === "function") t.unref();
    const iv = setInterval(checkDream, 20 * 60 * 1000);
    if (iv && typeof iv.unref === "function") iv.unref();
  } catch {}
}

async function MemoryPlugin() {
  const st = ensure();
  journal("boot", { status: st.status });
  armDreamClock();
  return { tool: { memory: descriptor() }, event: onEvent, "tool.execute.after": onToolAfter };
}

export default MemoryPlugin;
