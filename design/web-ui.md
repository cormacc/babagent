# Web UI Design

## Overview

A browser-based interface for babagent, served on `localhost:6767`. Reuses the
existing session model from `server.clj` with an HTTP + WebSocket layer on top.

## Architecture

```
[Terminal TUI]  ──┐
                  ├── server.clj (session state atom)
[Web browser]   ──┘         │
    ↕ WebSocket         add-watch
[babagent.web HTTP server]
```

### Server side (`babagent.web`)

- httpkit HTTP server on port 6767
- Creates one babagent session via `server/create-session!` at startup
- Watches `server/!server-state` with `add-watch`; broadcasts JSON to all
  connected WebSocket clients on every session change (captures mid-loop tool
  events automatically via the existing `notify!` mechanism)
- Routes: `GET /` and `/web/*` → static assets, `GET /js/*` → compiled CLJS,
  `GET /ws` → WebSocket upgrade
- Processes submit actions via `server/submit!` (runs server effects in a
  future; returns client-effects synchronously)

### WebSocket protocol

| Direction       | Payload                                  |
|-----------------|------------------------------------------|
| server → client | `{"type":"session","session":{...}}`     |
| client → server | `{"type":"submit","text":"..."}`         |

### Session JSON serialization

- `:role` keywords converted via `name` (`:tool-result` → `"tool-result"`)
- `:tool-result` messages pre-render call/result text server-side using
  `tools/render-tool-call` and `tools/render-tool-result` — avoids duplicating
  rendering logic in ClojureScript; CLJS receives plain `:call-text`/`:result-text` strings
- `:available-models` stripped (not needed by web client)

### Frontend

ClojureScript:
- **Replicant** (`no.cjohansen/replicant 2025.12.1`) — pure function from state to hiccup
- **Nexus** (`no.cjohansen/nexus 2025.11.1`) — data-driven action/effect dispatch
  via global registry (`nexus.registry`)

Store atom shape:
```clojure
{:session    {...}   ; current session pushed from server
 :input      ""      ; text input value
 :connected? false}  ; WebSocket connection status
```

Render loop: `add-watch` on store atom → `replicant.dom/render`.

## File layout

```
src/babagent/web.clj              ; HTTP/WS server + session bridge
src/babagent/ui/web/core.cljs     ; entry point, store, render loop
src/babagent/ui/web/views.cljs    ; Replicant hiccup views
src/babagent/ui/web/nexus.cljs    ; Nexus actions/effects/placeholders
resources/public/web/index.html   ; HTML shell
resources/public/web/app.css      ; dark theme (Catppuccin Mocha palette)
```

## Startup

```bash
bb task web                 # start web server on :6767 (bb task defined in bb.edn)
bb -m babagent.web          # alternative direct invocation
npm run build:web-ui        # compile ClojureScript (one-shot)
npm run watch:web-ui        # ClojureScript watch mode for development
```

## Design decisions

| Decision           | Choice                    | Reason                                                              |
|--------------------|---------------------------|---------------------------------------------------------------------|
| HTTP server        | httpkit 2.8.0             | Native WebSocket support; single dep; works as Babashka JVM dep     |
| Transport          | WebSocket (not SSE)       | Bidirectional; clean uniform protocol even if upstream traffic is light |
| State broadcast    | `add-watch` on `!server-state` | Any change (including mid-loop tool events via `notify!`) automatically reaches all WS clients; no callback threading required |
| Tool rendering     | Server-side pre-render    | Keeps `tools/render-*` functions in one place; CLJS receives plain strings |
| Nexus style        | Registry (global)         | Less wiring for a single-session app                                |
| CSS palette        | Catppuccin Mocha          | Matches TUI ANSI theme; hand-rolled — no build-time CSS framework dep |
| Markdown rendering | `white-space: pre-wrap`   | Consistent with TUI display; avoids adding a JS markdown parser dep |
