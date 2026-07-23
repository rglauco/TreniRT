#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""TreniRT - Treni in tempo reale via ViaggiaTreno API."""

import json
import urllib.parse
from datetime import datetime, timezone, timedelta

import requests
from flask import Flask, jsonify, request

app = Flask(__name__)

VT_BASE = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
session = requests.Session()
session.headers.update({"User-Agent": "TreniRT/1.0"})
session.mount("http://", requests.adapters.HTTPAdapter(max_retries=3))

TZ_ROME = timezone(timedelta(hours=2))  # CEST

APP_VERSION = "1.3.0"


def vt_get(method: str, *params: str):
    """Proxy GET to ViaggiaTreno API."""
    path = "/".join(urllib.parse.quote(str(p), safe="") for p in params)
    url = f"{VT_BASE}/{method}/{path}"
    r = session.get(url, timeout=15)
    if r.status_code == 204:
        return "", 204
    if r.status_code != 200 or "Error" in r.text[:50]:
        return jsonify({"error": f"VT API {r.status_code}", "url": url}), 502
    try:
        return jsonify(r.json())
    except Exception:
        return r.text


# ── API proxy endpoints ──────────────────────────────────────────────

@app.route("/api/autocompleta/<q>")
def autocompleta(q):
    return vt_get("autocompletaStazione", q)


@app.route("/api/cercaStazione/<q>")
def cerca_stazione(q):
    return vt_get("cercaStazione", q)


@app.route("/api/regione/<code>")
def regione(code):
    return vt_get("regione", code)


def _formatvttime(dt):
    """Format datetime for ViaggiaTreno API."""
    offset = dt.strftime("%z")  # e.g. +0200
    # VT expects format like: Tue Jul 21 2026 06:19:00 GMT+0200
    return dt.strftime("%a %b %d %Y %H:%M:%S GMT") + offset


# Note: these intentionally do NOT auto-shift a past time to "tomorrow" —
# that decision (and knowing which day was actually used, needed for
# pagination and for picking the right andamentoTreno reference day) is
# made client-side, same as the Android app.
@app.route("/api/partenze/<code>")
@app.route("/api/partenze/<code>/<path:when>")
def partenze(code, when=None):
    if when:
        ts = int(when)
        dt = datetime.fromtimestamp(ts / 1000, tz=TZ_ROME)
        t = _formatvttime(dt)
    else:
        t = _formatvttime(datetime.now(TZ_ROME))
    return vt_get("partenze", code, t)


@app.route("/api/arrivi/<code>")
@app.route("/api/arrivi/<code>/<path:when>")
def arrivi(code, when=None):
    if when:
        ts = int(when)
        dt = datetime.fromtimestamp(ts / 1000, tz=TZ_ROME)
        t = _formatvttime(dt)
    else:
        t = _formatvttime(datetime.now(TZ_ROME))
    return vt_get("arrivi", code, t)


@app.route("/api/cercaTreno/<num>")
def cerca_treno(num):
    return vt_get("cercaNumeroTrenoTrenoAutocomplete", num)


@app.route("/api/andamento/<origin>/<num>")
@app.route("/api/andamento/<origin>/<num>/<day>")
def andamento(origin, num, day=None):
    """[day] is the midnight (ms) of the specific day this train instance runs —
    the same train number recurs daily, so ViaggiaTreno needs it to disambiguate
    which run's live data to return. Defaults to today when not given."""
    if day:
        ts = int(day)
    else:
        midnight = datetime.now(TZ_ROME).replace(
            hour=0, minute=0, second=0, microsecond=0
        )
        ts = int(midnight.timestamp() * 1000)
    return vt_get("andamentoTreno", origin, num, str(ts))


# ── Serve the SPA ────────────────────────────────────────────────────

HTML = r"""<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="theme-color" content="#1a237e">
<title>TreniRT</title>
<link rel="manifest" href="/manifest.json">
<style>
:root{--bg:#0d1117;--card:#161b22;--border:#30363d;--text:#c9d1d9;--muted:#8b949e;--accent:#58a6ff;--green:#3fb950;--red:#f85149;--orange:#d29922;--yellow:#e3b341}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text);min-height:100vh;-webkit-tap-highlight-color:transparent}
.container{max-width:600px;margin:0 auto;padding:12px}
h1{font-size:1.3rem;text-align:center;padding:8px 0;color:var(--accent)}
.version-tag{font-size:.6em;color:var(--muted);font-weight:400;margin-left:6px}
.tabs{display:flex;gap:4px;margin-bottom:12px}
.tab{flex:1;padding:10px;text-align:center;border-radius:8px;cursor:pointer;background:var(--card);border:1px solid var(--border);font-size:.9rem;transition:all .2s}
.tab.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.search-box{position:relative;margin-bottom:12px}
.search-box input{width:100%;padding:12px 36px 12px 12px;border-radius:8px;border:1px solid var(--border);background:var(--card);color:var(--text);font-size:1rem;outline:none}
.search-box input:focus{border-color:var(--accent)}
.clear-btn{position:absolute;right:8px;top:50%;transform:translateY(-50%);color:var(--muted);cursor:pointer;font-size:1.1rem;padding:6px;line-height:1}
.autocomplete{position:absolute;width:100%;max-height:200px;overflow-y:auto;background:var(--card);border:1px solid var(--border);border-top:none;border-radius:0 0 8px 8px;z-index:10}
.autocomplete div{padding:10px 12px;cursor:pointer;font-size:.9rem}
.autocomplete div:hover,.autocomplete div:active{background:#1f6feb33}
.station-id{color:var(--muted);font-size:.8rem;margin-left:8px}
.recent-section{margin-bottom:12px}
.recent-label{color:var(--muted);font-size:.75rem;margin-bottom:2px}
.recent-item{padding:10px 4px;border-bottom:1px solid var(--border);cursor:pointer;display:flex;align-items:center;font-size:.9rem}
.recent-item:last-child{border-bottom:none}
.recent-arrow{color:var(--accent);margin:0 8px}
.swap-row{text-align:right;margin:-4px 0 8px}
.swap-btn{color:var(--accent);font-size:.8rem;cursor:pointer;display:inline-block}
.train-card{background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;margin-bottom:2px;cursor:pointer;transition:border-color .2s}
.train-card:hover,.train-card:active{border-color:var(--accent)}
.connection-note{color:var(--accent);font-size:.75rem;margin:2px 0 10px 6px}
.train-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:4px}
.train-number{font-weight:700;font-size:1rem}
.train-route{color:var(--muted);font-size:.85rem}
.train-time{display:flex;justify-content:space-between;align-items:center}
.time-sched{font-size:.9rem}
.time-real{font-weight:700;font-size:1rem}
.delay-min{font-weight:700;margin-left:6px;font-size:.9rem}
.delay-0{color:var(--green)}
.delay-pos{color:var(--orange)}
.delay-neg{color:var(--accent)}
.platform{background:#1f6feb33;padding:2px 6px;border-radius:4px;font-size:.8rem;margin-left:8px}
.cancelled{color:var(--red)!important;text-decoration:line-through}
.last-detect{font-size:.8rem;color:var(--muted);margin-top:4px}
.back-btn{display:inline-block;padding:8px 16px;background:var(--card);border:1px solid var(--border);border-radius:8px;color:var(--text);cursor:pointer;font-size:.9rem}
#results{min-height:50vh}
.loading{text-align:center;padding:30px;color:var(--muted)}
.loading-more{text-align:center;padding:16px;color:var(--muted);font-size:.85rem}
.empty{text-align:center;padding:40px;color:var(--muted)}
.warning-banner{color:var(--orange);font-size:.8rem;margin-bottom:8px;padding:8px;background:#d2992215;border-radius:6px}
.past-note{text-align:center;color:var(--orange);font-size:.85rem;margin-bottom:8px}
.stop-card{display:flex;align-items:center;padding:8px 0;border-bottom:1px solid var(--border)}
.stop-card:last-child{border-bottom:none}
.stop-dot{width:12px;height:12px;border-radius:50%;border:2px solid var(--border);margin-right:10px;flex-shrink:0}
.stop-dot.passed{background:var(--green);border-color:var(--green)}
.stop-dot.current{background:var(--orange);border-color:var(--orange);box-shadow:0 0 6px var(--orange)}
.stop-dot.cancelled{background:var(--red);border-color:var(--red)}
.stop-info{flex:1;min-width:0}
.stop-name{font-weight:500;font-size:.9rem}
.stop-link{cursor:pointer;color:var(--accent);text-decoration:none}
.stop-link:hover{text-decoration:underline}
.stop-times{font-size:.8rem;color:var(--muted)}
.stop-actual{color:var(--text);font-weight:600}
.stop-delay{margin-left:4px}
.detail-header{text-align:center;margin-bottom:16px}
.detail-cat{font-size:.85rem;color:var(--muted)}
.detail-route{font-size:1.2rem;font-weight:700;margin:4px 0}
.detail-delay{font-size:1.5rem;font-weight:700;margin:8px 0}
.detail-where{font-size:.85rem;color:var(--muted);margin-bottom:12px}
.detail-topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}
.filter-row{display:flex;gap:4px;margin-bottom:12px;align-items:center}
.filter-btn{flex:1;padding:8px;text-align:center;border-radius:6px;cursor:pointer;background:var(--card);border:1px solid var(--border);font-size:.8rem;color:var(--text);transition:all .2s}
.filter-btn.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.icon-btn{flex:0 0 auto;padding:8px 12px}
.time-input{width:90px;flex:0 0 auto;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);font-size:.85rem;text-align:center;outline:none}
</style>
</head>
<body>
<div class="container">
<h1>🚆 TreniRT <span class="version-tag">v__APP_VERSION__</span></h1>

<div class="tabs">
  <div class="tab active" id="tab-station" onclick="switchTab('station')">Stazione</div>
  <div class="tab" id="tab-train" onclick="switchTab('train')">Numero Treno</div>
</div>

<div id="search-station">
  <div class="search-box">
    <input id="station-input" type="text" placeholder="Cerca stazione..." autocomplete="off">
    <span class="clear-btn" id="station-clear" onclick="clearStation()" style="display:none">✕</span>
    <div class="autocomplete" id="station-suggestions" style="display:none"></div>
  </div>

  <div class="recent-section" id="recent-trips" style="display:none"></div>

  <div id="dest-section" style="display:none">
    <div class="search-box">
      <input id="dest-input" type="text" placeholder="Destinazione (opzionale)" autocomplete="off">
      <span class="clear-btn" id="dest-clear" onclick="clearDestination()" style="display:none">✕</span>
      <div class="autocomplete" id="dest-suggestions" style="display:none"></div>
    </div>
    <div class="swap-row" id="swap-row" style="display:none">
      <span class="swap-btn" onclick="swapStations()">⇅ Inverti partenza/destinazione</span>
    </div>
  </div>

  <div class="filter-row">
    <div class="filter-btn active" id="f-dep" onclick="setFilter('partenze')">Partenze</div>
    <div class="filter-btn" id="f-arr" onclick="setFilter('arrivi')">Arrivi</div>
    <div class="filter-btn icon-btn" id="f-refresh" onclick="refreshStation()" title="Aggiorna" style="display:none">🔄</div>
    <input type="time" id="time-input" class="time-input" title="Orario di riferimento">
    <div class="filter-btn icon-btn" id="f-now" onclick="setNow()" title="Adesso">🕐</div>
  </div>
</div>

<div id="search-train" style="display:none">
  <div class="search-box">
    <input id="train-input" type="text" placeholder="Numero treno (es. 9584)" autocomplete="off">
    <div class="autocomplete" id="train-suggestions" style="display:none"></div>
  </div>
  <div class="recent-section" id="recent-trains" style="display:none"></div>
</div>

<div id="results"></div>
</div>

<script>
const $ = id => document.getElementById(id);
let currentTab = 'station';
let currentFilter = 'partenze';
let debounceTimer = null, destDebounceTimer = null, trainDebounceTimer = null;
let viewingDetail = false;

let originCode = null, originName = null;
let destCode = null, destName = null;
let currentTrains = [];        // raw board for the selected station
let stopMatchedTrains = null;  // null = no destination filter; array = verified matches
let connectionInfo = {};       // numeroTreno -> connection info
let stopVerificationUnavailable = false;
let isCheckingStops = false;
let isLoadingMore = false;
let effectiveBoardDate = null; // Date actually used for the current board (may be "tomorrow")
let lastError = null;
let stopCheckToken = 0;

let currentTrainOrigin = null, currentTrainNumber = null, currentTrainRefDay = null;

const MAX_RECENT = 10;
const MIN_TRANSFER_MS = 60000;          // 1 minute — don't suggest impossible connections
const MAX_TRANSFER_WAIT_MS = 90 * 60000; // 90 minutes — cap how long a wait is worth suggesting

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ── localStorage-backed recent searches ─────────────────────────────
function loadRecent(key) {
  try { return JSON.parse(localStorage.getItem(key) || '[]'); } catch(e) { return []; }
}
function saveRecent(key, arr) { localStorage.setItem(key, JSON.stringify(arr)); }

function recordRecentTrip(o, d) {
  let trips = loadRecent('trenirt_recent_trips');
  trips = trips.filter(t => !(t.origin.code === o.code && t.dest.code === d.code));
  trips.unshift({origin: o, dest: d});
  saveRecent('trenirt_recent_trips', trips.slice(0, MAX_RECENT));
}
function recordRecentTrain(t) {
  let trains = loadRecent('trenirt_recent_trains');
  trains = trains.filter(x => !(x.number === t.number && x.originCode === t.originCode));
  trains.unshift(t);
  saveRecent('trenirt_recent_trains', trains.slice(0, MAX_RECENT));
}

function renderRecentTrips() {
  const trips = loadRecent('trenirt_recent_trips');
  const el = $('recent-trips');
  if (!trips.length || originCode) { el.style.display = 'none'; el.innerHTML = ''; return; }
  el.innerHTML = '<div class="recent-label">Ricerche recenti</div>' + trips.map((t,i) =>
    `<div class="recent-item" data-idx="${i}"><span>${escapeHtml(t.origin.name)}</span><span class="recent-arrow">→</span><span>${escapeHtml(t.dest.name)}</span></div>`
  ).join('');
  el.style.display = '';
  el.querySelectorAll('.recent-item').forEach(node => {
    node.addEventListener('click', () => selectRecentTrip(trips[parseInt(node.dataset.idx)]));
  });
}

function renderRecentTrains() {
  const trains = loadRecent('trenirt_recent_trains');
  const el = $('recent-trains');
  if (!trains.length || $('train-input').value.trim()) { el.style.display = 'none'; el.innerHTML = ''; return; }
  el.innerHTML = '<div class="recent-label">Ricerche recenti</div>' + trains.map((t,i) =>
    `<div class="recent-item" data-idx="${i}"><span>${escapeHtml(t.number)} — ${escapeHtml(t.originName)}</span></div>`
  ).join('');
  el.style.display = '';
  el.querySelectorAll('.recent-item').forEach(node => {
    node.addEventListener('click', () => {
      const t = trains[parseInt(node.dataset.idx)];
      loadTrainDetail(t.originCode, t.number, t.referenceDay);
      recordRecentTrain(t);
    });
  });
}

// ── Tabs ─────────────────────────────────────────────────────────────
function switchTab(tab) {
  currentTab = tab;
  viewingDetail = false;
  $('tab-station').className = tab === 'station' ? 'tab active' : 'tab';
  $('tab-train').className = tab === 'train' ? 'tab active' : 'tab';
  $('search-station').style.display = tab === 'station' ? '' : 'none';
  $('search-train').style.display = tab === 'train' ? '' : 'none';
  $('results').innerHTML = '';
  if (tab === 'station') renderRecentTrips(); else renderRecentTrains();
}

function updateClearButtons() {
  $('station-clear').style.display = originCode ? '' : 'none';
  $('dest-clear').style.display = destCode ? '' : 'none';
  $('f-refresh').style.display = originCode ? '' : 'none';
}

function updateDestLabel() {
  $('dest-input').placeholder = currentFilter === 'partenze' ? 'Destinazione (opzionale)' : 'Provenienza (opzionale)';
}

function setNow() {
  $('time-input').value = '';
  if (originCode) loadStationTrains();
}

function setFilter(f) {
  currentFilter = f;
  $('f-dep').className = f === 'partenze' ? 'filter-btn active' : 'filter-btn';
  $('f-arr').className = f === 'arrivi' ? 'filter-btn active' : 'filter-btn';
  updateDestLabel();
  if (originCode) loadStationTrains();
}

function refreshStation() {
  if (originCode) loadStationTrains();
}

$('time-input').addEventListener('change', function() {
  if (originCode) loadStationTrains();
});

// ── Station autocomplete ────────────────────────────────────────────
$('station-input').addEventListener('input', function() {
  originCode = null; originName = null;
  destCode = null; destName = null;
  updateClearButtons();
  $('dest-section').style.display = 'none';
  renderRecentTrips();
  const q = this.value.trim();
  if (q.length < 2) { $('station-suggestions').style.display = 'none'; return; }
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    fetch(`/api/autocompleta/${encodeURIComponent(q)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length || lines[0] === '') { $('station-suggestions').style.display = 'none'; return; }
        const items = lines.map(l => { const [name, code] = l.split('|'); return {name: name.trim(), code: code.trim()}; });
        $('station-suggestions').innerHTML = items.map((it,i) =>
          `<div data-idx="${i}">${escapeHtml(it.name)}<span class="station-id">${escapeHtml(it.code)}</span></div>`
        ).join('');
        $('station-suggestions').style.display = '';
        $('station-suggestions').querySelectorAll('div').forEach(node => {
          node.addEventListener('click', () => { const it = items[parseInt(node.dataset.idx)]; selectStation(it.code, it.name); });
        });
      });
  }, 300);
});

function selectStation(code, name) {
  originCode = code; originName = name;
  $('station-input').value = name;
  $('station-suggestions').style.display = 'none';
  destCode = null; destName = null;
  $('dest-input').value = '';
  $('dest-section').style.display = '';
  $('swap-row').style.display = 'none';
  updateClearButtons();
  renderRecentTrips();
  loadStationTrains();
}

function clearStation() {
  originCode = null; originName = null;
  destCode = null; destName = null;
  currentTrains = []; stopMatchedTrains = null; connectionInfo = {}; stopVerificationUnavailable = false;
  lastError = null;
  $('station-input').value = '';
  $('dest-input').value = '';
  $('dest-section').style.display = 'none';
  $('results').innerHTML = '';
  updateClearButtons();
  renderRecentTrips();
}

// ── Destination autocomplete ─────────────────────────────────────────
$('dest-input').addEventListener('input', function() {
  destCode = null; destName = null;
  updateClearButtons();
  $('swap-row').style.display = 'none';
  const q = this.value.trim();
  if (q.length < 2) { $('dest-suggestions').style.display = 'none'; return; }
  clearTimeout(destDebounceTimer);
  destDebounceTimer = setTimeout(() => {
    fetch(`/api/autocompleta/${encodeURIComponent(q)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length || lines[0] === '') { $('dest-suggestions').style.display = 'none'; return; }
        const items = lines.map(l => { const [name, code] = l.split('|'); return {name: name.trim(), code: code.trim()}; });
        $('dest-suggestions').innerHTML = items.map((it,i) =>
          `<div data-idx="${i}">${escapeHtml(it.name)}<span class="station-id">${escapeHtml(it.code)}</span></div>`
        ).join('');
        $('dest-suggestions').style.display = '';
        $('dest-suggestions').querySelectorAll('div').forEach(node => {
          node.addEventListener('click', () => { const it = items[parseInt(node.dataset.idx)]; selectDestination(it.code, it.name); });
        });
      });
  }, 300);
});

function selectDestination(code, name) {
  destCode = code; destName = name;
  $('dest-input').value = name;
  $('dest-suggestions').style.display = 'none';
  $('swap-row').style.display = '';
  updateClearButtons();
  if (originCode) recordRecentTrip({code: originCode, name: originName}, {code, name});
  runStopCheck();
}

function clearDestination() {
  destCode = null; destName = null;
  $('dest-input').value = '';
  $('swap-row').style.display = 'none';
  stopMatchedTrains = null; connectionInfo = {}; stopVerificationUnavailable = false;
  updateClearButtons();
  renderResults();
}

function swapStations() {
  if (!originCode || !destCode) return;
  const o = {code: originCode, name: originName};
  const d = {code: destCode, name: destName};
  originCode = d.code; originName = d.name;
  destCode = o.code; destName = o.name;
  $('station-input').value = originName;
  $('dest-input').value = destName;
  recordRecentTrip(d, o);
  loadStationTrains();
}

function selectRecentTrip(t) {
  originCode = t.origin.code; originName = t.origin.name;
  destCode = t.dest.code; destName = t.dest.name;
  $('station-input').value = originName;
  $('dest-input').value = destName;
  $('dest-section').style.display = '';
  $('swap-row').style.display = '';
  updateClearButtons();
  recordRecentTrip(t.origin, t.dest);
  loadStationTrains();
}

// ── Board loading + pagination ───────────────────────────────────────
async function fetchBoard(base, code, date) {
  try {
    const r = await fetch(`${base}${code}/${date.getTime()}`);
    if (r.status === 204) return [];
    const data = await r.json();
    return Array.isArray(data) ? data : [];
  } catch (e) {
    return null; // signals a real network/error, distinct from a legitimate empty list
  }
}

async function loadStationTrains() {
  viewingDetail = false;
  $('results').innerHTML = '<div class="loading">Caricamento...</div>';
  lastError = null;
  stopMatchedTrains = null; connectionInfo = {}; stopVerificationUnavailable = false;
  currentTrains = [];
  const myToken = ++stopCheckToken;

  const timeVal = $('time-input').value;
  const now = new Date();
  let queryDate = now;
  if (timeVal) {
    const [h, m] = timeVal.split(':').map(Number);
    queryDate = new Date(now.getFullYear(), now.getMonth(), now.getDate(), h, m, 0);
  }

  const base = currentFilter === 'partenze' ? '/api/partenze/' : '/api/arrivi/';
  let trains = await fetchBoard(base, originCode, queryDate);
  if (myToken !== stopCheckToken) return;

  if (trains === null) {
    lastError = 'Errore di caricamento';
    effectiveBoardDate = queryDate;
  } else if (queryDate < now && trains.length === 0) {
    const tomorrow = new Date(queryDate.getTime() + 86400000);
    const trainsTomorrow = await fetchBoard(base, originCode, tomorrow);
    if (myToken !== stopCheckToken) return;
    trains = trainsTomorrow || [];
    effectiveBoardDate = tomorrow;
    lastError = trains.length === 0 ? 'Nessun treno trovato' : 'Orario nel passato — orari di domani';
  } else {
    effectiveBoardDate = queryDate;
    lastError = trains.length === 0 ? 'Nessun treno trovato' : null;
  }

  currentTrains = trains || [];
  renderResults();
  if (destCode) runStopCheck();
}

function nextAnchorFrom(lastTimeStr, referenceDate) {
  if (!lastTimeStr) return null;
  const parts = lastTimeStr.split(':');
  if (parts.length !== 2) return null;
  const h = parseInt(parts[0]), m = parseInt(parts[1]);
  if (isNaN(h) || isNaN(m)) return null;
  const d = new Date(referenceDate.getFullYear(), referenceDate.getMonth(), referenceDate.getDate(), h, m, 0);
  d.setMinutes(d.getMinutes() + 1);
  return d;
}

async function loadMoreTrains() {
  if (!originCode || isLoadingMore || !currentTrains.length) return;
  const last = currentTrains[currentTrains.length - 1];
  const isDepartures = currentFilter === 'partenze';
  const lastTimeStr = isDepartures ? last.compOrarioPartenza : last.compOrarioArrivo;
  const referenceDate = effectiveBoardDate || new Date();
  const nextAnchor = nextAnchorFrom(lastTimeStr, referenceDate);
  if (!nextAnchor) return;

  isLoadingMore = true;
  renderResults();
  const base = isDepartures ? '/api/partenze/' : '/api/arrivi/';
  const more = await fetchBoard(base, originCode, nextAnchor);
  const newOnes = (more || []).filter(t => {
    const key = t.numeroTreno + '_' + t.codOrigine;
    return !currentTrains.some(e => (e.numeroTreno + '_' + e.codOrigine) === key);
  });
  currentTrains = currentTrains.concat(newOnes);
  isLoadingMore = false;

  if (destCode && newOnes.length) {
    const timeParam = '/' + nextAnchor.getTime();
    const result = await verifyCandidates(newOnes, isDepartures, timeParam);
    stopMatchedTrains = (stopMatchedTrains || []).concat(result.matched);
    connectionInfo = Object.assign({}, connectionInfo, result.connections);
    stopVerificationUnavailable = stopVerificationUnavailable || result.verificationUnavailable;
  }
  renderResults();
}

window.addEventListener('scroll', () => {
  if (!originCode || isLoadingMore || isCheckingStops) return;
  const scrollBottom = window.innerHeight + window.scrollY;
  if (scrollBottom >= document.documentElement.scrollHeight - 300) loadMoreTrains();
});

// ── Destination verification (direct + one-transfer) ─────────────────
function stationNamesMatch(field, target) {
  if (!field) return false;
  const a = field.trim().toUpperCase();
  const b = target.trim().toUpperCase();
  return a === b || a.includes(b) || b.includes(a);
}
function effectiveArrival(stop) { return (stop.arrivoReale > 0) ? stop.arrivoReale : (stop.arrivo_teorico || 0); }
function effectiveDeparture(stop) { return (stop.partenzaReale > 0) ? stop.partenzaReale : (stop.partenza_teorica || 0); }

function todayMidnightTs() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0, 0).getTime();
}
function trainReferenceDay(train) {
  return (train.dataPartenzaTreno && train.dataPartenzaTreno > 0) ? train.dataPartenzaTreno : todayMidnightTs();
}

async function fetchStops(originCode2, numeroTreno, referenceDay) {
  try {
    const r = await fetch(`/api/andamento/${originCode2}/${numeroTreno}/${referenceDay}`);
    if (r.status === 204) return null;
    const data = await r.json();
    return (data && data.fermate && data.fermate.length) ? data.fermate : null;
  } catch (e) { return null; }
}

function stopIsReachable(stops, isDepartures) {
  if (!stops || !stops.length) return false;
  const originIdx = stops.findIndex(s => s.id === originCode || stationNamesMatch(s.stazione, originName));
  const destIdx = stops.findIndex(s => s.id === destCode || stationNamesMatch(s.stazione, destName));
  if (originIdx === -1 || destIdx === -1) return false;
  return isDepartures ? destIdx > originIdx : destIdx < originIdx;
}

function findConnection(originStops, isDepartures, connectingStopsList) {
  const originIdx = originStops.findIndex(s => s.id === originCode || stationNamesMatch(s.stazione, originName));
  if (originIdx === -1) return null;
  const candidates = isDepartures ? originStops.slice(originIdx + 1) : originStops.slice(0, originIdx);

  for (const transferStop of candidates) {
    if (!transferStop.id) continue;
    for (const entry of connectingStopsList) {
      const cStops = entry.stops;
      const matchIdx = cStops.findIndex(s => s.id === transferStop.id);
      if (matchIdx === -1) continue;
      const destIdx = cStops.findIndex(s => s.id === destCode || stationNamesMatch(s.stazione, destName));
      if (destIdx === -1) continue;
      const validOrder = isDepartures ? destIdx > matchIdx : destIdx < matchIdx;
      if (!validOrder) continue;

      let arrival, departure;
      if (isDepartures) { arrival = effectiveArrival(transferStop); departure = effectiveDeparture(cStops[matchIdx]); }
      else { departure = effectiveDeparture(transferStop); arrival = effectiveArrival(cStops[matchIdx]); }
      if (arrival <= 0 || departure <= 0) continue;
      const wait = departure - arrival;
      if (wait >= MIN_TRANSFER_MS && wait <= MAX_TRANSFER_WAIT_MS) {
        return {
          transferStationName: transferStop.stazione,
          transferArrivalTime: arrival,
          transferDepartureTime: departure,
          connectingCategory: entry.train.categoriaDescrizione,
          connectingNumber: entry.train.numeroTreno
        };
      }
    }
  }
  return null;
}

async function verifyCandidates(candidates, isDepartures, timeParam) {
  const quickMatched = [];
  const needsDetail = [];
  for (const t of candidates) {
    const terminus = isDepartures ? t.destinazione : t.origine;
    if (stationNamesMatch(terminus, destName)) quickMatched.push(t); else needsDetail.push(t);
  }

  const candidateStopsEntries = (await Promise.all(needsDetail.map(async t => {
    const stops = await fetchStops(t.codOrigine, t.numeroTreno, trainReferenceDay(t));
    return stops ? {train: t, stops} : null;
  }))).filter(Boolean);

  const directNumbers = new Set(
    candidateStopsEntries.filter(e => stopIsReachable(e.stops, isDepartures)).map(e => e.train.numeroTreno)
  );
  const unresolved = candidateStopsEntries.filter(e => !directNumbers.has(e.train.numeroTreno));

  let matched = quickMatched.concat(candidateStopsEntries.filter(e => directNumbers.has(e.train.numeroTreno)).map(e => e.train));
  const connections = {};

  if (unresolved.length) {
    let connectingBoard = [];
    try {
      const endpoint = isDepartures ? 'arrivi' : 'partenze';
      const r = await fetch(`/api/${endpoint}/${destCode}${timeParam}`);
      const data = await r.json();
      connectingBoard = Array.isArray(data) ? data : [];
    } catch (e) { connectingBoard = []; }

    const connectingStopsList = (await Promise.all(connectingBoard.map(async t => {
      const stops = await fetchStops(t.codOrigine, t.numeroTreno, trainReferenceDay(t));
      return stops ? {train: t, stops} : null;
    }))).filter(Boolean);

    for (const entry of unresolved) {
      const conn = findConnection(entry.stops, isDepartures, connectingStopsList);
      if (conn) { matched.push(entry.train); connections[entry.train.numeroTreno] = conn; }
    }
  }

  // ViaggiaTreno only activates live data for a train shortly before/during its run, so when
  // checking a future schedule (e.g. the "past time -> tomorrow" fallback) almost everything
  // 204s. A rare early activation shouldn't count as "verification worked".
  const verificationUnavailable = needsDetail.length >= 3 && candidateStopsEntries.length < needsDetail.length / 3;
  return {matched, connections, verificationUnavailable};
}

async function runStopCheck() {
  if (!destCode || !originCode) return;
  const myToken = ++stopCheckToken;
  if (!currentTrains.length) {
    stopMatchedTrains = []; connectionInfo = {}; stopVerificationUnavailable = false;
    renderResults();
    return;
  }
  isCheckingStops = true;
  stopMatchedTrains = null; connectionInfo = {}; stopVerificationUnavailable = false;
  renderResults();

  const isDepartures = currentFilter === 'partenze';
  const timeParam = '/' + (effectiveBoardDate ? effectiveBoardDate.getTime() : Date.now());
  const result = await verifyCandidates(currentTrains, isDepartures, timeParam);
  if (myToken !== stopCheckToken) return;

  isCheckingStops = false;
  stopMatchedTrains = result.matched;
  connectionInfo = result.connections;
  stopVerificationUnavailable = result.verificationUnavailable;
  renderResults();
}

// ── Rendering ─────────────────────────────────────────────────────────
function fmtTime(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleTimeString('it-IT', {hour: '2-digit', minute: '2-digit'});
}

function trainCard(t) {
  const isCancelled = t.provvedimento === 1;
  const isPartialCancel = t.provvedimento === 2;
  const delayed = t.ritardo || 0;
  const cancelledClass = isCancelled ? ' cancelled' : '';
  const delayClass = delayed > 0 ? 'delay-pos' : delayed < 0 ? 'delay-neg' : 'delay-0';
  const delayText = delayed === 0 ? 'In orario' : (delayed > 0 ? `+${delayed}'` : `${delayed}'`);

  const schedTime = (t.compOrarioPartenza || t.compOrarioArrivo || '--:--');
  const realTime = t.compOrarioPartenzaZeroEffettivo || t.compOrarioArrivoZeroEffettivo || null;

  const category = (t.categoriaDescrizione || '').trim();
  const num = t.numeroTreno;
  const label = category ? `${category} ${num}` : `Treno ${num}`;

  const dest = t.destinazione || t.origine || '—';
  const platform = t.binarioEffettivoPartenzaDescrizione || t.binarioEffettivoArrivoDescrizione || null;

  let statusBadge = '';
  if (isCancelled) statusBadge = '<span style="color:var(--red);font-weight:700;margin-left:6px">CANCELLATO</span>';
  else if (isPartialCancel) statusBadge = '<span style="color:var(--orange);font-weight:700;margin-left:6px">PARZ. CANCELLATO</span>';

  const origin = t.codOrigine || '';

  return `<div class="train-card" data-origin="${escapeHtml(origin)}" data-num="${num}">
    <div class="train-header">
      <span class="train-number${cancelledClass}">${escapeHtml(label)}${platform ? `<span class="platform">Bin. ${escapeHtml(platform)}</span>` : ''}</span>
      <span class="${delayClass}">${delayText}</span>
    </div>
    <div class="train-route${cancelledClass}">${escapeHtml(dest)}</div>
    <div class="train-time">
      <span class="time-sched${cancelledClass}">⏰ ${escapeHtml(schedTime)}</span>
      ${realTime && realTime !== schedTime ? `<span class="time-real ${delayClass}">${escapeHtml(realTime)}</span>` : ''}
    </div>
    ${statusBadge}
    ${t.inStazione ? '<div style="color:var(--green);font-size:.8rem">🟢 In stazione</div>' : ''}
  </div>`;
}

function renderResults() {
  const el = $('results');
  let html = '';

  if (lastError) html += `<div class="past-note">${escapeHtml(lastError)}</div>`;

  if (isCheckingStops) {
    html += `<div class="loading">Verifica fermate...</div>`;
    el.innerHTML = html;
    return;
  }

  let displayed;
  if (!destCode) {
    displayed = currentTrains;
  } else if (stopVerificationUnavailable && (!stopMatchedTrains || !stopMatchedTrains.length)) {
    html += `<div class="warning-banner">⚠️ Non riesco a verificare le fermate per questo orario (dati non ancora disponibili) — ecco tutti i treni, controlla tu quali fermano a ${escapeHtml(destName)}</div>`;
    displayed = currentTrains;
  } else {
    displayed = stopMatchedTrains || [];
    if (!displayed.length) {
      html += `<div class="empty">Nessun treno, nemmeno con cambio, per ${escapeHtml(destName)}</div>`;
      el.innerHTML = html;
      return;
    }
  }

  if (!displayed.length) {
    if (!lastError) html += `<div class="empty">Nessun treno trovato</div>`;
  } else {
    html += displayed.map(t => {
      let card = trainCard(t);
      const conn = connectionInfo[t.numeroTreno];
      if (conn) {
        card += `<div class="connection-note">🔄 Cambio a ${escapeHtml(conn.transferStationName)} (arrivo ${fmtTime(conn.transferArrivalTime)}) → ${escapeHtml(conn.connectingCategory)} ${conn.connectingNumber} delle ${fmtTime(conn.transferDepartureTime)}</div>`;
      }
      return card;
    }).join('');
  }

  if (isLoadingMore) html += `<div class="loading-more">Caricamento altri treni...</div>`;

  el.innerHTML = html;
  el.querySelectorAll('.train-card').forEach(node => {
    node.addEventListener('click', () => loadTrainDetail(node.dataset.origin, node.dataset.num, null));
  });
}

// ── Train number search ────────────────────────────────────────────
$('train-input').addEventListener('input', function() {
  const q = this.value.trim();
  renderRecentTrains();
  if (q.length < 1) { $('train-suggestions').style.display = 'none'; return; }
  clearTimeout(trainDebounceTimer);
  trainDebounceTimer = setTimeout(() => {
    fetch(`/api/cercaTreno/${encodeURIComponent(q)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length || lines[0] === '') {
          if (/^\d+$/.test(q)) loadTrainDetail(null, q, null);
          $('train-suggestions').style.display = 'none';
          return;
        }
        const items = lines.map(l => {
          const [label, rest] = l.split('|');
          const parts = rest.split('-');
          return {label, number: parts[0], originCode: parts[1], referenceDay: parts[2] ? parseInt(parts[2]) : null};
        });
        $('train-suggestions').innerHTML = items.map((it,i) => `<div data-idx="${i}">${escapeHtml(it.label)}</div>`).join('');
        $('train-suggestions').style.display = '';
        $('train-suggestions').querySelectorAll('div').forEach(node => {
          node.addEventListener('click', () => {
            const it = items[parseInt(node.dataset.idx)];
            loadTrainDetail(it.originCode, it.number, it.referenceDay);
            recordRecentTrain({number: it.number, originCode: it.originCode, originName: it.label, referenceDay: it.referenceDay});
          });
        });
      });
  }, 300);
});

function loadTrainDetail(origin, num, referenceDay) {
  $('train-suggestions').style.display = 'none';
  if (!origin) {
    $('results').innerHTML = '<div class="loading">Ricerca...</div>';
    fetch(`/api/cercaTreno/${encodeURIComponent(num)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length) { $('results').innerHTML = '<div class="empty">Treno non trovato</div>'; return; }
        if (lines.length === 1) {
          const [, rest] = lines[0].split('|');
          const p = rest.split('-');
          fetchTrainDetail(p[1], p[0], p[2] ? parseInt(p[2]) : null);
        } else {
          $('results').innerHTML = lines.map(l => {
            const [label, rest] = l.split('|');
            const p = rest.split('-');
            return `<div class="train-card" data-origin="${escapeHtml(p[1])}" data-num="${escapeHtml(p[0])}" data-day="${p[2]||''}">${escapeHtml(label)}</div>`;
          }).join('');
          $('results').querySelectorAll('.train-card').forEach(node => {
            node.addEventListener('click', () => fetchTrainDetail(node.dataset.origin, node.dataset.num, node.dataset.day ? parseInt(node.dataset.day) : null));
          });
        }
      });
  } else {
    fetchTrainDetail(origin, num, referenceDay);
  }
}

function fetchTrainDetail(origin, num, referenceDay) {
  $('results').innerHTML = '<div class="loading">Caricamento dettagli...</div>';
  currentTrainOrigin = origin; currentTrainNumber = num; currentTrainRefDay = referenceDay || todayMidnightTs();
  fetch(`/api/andamento/${origin}/${num}/${currentTrainRefDay}`)
    .then(r => r.status === 204 ? null : r.json())
    .then(data => {
      if (!data) { $('results').innerHTML = '<div class="empty">Dettagli non disponibili (treno cancellato o non ancora partito)</div>'; return; }
      renderTrainDetail(data);
    })
    .catch(() => $('results').innerHTML = '<div class="empty">Errore di caricamento</div>');
}

function refreshTrainDetail() {
  if (!currentTrainOrigin || !currentTrainNumber) return;
  fetch(`/api/andamento/${currentTrainOrigin}/${currentTrainNumber}/${currentTrainRefDay}`)
    .then(r => r.status === 204 ? null : r.json())
    .then(data => { if (data) renderTrainDetail(data); })
    .catch(() => {});
}

function renderTrainDetail(t) {
  viewingDetail = true;
  const isCancelled = t.provvedimento === 1;
  const isPartialCancel = t.provvedimento === 2;
  const delayed = t.ritardo || 0;

  const delayClass = delayed > 0 ? 'delay-pos' : delayed < 0 ? 'delay-neg' : 'delay-0';
  const delayText = delayed === 0 ? '✅ In orario' : (delayed > 0 ? `⚠️ +${delayed} min` : `${delayed} min`);

  let statusText = '';
  if (isCancelled) statusText = '<span style="color:var(--red);font-weight:700">CANCELLATO</span>';
  else if (isPartialCancel) statusText = '<span style="color:var(--orange);font-weight:700">PARZIALMENTE CANCELLATO</span>';

  const category = (t.categoria || '').trim();
  const origin = t.origine || '—';
  const destination = t.destinazione || '—';

  const lastPlace = t.stazioneUltimoRilevamento;
  const lastTime = t.oraUltimoRilevamento ? fmtTime(t.oraUltimoRilevamento) : null;
  let whereHtml = '';
  if (lastPlace && lastPlace !== '--' && lastTime) {
    whereHtml = `<div class="detail-where">📍 Ultimo rilevamento: ${escapeHtml(lastPlace)} alle ${lastTime}</div>`;
  }

  const stops = t.fermate || [];
  let currentIdx = -1;
  for (let i = 0; i < stops.length; i++) {
    const s = stops[i];
    if ((s.tipoFermata === 'F' || s.tipoFermata === 'A') && s.arrivoReale) currentIdx = i;
  }

  const stopsHtml = stops.map((s, i) => {
    const isCancelledStop = s.actualFermataType === 3;
    const isOrigin = s.tipoFermata === 'P';
    const isDest = s.tipoFermata === 'A';
    const hasArrived = s.arrivoReale != null && s.arrivoReale > 0;
    const hasDeparted = s.partenzaReale != null && s.partenzaReale > 0;
    const isCurrent = (i === currentIdx);

    let dotClass = 'stop-dot';
    if (isCancelledStop) dotClass += ' cancelled';
    else if (isCurrent) dotClass += ' current';
    else if ((isOrigin && hasDeparted) || (!isOrigin && hasArrived)) dotClass += ' passed';

    const name = s.stazione || '—';
    const sid = s.id || '';
    let timesHtml = '';

    if (isOrigin) {
      const sched = fmtTime(s.partenza_teorica);
      const real = fmtTime(s.partenzaReale);
      const del = s.ritardoPartenza || 0;
      const delClass = del > 0 ? 'delay-pos' : del < 0 ? 'delay-neg' : 'delay-0';
      const delText = del === 0 ? 'in orario' : (del > 0 ? `+${del}'` : `${del}'`);
      const platform = s.binarioEffettivoPartenzaDescrizione || s.binarioProgrammatoPartenzaDescrizione;
      timesHtml = `${sched}${real && real !== sched ? ` → <span class="stop-actual">${real}</span>` : ''} <span class="stop-delay ${delClass}">${delText}</span>${platform ? ` · Bin ${escapeHtml(platform)}` : ''}`;
    } else if (isDest) {
      const sched = fmtTime(s.arrivo_teorico);
      const real = fmtTime(s.arrivoReale);
      const del = s.ritardoArrivo || s.ritardo || 0;
      const delClass = del > 0 ? 'delay-pos' : del < 0 ? 'delay-neg' : 'delay-0';
      const delText = del === 0 ? 'in orario' : (del > 0 ? `+${del}'` : `${del}'`);
      timesHtml = `Arrivo: ${sched}${real && real !== sched ? ` → <span class="stop-actual">${real}</span>` : ''} <span class="stop-delay ${delClass}">${delText}</span>`;
    } else {
      const arrSched = fmtTime(s.arrivo_teorico);
      const arrReal = fmtTime(s.arrivoReale);
      const depSched = fmtTime(s.partenza_teorica);
      const depReal = fmtTime(s.partenzaReale);
      const arrDel = s.ritardoArrivo || 0;
      const arrDelClass = arrDel > 0 ? 'delay-pos' : arrDel < 0 ? 'delay-neg' : 'delay-0';
      const arrDelText = arrDel === 0 ? '' : (arrDel > 0 ? `+${arrDel}'` : `${arrDel}'`);
      const platform = s.binarioEffettivoArrivoDescrizione || s.binarioEffettivoPartenzaDescrizione || s.binarioProgrammatoArrivoDescrizione;
      timesHtml = `↓ ${arrSched}${arrReal && arrReal !== arrSched ? ` → <span class="stop-actual">${arrReal}</span>` : ''} ${arrDelText ? `<span class="stop-delay ${arrDelClass}">${arrDelText}</span>` : ''} · ↓ ${depSched}${depReal && depReal !== depSched ? ` → <span class="stop-actual">${depReal}</span>` : ''}${platform ? ` · Bin ${escapeHtml(platform)}` : ''}`;
    }

    if (isCancelledStop) timesHtml += ' <span style="color:var(--red)">SOPPRESSA</span>';

    const nameHtml = sid ? `<span class="stop-link" data-sid="${escapeHtml(sid)}" data-sname="${escapeHtml(name)}">${escapeHtml(name)}</span>` : escapeHtml(name);
    return `<div class="stop-card"><div class="${dotClass}"></div><div class="stop-info"><div class="stop-name">${nameHtml}</div><div class="stop-times">${timesHtml}</div></div></div>`;
  }).join('');

  $('results').innerHTML = `
    <div class="detail-topbar">
      <div class="back-btn" onclick="goBack()">&larr; Indietro</div>
      <div class="back-btn" onclick="refreshTrainDetail()" title="Aggiorna">🔄</div>
    </div>
    <div class="detail-header">
      <div class="detail-cat">${escapeHtml(category)}</div>
      <div class="detail-route">${escapeHtml(origin)} → ${escapeHtml(destination)}</div>
      <div class="detail-delay ${delayClass}">${delayText}</div>
      ${statusText}
      ${whereHtml}
    </div>
    ${stopsHtml}
  `;
  $('results').querySelectorAll('.stop-link').forEach(node => {
    node.addEventListener('click', () => openStation(node.dataset.sid, node.dataset.sname));
  });
}

function openStation(code, name) {
  switchTab('station');
  originCode = code; originName = name;
  destCode = null; destName = null;
  $('station-input').value = name;
  $('dest-input').value = '';
  $('dest-section').style.display = '';
  $('swap-row').style.display = 'none';
  updateClearButtons();
  currentFilter = 'partenze';
  $('f-dep').className = 'filter-btn active'; $('f-arr').className = 'filter-btn';
  loadStationTrains();
}

function goBack() {
  viewingDetail = false;
  if (originCode && currentTab === 'station') renderResults();
  else $('results').innerHTML = '';
}

// ── Auto-refresh ──────────────────────────────────────────────────────
let refreshInterval = null;
function startAutoRefresh() {
  stopAutoRefresh();
  refreshInterval = setInterval(() => {
    if (originCode && currentTab === 'station' && !viewingDetail) loadStationTrains();
  }, 60000);
}
function stopAutoRefresh() {
  if (refreshInterval) { clearInterval(refreshInterval); refreshInterval = null; }
}

updateDestLabel();
renderRecentTrips();
startAutoRefresh();
</script>
</body>
</html>"""

HTML = HTML.replace("__APP_VERSION__", APP_VERSION)

MANIFEST = json.dumps({
    "name": "TreniRT",
    "short_name": "TreniRT",
    "description": "Treni italiani in tempo reale",
    "start_url": "/",
    "display": "standalone",
    "background_color": "#0d1117",
    "theme_color": "#1a237e",
    "icons": [
        {"src": "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🚆</text></svg>",
         "sizes": "any", "type": "image/svg+xml"}
    ]
}, ensure_ascii=False)


@app.route("/")
def index():
    return HTML


@app.route("/manifest.json")
def manifest():
    return MANIFEST, 200, {"Content-Type": "application/json"}


if __name__ == "__main__":
    print(f"🚆 TreniRT v{APP_VERSION} avviato su http://localhost:5000")
    print("   Apri dal telefono: http://<IP-LOCALE>:5000")
    app.run(host="0.0.0.0", port=5000, debug=True)
