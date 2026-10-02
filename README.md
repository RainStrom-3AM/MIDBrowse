# MIDBrowse

![AppIcon](/res/Icon.png)

A small web browser for J2ME (Java ME) phones — CLDC 1.1 / MIDP 2.0. The phone
talks to a tiny self-hosted server on your PC; the server fetches modern websites,
strips them down to plain text + links, and the app renders them on a classic
feature-phone screen.

## How it works

```
phone (J2ME)  --HTTP-->  your PC (Node.js server)  --HTTP-->  the web
MIDBrowse.jar            server/server.js :8765
```

The server does the heavy lifting (TLS, redirects, modern HTML, charsets) and
speaks a tiny line protocol the phone can parse in a few KB of RAM.

## Features

- **Browse** — any URL rendered as wrapped text, links underlined, moving cursor
- **Jump** — press digit keys `1-9`/`0` to jump straight to a visible link
- **Search** — DuckDuckGo results with Bing / Wikipedia fallbacks
- **History** — Back through visited pages, Reload, Open URL…
- **Setup once** — server address is persisted on the phone (RMS); first run asks for it
- **Guarded server** — `/page` and `/search` require the app's `X-MIDBROWSE: 1`
  header, so the server can't be trivially used as an open proxy by browsers

## Quick start

### 1. Start the server (your PC)

Requirements: **Node.js 18+** — zero dependencies, nothing to install.

```
cd server
start.bat          (Windows)
node server.js     (any OS)
```

It prints the addresses to point the phone at:

```
MIDBrowse server: listening on 0.0.0.0:8765
  LAN   http://192.168.x.x:8765
  Public http://x.x.x.x:8765
```

Opening `http://<pc-ip>:8765` in a desktop browser shows a status page.
Use `PORT=9000 node server.js` to change the port.

### 2. Install the app on the phone

Download `MIDBrowse.jar` + `MIDBrowse.jad` from
[Releases](https://github.com/RainStrom-3AM/MIDBrowse/releases) and install them
on a J2ME device (keep both files together), or load the jar in an emulator
such as KEmulator / MicroEmulator.

### 3. Point the app at the server

- First run prompts for the server address; change it later via **Menu → Server address...**
- **Same network:** use the printed LAN address, e.g. `http://192.168.1.5:8765`
- **Over the internet (RDP / cloud VM):** use the printed public address and allow
  inbound **TCP 8765** — in Windows Firewall (allow Node.js when prompted) and, on a
  cloud VM, in the network security group (NSG) inbound rules
- **Menu → Test connection** should answer `Connected`

## Controls

| Key | Action |
| --- | --- |
| Up / Down | move the cursor |
| Left / Right | scroll the page |
| Select | open the highlighted link / confirm in menus |
| `1`–`9`, `0` | jump to the nth visible link |
| Back (left softkey) | history back |
| Menu (right softkey) | Open URL…, Search…, Reload, Back, Server address…, Test connection, Exit |

## Build

macOS / Linux, or Windows via Git Bash:

```bash
./build.sh
```

Requires **JDK 8** (the last toolchain emitting CLDC-compatible bytecode):

```bash
winget install EclipseAdoptium.Temurin.8.JDK
```

Output: `dist/MIDBrowse.jar` + `dist/MIDBrowse.jad`. See `build.sh` for the full
pipeline (compile → package → ProGuard → JAD).

## Protocol (MIDBROWSE/1)

Plain-text responses, tab-separated fields:

```
MIDBROWSE/1
T<TAB>page title
U<TAB>final url after redirects
X<TAB>a line of text
L<TAB>idx<TAB>url<TAB>label
E<TAB>error message
.                 (end marker)
```

Endpoints:

- `GET /` — HTML status page (open)
- `GET /ping` — connectivity check (open)
- `GET /page?url=<enc>` — fetch + simplify a page (header `X-MIDBROWSE: 1` required)
- `GET /search?q=<enc>` — search results (same header)

## Tech stack

- Java ME (J2ME), MIDP 2.0 / CLDC 1.1; RMS for the saved server address
- Node.js ≥ 18, zero npm dependencies (built-in `http` + `fetch`)

## License

MIT — see [LICENSE](LICENSE).
