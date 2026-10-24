# Aesir Addon (Meteor Client)

Meteor Client 1.21.11 add-on for **AesirMC** — four modules in a single jar.

| Module | Purpose |
|--------|---------|
| **AesirSpoof** | Authenticates with the Aesir backend, spoofs client brand, auto-logs in, reports presence & mod list via WebSocket |
| **AesirRadar** | Reads the AesirMC Towny web-map API (`/townymap/tiles/players.json`) and renders live nametags for nearby players (name, distance, health, rank color/title) |
| **AnchorBind** | Binds a key to set/teleport to an anchor position (persists across sessions) |
| **ServerPackSaver** | Caches server resource packs so you don't re-download on reconnect |

---

## Requirements

- Minecraft **1.21.11**
- Fabric Loader
- [Meteor Client 1.21.11](https://github.com/MeteorDevelopment/meteor-client) (named/dev jar in `libs/`)

---

## Installation

1. Drop `build/libs/aesir-addon-1.0.0.jar` into your `mods/` folder.
2. Launch the game → **Modules → Aesir** category.
3. Enable the modules you want.

---

## Module Details

### AesirSpoof
Authenticates against the Aesir backend and mimics official AesirClient traffic.

**Features**
- Username/password login with JWT token refresh
- WebSocket presence (heartbeat, room join/leave, member list)
- Periodic mod-list reporting (installed or official AesirLauncher list)
- Client brand spoofing (`AesirClient` by default)
- Auto-login: detects `/login <password>` prompts and sends the command
- Badge system: shows AesirClient badge + mobile icon next to names in-world and in tab list

**Settings**
| Setting | Description |
|---------|-------------|
| `username` | Aesir account username |
| `password` | Aesir account password |
| `api-url` | Backend REST base URL (default: `http://91.124.63.116:3456/api`) |
| `ws-url` | WebSocket URL (auto-derived from API URL if empty) |
| `auto-login` | Send `/login <password>` when server asks (default: true) |
| `presence` | Send periodic presence updates (default: true) |
| `report-mods` | Report mod list to backend (default: true) |
| `launcher-mods` | Use official AesirLauncher mod list instead of installed mods (default: true) |
| `content-url` | URL for official mod list JSON (default: `https://storage.aesirmc.com/files/aesircontent.json`) |
| `spoof-brand` | Replace outgoing brand payload with custom brand (default: true) |
| `brand` | Brand string to send (default: `AesirClient`) |
| `badges` | Show AesirClient badges in nametags/tab (default: true) |
| `debug` | Log auth/network activity to chat (default: false) |

**Badge behavior**
- When `badges` is enabled and WebSocket is connected, the module receives room member lists and badge levels from the backend.
- Mobile players get a phone icon (📱).
- AesirClient users get a colored badge (level 0–30) based on their rank.
- Works in-world (via `EntityRendererMixin`) and in the player list (via `PlayerListHudMixin`).

---

### AesirRadar
Live radar using the AesirMC Towny web-map API.

**Data sources**
- `/townymap/tiles/players.json` — player positions (name, world, x/y/z, health, armor) every `update-interval` ms
- `/api/map/player-ranks` — rank colors (hex) per player, refreshed ~every minute
- `/api/map/player-titles` — rank titles (prefixes) per player, refreshed ~every minute

**Settings**
| Setting | Description |
|---------|-------------|
| `api-url` | Towny map base URL (default: `https://towny.aesirmc.com`) |
| `range` | Max render distance in blocks (default: `5000`) |
| `update-interval` | Fetch interval in ms (default: `500`) |
| `world` | Target world: `auto` (matches your dimension), or explicit name (e.g. `dunya`, `spawn`) |
| `distance` | Show distance suffix (e.g. `1324m`) |
| `health` | Show health value |
| `rank-color` | Apply rank color to name |
| `rank-name` | Prefix rank title to name |
| `scale` | Nametag scale |
| `text-color` | Base text color |
| `distance-color` | Distance text color |
| `health-color` | Health text color |
| `background` | Background color/alpha behind text |

**Rendering**
- Runs on a background thread — zero main-thread blocking.
- Only renders players in the same dimension within `range`.
- Nametags are projected to screen space relative to camera.
- Players not updated for >60 s are considered offline and hidden.

---

### AnchorBind
Simple position anchor system.

**Usage**
1. Bind a key in Controls → **Aesir → AnchorBind**.
2. Press the key once to **set** the anchor (saves X/Y/Z/Yaw/Pitch/Dimension).
3. Press again to **teleport** back to the anchor.
4. Anchor persists across game restarts (stored in Meteor's config).

**Settings**
- `key` — Keybind to set/teleport (default: unbound)

---

### ServerPackSaver
Caches server resource packs locally to avoid re-downloading on every join.

**How it works**
- Intercepts `ResourcePackSendS2CPacket` from the server.
- If the pack UUID + hash matches a cached entry, cancels the packet and loads the local copy.
- On successful download, saves the pack to `<minecraft-dir>/aesir-serverpacks/<uuid>.zip`.

**Settings**
- `enabled` — Toggle the feature (default: true)
- `max-packs` — Maximum cached packs (default: `50`, oldest evicted first)

**Commands**
- `/serverpacks list` — List cached packs
- `/serverpacks clear` — Delete all cached packs

---

## Building

```bash
./gradlew build
```

Output: `build/libs/aesir-addon-1.0.0.jar`

**Note on Meteor Client dependency**  
The `libs/meteor-client-1.21.11-dev.jar` is a **named** (not remapped) dev build from the `1.21.11` branch. The official Maven snapshot is remapped, so a local dev jar is required.

To update:
```bash
git clone -b 1.21.11 https://github.com/MeteorDevelopment/meteor-client
cd meteor-client
./gradlew jar
cp build/devlibs/meteor-client-*-dev.jar /path/to/this/project/libs/
```

---

## Project Structure

```text
src/main/java/com/aesir/addon
├── AesirAddon.java                      → Addon entry point, category registration
├── AesirApi.java                        → HTTP client + mini JSON parser (players/ranks/titles/markers)
├── AesirBadges.java                     → Badge state + rendering helpers (used by AesirSpoof)
├── modules/
│   ├── AesirSpoof.java                  → Auth, WS, brand spoof, auto-login, mod reporting, badges
│   ├── AesirRadar.java                  → API fetch + nametag rendering
│   ├── AnchorBind.java                  → Position anchor keybind
│   └── ServerPackSaver.java             → Resource pack cache + intercept
└── mixin/
    ├── EntityRendererMixin.java         → In-world badge rendering (AesirSpoof)
    ├── PlayerListHudMixin.java          → Tab list badge rendering (AesirSpoof)
    ├── PlayerListHudAccessor.java       → Access private header/footer (AesirSpoof)
    ├── ServerResourcePackManagerMixin.java  → Remember pack hashes (ServerPackSaver)
    └── ClientCommonNetworkHandlerMixin.java → Use cached pack on join (ServerPackSaver)
```

---

## Configuration Files

All module settings are stored in Meteor's config directory:
```
.minecraft/meteor-client/modules/aesir/
├── aesir-spoof.json
├── aesir-radar.json
├── anchor-bind.json
└── server-pack-saver.json
```

Server packs cache:
```
.minecraft/aesir-serverpacks/
├── <uuid>.zip
└── index.json   (metadata: url, hash, timestamp)
```

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| `AesirSpoof` won't connect | Check `api-url`/`ws-url` reachable; verify credentials; enable `debug` to see logs |
| Badges not showing | Ensure `badges` setting is on, WebSocket connected (`connected` state), and you're on an AesirMC server |
| `AesirRadar` shows no one | Confirm `api-url` returns valid JSON at `/townymap/tiles/players.json`; check `world` setting matches dimension |
| Pack not loading | Delete `<minecraft>/aesir-serverpacks/` and rejoin; check `ServerPackSaver.enabled` |
| Build fails (Meteor jar) | Replace `libs/meteor-client-1.21.11-dev.jar` with a fresh named dev build from the `1.21.11` branch |

---

## License

MIT — see [LICENSE](LICENSE).