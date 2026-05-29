# Server-In-Server

A Velocity plugin that launches and manages Minecraft server instances as child processes directly from the proxy. Configure servers in a single YAML file — the plugin handles starting, stopping, auto-restarting, port assignment, and proxy registration automatically.

## How it works

When Velocity starts, the plugin reads `plugins/server-in-server/config.yml`. For each configured server with `auto-start: true`, it spawns a new Java process running the specified server jar, generates `server.properties` with the correct port, optionally accepts the EULA, registers the server in Velocity's proxy, and adds it to `velocity.toml`. If a server crashes, the plugin can auto-restart it a configurable number of times.

## Quick start

1. Place the plugin jar in `plugins/`
2. Start Velocity — the plugin creates `plugins/server-in-server/config.yml` with a default config
3. Edit `config.yml` to match your server jars and settings
4. Run `/sis reload` in the Velocity console to apply changes

## Configuration

### Global settings (top level)

```yaml
auto-eula: false
lobby-server: "lobby"
```

| Field | Default | Description |
|---|---|---|
| `auto-eula` | `false` | If `true`, creates `eula.txt` with `eula=true` automatically for every server. If `false`, you must accept the EULA manually. |
| `lobby-server` | `""` | Server name to connect players to when they first join the proxy. Leave empty to disable. |

### Server entries

```yaml
servers:
  - name: "lobby"
    jar: "servers/lobby/server.jar"
    directory: "servers/lobby"
    port: 0
    flags: "-Xmx1G -Xms512M"
    auto:
      start: true
      restart: true
      retry-count: 0
    stop:
      command: "stop"
      wait-to-stop: true
      wait: 10
```

| Field | Default | Description |
|---|---|---|
| `name` | — | Server name used in Velocity and for routing |
| `jar` | — | Path to the server jar (relative to the proxy directory, or absolute) |
| `directory` | `"servers/<name>"` | Working directory for the server process |
| `port` | `0` | Server port. `0` = auto-assign (`velocityPort + 2 + index`) |
| `flags` | `"-Xmx1G -Xms512M"` | JVM arguments passed to `java` before `-jar` |
| `auto.start` | `true` | Start this server when Velocity (re)starts |
| `auto.restart` | `false` | Restart the server automatically when it stops/crashes |
| `auto.retry-count` | `0` | Max restart attempts. `0` = infinite restarts |
| `stop.command` | `"stop"` | Command sent via stdin to gracefully stop the server |
| `stop.wait-to-stop` | `true` | If `true`, wait for the process to exit before force killing |
| `stop.wait` | `10` | Seconds to wait before force killing (only if `wait-to-stop` is `true`) |

## Commands

All commands use the `/sis` prefix and can be run from the Velocity console or by players with appropriate permissions.

| Command | Description |
|---|---|
| `/sis start <server>` | Start a configured server |
| `/sis stop <server>` | Gracefully stop a running server |
| `/sis restart <server>` | Stop then start a server |
| `/sis forcestop` | Kill all child processes and shut down Velocity |
| `/sis reload` | Reload config, stop all servers, start auto-start ones |
| `/sis list` | Show all configured servers with their status and ports |
| `/sis com <server> <command>` | Send a console command to a running server |

## Port auto-assignment

If `port` is `0`, the plugin calculates it as:

```
port = velocityBindPort + 2 + serverIndex
```

Where `serverIndex` is the server's position in the `servers` list (0-based).

Example with Velocity on port `25565`:

| Index | Server | Calculated port |
|---|---|---|
| 0 | lobby | 25567 |
| 1 | survival | 25568 |
| 2 | creative | 25569 |

## velocity.toml integration

On every startup and reload, the plugin scans `velocity.toml` for the `[servers]` section and adds or updates entries for all configured servers. Existing entries for other servers are preserved. If the `[servers]` section does not exist, it is created at the end of the file.

## Requirements

- Java 21+
- Velocity 3.5.0-SNAPSHOT (or compatible 3.x)
