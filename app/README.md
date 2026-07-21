# 🚆 TreniRT — Treni italiani in tempo reale

App web minimalista per consultare lo stato dei treni italiani in tempo reale, usando le API non ufficiali di ViaggiaTreno.

## Funzionalità

- **Ricerca per stazione**: autocompletamento del nome, elenco partenze/arrivi con ritardo e binario
- **Ricerca per numero treno**: dettaglio completo con tutte le fermate, ritardo per ogni stazione, posizione del treno
- **Auto-refresh**: aggiornamento automatico ogni 60 secondi
- **PWA installabile**: aggiungi alla home su Android per usarla come app

## Avvio rapido

```bash
pip install flask requests
python app.py
```

Poi apri `http://localhost:5000` nel browser.  
Dal telefono: `http://<IP-LOCALE>:5000`

## Installare come app su Android

1. Apri `http://<IP-LOCALE>:5000` in Chrome
2. Menu → "Aggiungi alla schermata Home"
3. L'app apparirà sulla home come una qualunque app nativa

## Note

- Le API di ViaggiaTreno non supportano CORS, perciò il server Flask funge da proxy
- L'app è uno **snapshot istantaneo** — non riceve push notifications
- I dati provengono da `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/`

## API endpoints proxy

| Endpoint | Descrizione |
|----------|-------------|
| `/api/autocompleta/<q>` | Autocompletamento nome stazione |
| `/api/cercaStazione/<q>` | Cerca stazione (JSON) |
| `/api/partenze/<code>` | Partenze da una stazione |
| `/api/arrivi/<code>` | Arrivi a una stazione |
| `/api/cercaTreno/<num>` | Cerca treno per numero |
| `/api/andamento/<origin>/<num>` | Dettaglio andamento treno |