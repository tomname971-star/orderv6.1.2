# Order Plugin 2.0.0

Update deines Order-Plugins. Nutzt weiterhin `money.jar` als externe Dependency
(keine Preisliste, Verkäufer setzen ihren Preis wie bisher selbst).

## Was wurde gemacht

### Kritische Bugfixes
- **Item-Dupe-Exploit behoben**: Vorher wurde beim Erstellen einer Order ein
  komplett neuer `ItemStack` "aus dem Nichts" erzeugt - der Verkäufer hat NIE
  wirklich Items aus seinem Inventar verloren. Jetzt werden die Items erst
  entfernt, wenn Menge UND Preis final bestätigt wurden, und zwar exakt aus
  dem echten Spieler-Inventar (`takeFromInventory`).
- **Verkäufer wurde nie bezahlt**: `withdraw()` beim Käufer war da, aber nirgends
  ein `deposit()` für den Verkäufer. Jetzt wird der Verkäufer sofort bezahlt
  (`payoutSeller`), oder - falls offline - der Betrag in `order_pending_payouts`
  zwischengespeichert und automatisch beim nächsten Login gutgeschrieben.
- **Claim-Orders waren nicht auf den Besitzer beschränkt**: Jeder Spieler
  konnte im "Claim"-Menü fremde abgelaufene Orders kostenlos einsacken.
  Jetzt: `getClaimableOrders(uuid)` ist strikt auf den jeweiligen Verkäufer
  gefiltert.
- **Race Condition Cleanup vs. Claim**: Der alte Cleanup-Task hat abgelaufene
  Orders (die gleichzeitig als "claimbar" galten) nach 20 Minuten sofort
  gelöscht - Verkäufer konnten ihre Items so schlicht verlieren. Jetzt gibt es
  zwei Phasen: ACTIVE -> EXPIRED (claimbar) -> erst nach zusätzlicher
  Karenzzeit (`claim-grace-days`, Standard 7 Tage) wird endgültig gelöscht.
- Eigene aktive Order stornieren gab dem Spieler nie sein Item zurück -
  jetzt schon (`handleMyOrdersClick`).
- Käufer konnte theoretisch die eigene Order "annehmen" - jetzt blockiert.

### Neue Features
- **SQLite-Datenbank** statt Java-Objekt-Serialisierung in eine `.dat`-Datei
  (robuster, überlebt Server-/Minecraft-Versionswechsel besser). MySQL ist
  ebenfalls unterstützt und per `config.yml` (`database.type: mysql`)
  umschaltbar, ohne Codeänderung.
- Verbindungspool über HikariCP, alle DB-Schreibvorgänge laufen asynchron
  (kein Haupt-Thread-Blocking), die GUI selbst arbeitet auf einem
  In-Memory-Cache und bleibt dadurch sofort responsiv.
- Physisches **Such-Buch**: `/orders searchbook` gibt ein Buch, das beim
  Rechtsklick direkt die Item-Suche öffnet (zusätzlich zur Such-Schaltfläche
  im GUI-Menü).
- Konfigurierbare Limits: `max-active-orders-per-player`, `min-price`,
  `max-price`, `max-order-amount`, `order-duration-hours`, `claim-grace-days`.
- Vollständiges Permission-System (siehe unten) statt komplett offenem Zugriff.
- Item-Legitimitäts-Filter (`ItemUtil.isAllowed`) verhindert, dass Command-
  Blocks, Barrier, Spawner, Structure-Blocks, Spawn-Eggs usw. jemals gelistet
  werden können - auch als serverseitiger Hard-Check direkt vor dem Erstellen
  einer Order, nicht nur beim Durchblättern im GUI.
- `/orders reload` und `/orders clear` für Admins.

## Permissions

| Permission      | Standard | Beschreibung                                    |
|-----------------|----------|--------------------------------------------------|
| `order.use`     | true     | `/orders` Basisbefehl, Liste ansehen             |
| `order.create`  | true     | Orders erstellen                                 |
| `order.claim`   | true     | Eigene abgelaufene Orders zurückholen            |
| `order.admin`   | op       | `/orders reload`, `/orders clear`                |
| `order.*`       | op       | Alle Order-Permissions                           |

## Build

Benötigt Internetzugriff für Maven (Paper-API, SQLite/MySQL/HikariCP-Downloads).

```bash
cd order-plugin
mvn clean package
```

Das fertige Plugin liegt danach unter `target/Order-2.0.0.jar`.

`money.jar` liegt bereits unter `libs/money.jar` und wird nur als
Compile-Dependency verwendet (nicht mit ins fertige Jar gepackt) - das echte
`money`-Plugin muss weiterhin separat auf dem Server installiert sein
(steht auch so in `plugin.yml`: `depend: [money]`).

## Installation

1. `Order-2.0.0.jar` in den `plugins/`-Ordner kopieren.
2. Sicherstellen, dass `money.jar` (dein Economy-Plugin) ebenfalls in
   `plugins/` liegt.
3. Server starten. Beim ersten Start wird `config.yml` mit SQLite als
   Standard-Datenbank erzeugt (`plugins/Order/orders.db`) - keine weitere
   Einrichtung nötig.
4. Für MySQL: `database.type: mysql` setzen und die Zugangsdaten unter
   `database.mysql` eintragen, dann Server neu starten.

## Befehle

- `/orders` - Order-Liste öffnen
- `/orders create` - neue Order erstellen (Kategorie/Suche -> Menge -> Preis)
- `/orders my` - eigene aktive Orders (Klick = stornieren, Item zurück)
- `/orders claim` - eigene abgelaufene, unverkaufte Orders zurückholen
- `/orders searchbook` - Such-Buch erhalten (Rechtsklick = Suche öffnen)
- `/orders reload` - Config neu laden (Admin)
- `/orders clear` - alle Orders löschen (Admin)
