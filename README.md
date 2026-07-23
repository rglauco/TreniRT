# 🚆 TreniRT

Treni italiani in tempo reale, usando gli stessi dati pubblici di ViaggiaTreno (Trenitalia). Due modi per usarla:

- **[`android/`](android/)** — app nativa Android (Kotlin + Jetpack Compose). Ricerca per stazione (con destinazione opzionale e ricerca automatica di coincidenze/cambio treno) o per numero treno, tema chiaro/scuro, cronologia ricerche, pagina di aiuto in italiano.
- **[`app/`](app/)** — companion web/PWA (Flask), installabile come app anche su Android tramite browser.

Vedi il [CHANGELOG](CHANGELOG.md) per la cronologia delle modifiche.

## Licenza

Distribuito sotto licenza [GNU AGPL-3.0-or-later](LICENSE). In breve: chiunque distribuisca una versione modificata di questo software — anche solo facendola girare come servizio accessibile via rete, senza mai distribuire un binario — deve rendere disponibile il proprio codice sorgente modificato con la stessa licenza.

## Disclaimer

TreniRT è un progetto indipendente e amatoriale, **non affiliato, sponsorizzato o approvato da Trenitalia, RFI o Gruppo FS**. Utilizza gli stessi dati pubblici dell'infrastruttura ViaggiaTreno consultabili dal sito e dall'app ufficiali, ma non è un prodotto ufficiale né ne garantisce l'accuratezza. Il servizio è fornito "così com'è", senza alcuna garanzia — vedi la licenza per i termini completi.
