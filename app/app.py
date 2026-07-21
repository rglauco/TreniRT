#!/usr/bin/env python3
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


@app.route("/api/partenze/<code>")
@app.route("/api/partenze/<code>/<path:when>")
def partenze(code, when=None):
    if when:
        ts = int(when)
        dt = datetime.fromtimestamp(ts / 1000, tz=TZ_ROME)
        # If requested time is in the past, shift to tomorrow (VT API returns [] for past times)
        now = datetime.now(TZ_ROME)
        if dt < now:
            dt = dt + timedelta(days=1)
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
        now = datetime.now(TZ_ROME)
        if dt < now:
            dt = dt + timedelta(days=1)
        t = _formatvttime(dt)
    else:
        t = _formatvttime(datetime.now(TZ_ROME))
    return vt_get("arrivi", code, t)


@app.route("/api/cercaTreno/<num>")
def cerca_treno(num):
    return vt_get("cercaNumeroTrenoTrenoAutocomplete", num)


@app.route("/api/andamento/<origin>/<num>")
def andamento(origin, num):
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
.tabs{display:flex;gap:4px;margin-bottom:12px}
.tab{flex:1;padding:10px;text-align:center;border-radius:8px;cursor:pointer;background:var(--card);border:1px solid var(--border);font-size:.9rem;transition:all .2s}
.tab.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.search-box{position:relative;margin-bottom:12px}
.search-box input{width:100%;padding:12px;border-radius:8px;border:1px solid var(--border);background:var(--card);color:var(--text);font-size:1rem;outline:none}
.search-box input:focus{border-color:var(--accent)}
.autocomplete{position:absolute;width:100%;max-height:200px;overflow-y:auto;background:var(--card);border:1px solid var(--border);border-top:none;border-radius:0 0 8px 8px;z-index:10}
.autocomplete div{padding:10px 12px;cursor:pointer;font-size:.9rem}
.autocomplete div:hover,.autocomplete div:active{background:#1f6feb33}
.station-id{color:var(--muted);font-size:.8rem;margin-left:8px}
.train-card{background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;margin-bottom:8px;cursor:pointer;transition:border-color .2s}
.train-card:hover,.train-card:active{border-color:var(--accent)}
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
.back-btn{display:inline-block;padding:8px 16px;background:var(--card);border:1px solid var(--border);border-radius:8px;color:var(--text);cursor:pointer;margin-bottom:12px;font-size:.9rem}
#results{min-height:50vh}
.loading{text-align:center;padding:30px;color:var(--muted)}
.empty{text-align:center;padding:40px;color:var(--muted)}
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
.filter-row{display:flex;gap:4px;margin-bottom:12px}
.filter-btn{flex:1;padding:8px;text-align:center;border-radius:6px;cursor:pointer;background:var(--card);border:1px solid var(--border);font-size:.8rem;color:var(--text);transition:all .2s}
.filter-btn.active{background:var(--accent);color:#fff;border-color:var(--accent)}
.time-input{width:90px;padding:8px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);font-size:.85rem;text-align:center;outline:none}
</style>
</head>
<body>
<div class="container">
<h1>🚆 TreniRT</h1>

<div class="tabs">
  <div class="tab active" id="tab-station" onclick="switchTab('station')">Stazione</div>
  <div class="tab" id="tab-train" onclick="switchTab('train')">Numero Treno</div>
</div>

<div id="search-station">
  <div class="search-box">
    <input id="station-input" type="text" placeholder="Cerca stazione..." autocomplete="off">
    <div class="autocomplete" id="station-suggestions" style="display:none"></div>
  </div>
  <div class="filter-row">
    <div class="filter-btn active" id="f-dep" onclick="setFilter('partenze')">Partenze</div>
    <div class="filter-btn" id="f-arr" onclick="setFilter('arrivi')">Arrivi</div>
    <input type="time" id="time-input" class="time-input" title="Orario di riferimento">
    <div class="filter-btn" id="f-now" onclick="setNow()" title="Adesso">🕐</div>
  </div>
</div>

<div id="search-train" style="display:none">
  <div class="search-box">
    <input id="train-input" type="text" placeholder="Numero treno (es. 9584)" autocomplete="off">
    <div class="autocomplete" id="train-suggestions" style="display:none"></div>
  </div>
</div>

<div id="results"></div>
</div>

<script>
const $ = id => document.getElementById(id);
let currentTab = 'station';
let currentFilter = 'partenze';
let debounceTimer = null;

function switchTab(tab) {
  currentTab = tab;
  $('tab-station').className = tab === 'station' ? 'tab active' : 'tab';
  $('tab-train').className = tab === 'train' ? 'tab active' : 'tab';
  $('search-station').style.display = tab === 'station' ? '' : 'none';
  $('search-train').style.display = tab === 'train' ? '' : 'none';
  $('results').innerHTML = '';
}

function setNow() {
  const now = new Date();
  $('time-input').value = String(now.getHours()).padStart(2,'0') + ':' + String(now.getMinutes()).padStart(2,'0');
  const code = $('station-input').dataset.code;
  if (code) loadStationTrains(code);
}

function setFilter(f) {
  currentFilter = f;
  $('f-dep').className = f === 'partenze' ? 'filter-btn active' : 'filter-btn';
  $('f-arr').className = f === 'arrivi' ? 'filter-btn active' : 'filter-btn';
  // Re-trigger station search if one is active
  const code = $('station-input').dataset.code;
  if (code) loadStationTrains(code);
}

// ── Station autocomplete ────────────────────────────────────────────
$('station-input').addEventListener('input', function() {
  delete this.dataset.code;
  const q = this.value.trim();
  if (q.length < 2) { $('station-suggestions').style.display='none'; return; }
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    fetch(`/api/autocompleta/${encodeURIComponent(q)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length || lines[0] === '') {
          $('station-suggestions').style.display = 'none';
          return;
        }
        $('station-suggestions').innerHTML = lines.map(l => {
          const [name, code] = l.split('|');
          return `<div onclick="selectStation('${code}','${name.replace(/'/g,"\\'")}')">${name}<span class="station-id">${code}</span></div>`;
        }).join('');
        $('station-suggestions').style.display = '';
      });
  }, 300);
});

function selectStation(code, name) {
  $('station-input').value = name;
  $('station-input').dataset.code = code;
  $('station-suggestions').style.display = 'none';
  loadStationTrains(code);
}

function loadStationTrains(code) {
  $('results').innerHTML = '<div class="loading">Caricamento...</div>';
  const timeVal = $('time-input').value;
  let timeParam = '';
  let pastNote = false;
  if (timeVal) {
    const [h, m] = timeVal.split(':').map(Number);
    const now = new Date();
    let dt = new Date(now.getFullYear(), now.getMonth(), now.getDate(), h, m, 0);
    if (dt < now) {
      dt.setDate(dt.getDate() + 1);
      pastNote = true;
    }
    timeParam = '/' + dt.getTime();
  }
  const base = currentFilter === 'partenze' ? '/api/partenze/' : '/api/arrivi/';
  const endpoint = base + code + timeParam;
  const heading = pastNote ? '<div style="text-align:center;color:var(--orange);font-size:.85rem;margin-bottom:8px">📅 Orario nel passato — mostrando orari di domani</div>' : '';
  fetch(endpoint)
    .then(r => r.json())
    .then(trains => {
      if (!trains || !trains.length) {
        $('results').innerHTML = heading + '<div class="empty">Nessun treno trovato</div>';
        return;
      }
      $('results').innerHTML = heading + trains.map(t => trainCard(t)).join('');
    })
    .catch(() => $('results').innerHTML = '<div class="empty">Errore di caricamento</div>');
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

  // Build click handler to load train detail
  const origin = t.codOrigine || '';
  const onclick = `onclick="loadTrainDetail('${origin}','${num}')"`;

  return `<div class="train-card" ${onclick}>
    <div class="train-header">
      <span class="train-number${cancelledClass}">${label}${platform ? `<span class="platform">Bin. ${platform}</span>` : ''}</span>
      <span class="${delayClass}">${delayText}</span>
    </div>
    <div class="train-route${cancelledClass}">${dest}</div>
    <div class="train-time">
      <span class="time-sched${cancelledClass}">⏰ ${schedTime}</span>
      ${realTime && realTime !== schedTime ? `<span class="time-real ${delayClass}">${realTime}</span>` : ''}
    </div>
    ${statusBadge}
    ${t.inStazione ? '<div style="color:var(--green);font-size:.8rem">🟢 In stazione</div>' : ''}
  </div>`;
}

// ── Train number search ────────────────────────────────────────────
$('train-input').addEventListener('input', function() {
  const q = this.value.trim();
  if (q.length < 1) { $('train-suggestions').style.display='none'; return; }
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    fetch(`/api/cercaTreno/${encodeURIComponent(q)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length || lines[0] === '') {
          // Try direct load if numeric
          if (/^\d+$/.test(q)) {
            loadTrainDetail(null, q);
          }
          $('train-suggestions').style.display = 'none';
          return;
        }
        $('train-suggestions').innerHTML = lines.map(l => {
          const [label, rest] = l.split('|');
          const parts = rest.split('-');
          const num = parts[0];
          const originCode = parts[1];
          return `<div onclick="loadTrainDetail('${originCode}','${num}')">${label}</div>`;
        }).join('');
        $('train-suggestions').style.display = '';
      });
  }, 300);
});

function loadTrainDetail(origin, num) {
  $('train-suggestions').style.display = 'none';

  if (!origin) {
    // Need to disambiguate first
    $('results').innerHTML = '<div class="loading">Ricerca...</div>';
    fetch(`/api/cercaTreno/${encodeURIComponent(num)}`)
      .then(r => r.text())
      .then(text => {
        const lines = text.trim().split('\n').filter(l => l);
        if (!lines.length) {
          $('results').innerHTML = '<div class="empty">Treno non trovato</div>';
          return;
        }
        if (lines.length === 1) {
          const [_, rest] = lines[0].split('|');
          const p = rest.split('-');
          fetchTrainDetail(p[1], p[0]);
        } else {
          // Multiple matches - show list
          $('results').innerHTML = lines.map(l => {
            const [label, rest] = l.split('|');
            const p = rest.split('-');
            return `<div class="train-card" onclick="loadTrainDetail('${p[1]}','${p[0]}')">${label}</div>`;
          }).join('');
        }
      });
  } else {
    fetchTrainDetail(origin, num);
  }
}

function fetchTrainDetail(origin, num) {
  $('results').innerHTML = '<div class="loading">Caricamento dettagli...</div>';
  fetch(`/api/andamento/${origin}/${num}`)
    .then(r => {
      if (r.status === 204) return null;
      return r.json();
    })
    .then(data => {
      if (!data) {
        $('results').innerHTML = '<div class="empty">Dettagli non disponibili (treno cancellato o non ancora partito)</div>';
        return;
      }
      renderTrainDetail(data);
    })
    .catch(() => $('results').innerHTML = '<div class="empty">Errore di caricamento</div>');
}

function fmtTime(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  return d.toLocaleTimeString('it-IT', {hour:'2-digit', minute:'2-digit'});
}

function renderTrainDetail(t) {
  const isCancelled = t.provvedimento === 1;
  const isPartialCancel = t.provvedimento === 2;
  const delayed = t.ritardo || 0;

  let delayClass = delayed > 0 ? 'delay-pos' : delayed < 0 ? 'delay-neg' : 'delay-0';
  let delayText = delayed === 0 ? '✅ In orario' : (delayed > 0 ? `⚠️ +${delayed} min` : `${delayed} min`);

  let statusText = '';
  if (isCancelled) statusText = '<span style="color:var(--red);font-weight:700">CANCELLATO</span>';
  else if (isPartialCancel) statusText = '<span style="color:var(--orange);font-weight:700">PARZIALMENTE CANCELLATO</span>';

  const category = (t.categoria || '').trim();
  const origin = t.origine || '—';
  const destination = t.destinazione || '—';

  // Last detection
  const lastPlace = t.stazioneUltimoRilevamento;
  const lastTime = t.oraUltimoRilevamento ? fmtTime(t.oraUltimoRilevamento) : null;
  let whereHtml = '';
  if (lastPlace && lastPlace !== '--' && lastTime) {
    whereHtml = `<div class="detail-where">📍 Ultimo rilevamento: ${lastPlace} alle ${lastTime}</div>`;
  }

  const stops = t.fermate || [];
  // Find current stop index (last stop with actual departure but next stop not yet arrived)
  let currentIdx = -1;
  for (let i = 0; i < stops.length; i++) {
    const s = stops[i];
    if (s.tipoFermata === 'F' || s.tipoFermata === 'A') {
      if (s.arrivoReale) currentIdx = i;
    }
  }

  let stopsHtml = stops.map((s, i) => {
    const isCancelled = s.actualFermataType === 3;
    const isOrigin = s.tipoFermata === 'P';
    const isDest = s.tipoFermata === 'A';
    const hasArrived = s.arrivoReale != null;
    const hasDeparted = s.partenzaReale != null;
    const isCurrent = (i === currentIdx);

    let dotClass = 'stop-dot';
    if (isCancelled) dotClass += ' cancelled';
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
      let platform = s.binarioEffettivoPartenzaDescrizione || s.binarioProgrammatoPartenzaDescrizione;
      timesHtml = `${sched}${real && real !== sched ? ` → <span class="stop-actual">${real}</span>` : ''} <span class="stop-delay ${delClass}">${delText}</span>${platform ? ` · Bin ${platform}` : ''}`;
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
      let platform = s.binarioEffettivoArrivoDescrizione || s.binarioEffettivoPartenzaDescrizione || s.binarioProgrammatoArrivoDescrizione;
      timesHtml = `↓ ${arrSched}${arrReal && arrReal !== arrSched ? ` → <span class="stop-actual">${arrReal}</span>` : ''} ${arrDelText ? `<span class="stop-delay ${arrDelClass}">${arrDelText}</span>` : ''} · ↓ ${depSched}${depReal && depReal !== depSched ? ` → <span class="stop-actual">${depReal}</span>` : ''}${platform ? ` · Bin ${platform}` : ''}`;
    }

    if (isCancelled) timesHtml += ' <span style="color:var(--red)">SOPPRESSA</span>';

    const nameHtml = sid ? `<span class="stop-link" onclick="openStation('${sid}','${name.replace(/'/g,"\\'")}')">${name}</span>` : name;
    return `<div class="stop-card"><div class="${dotClass}"></div><div class="stop-info"><div class="stop-name">${nameHtml}</div><div class="stop-times">${timesHtml}</div></div></div>`;
  }).join('');

  $('results').innerHTML = `
    <div class="back-btn" onclick="goBack()">&larr; Indietro</div>
    <div class="detail-header">
      <div class="detail-cat">${category}</div>
      <div class="detail-route">${origin} → ${destination}</div>
      <div class="detail-delay ${delayClass}">${delayText}</div>
      ${statusText}
      ${whereHtml}
    </div>
    ${stopsHtml}
  `;
}

function openStation(code, name) {
  switchTab('station');
  $('station-input').value = name;
  $('station-input').dataset.code = code;
  loadStationTrains(code);
}

function goBack() {
  const code = $('station-input').dataset.code;
  if (code && currentTab === 'station') {
    loadStationTrains(code);
  } else {
    $('results').innerHTML = '';
  }
}

// ── Time change listener ───────────────────────────────────────────
$('time-input').addEventListener('change', function() {
  const code = $('station-input').dataset.code;
  if (code) loadStationTrains(code);
});

// ── Auto-refresh ──────────────────────────────────────────────────── ────────────────────────────────────────────────────
let refreshInterval = null;
function startAutoRefresh() {
  stopAutoRefresh();
  refreshInterval = setInterval(() => {
    const code = $('station-input').dataset.code;
    if (code && currentTab === 'station') loadStationTrains(code);
  }, 60000);
}
function stopAutoRefresh() {
  if (refreshInterval) { clearInterval(refreshInterval); refreshInterval = null; }
}
startAutoRefresh();
</script>
</body>
</html>"""

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
    print("🚆 TreniRT avviato su http://localhost:5000")
    print("   Apri dal telefono: http://<IP-LOCALE>:5000")
    app.run(host="0.0.0.0", port=5000, debug=True)