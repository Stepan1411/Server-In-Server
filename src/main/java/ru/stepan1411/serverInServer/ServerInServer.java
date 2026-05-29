package ru.stepan1411.serverInServer;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class ServerInServer {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxyServer;

    @Inject
    private CommandManager commandManager;

    private final List<ServerEntry> servers = new ArrayList<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> remainingRetries = new ConcurrentHashMap<>();
    private final Yaml yaml;
    private Path configFile;
    private boolean autoEula;
    private String lobbyServer;
    private int shutdownDelay;

    public ServerInServer() {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        this.yaml = new Yaml(dumperOptions);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Server-In-Server initializing...");

        Path configDir = Path.of("plugins", "server-in-server");
        configFile = configDir.resolve("config.yml");

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            logger.error("Failed to create config directory", e);
            return;
        }

        if (!Files.exists(configFile)) {
            saveDefaultConfig(configFile);
        }

        loadConfig(configFile);
        registerCommand();
        startAllAutoStartServers();
        updateVelocityConfig();
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (lobbyServer == null || lobbyServer.isBlank()) return;

        Player player = event.getPlayer();
        proxyServer.getServer(lobbyServer).ifPresentOrElse(server -> {
            player.createConnectionRequest(server).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    logger.warn("Failed to connect player {} to lobby '{}'",
                        player.getUsername(), lobbyServer);
                }
            });
        }, () -> logger.warn("Lobby server '{}' not found for player {}",
            lobbyServer, player.getUsername()));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Proxy shutting down...");

        if (shutdownDelay > 0) {
            logger.info("Waiting {} seconds before stopping servers...", shutdownDelay);
            try {
                Thread.sleep(shutdownDelay * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("Shutting down all managed servers...");
        stopAllServers();
    }

    private void saveDefaultConfig(Path configFile) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in != null) {
                Files.copy(in, configFile, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Default config saved to {}", configFile);
            }
        } catch (IOException e) {
            logger.error("Failed to save default config", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadConfig(Path configFile) {
        boolean needsRewrite = false;
        try (InputStream in = Files.newInputStream(configFile)) {
            Map<String, Object> data = yaml.load(in);
            if (data == null) return;

            if (!data.containsKey("auto-eula")) {
                data.put("auto-eula", false);
                needsRewrite = true;
            }
            if (!data.containsKey("lobby-server")) {
                data.put("lobby-server", "");
                needsRewrite = true;
            }
            if (!data.containsKey("shutdown")) {
                Map<String, Object> shutdownSection = new java.util.LinkedHashMap<>();
                shutdownSection.put("delay", 5);
                data.put("shutdown", shutdownSection);
                needsRewrite = true;
            }

            Object autoEulaObj = data.get("auto-eula");
            autoEula = autoEulaObj instanceof Boolean && (Boolean) autoEulaObj;

            lobbyServer = (String) data.get("lobby-server");
            if (lobbyServer != null && lobbyServer.isBlank()) lobbyServer = null;

            Map<String, Object> shutdownSection = (Map<String, Object>) data.get("shutdown");
            if (shutdownSection != null) {
                Object delayObj = shutdownSection.get("delay");
                shutdownDelay = delayObj instanceof Number ? ((Number) delayObj).intValue() : 5;
            } else {
                shutdownDelay = 5;
            }

            List<Map<String, Object>> serverList = (List<Map<String, Object>>) data.get("servers");
            if (serverList == null) return;

            for (Map<String, Object> entry : serverList) {
                if (!entry.containsKey("flags")) {
                    entry.put("flags", "-Xmx1G -Xms512M");
                    needsRewrite = true;
                }

                if (!entry.containsKey("auto")) {
                    Map<String, Object> autoSection = new java.util.LinkedHashMap<>();
                    autoSection.put("start", entry.containsKey("auto-start")
                        ? entry.get("auto-start") : true);
                    autoSection.put("restart", entry.containsKey("auto-restart")
                        ? entry.get("auto-restart") : false);
                    autoSection.put("retry-count", entry.containsKey("retry-count")
                        ? entry.get("retry-count") : 0);
                    entry.put("auto", autoSection);
                    entry.remove("auto-start");
                    entry.remove("auto-restart");
                    entry.remove("retry-count");
                    needsRewrite = true;
                }

                if (!entry.containsKey("stop")) {
                    Map<String, Object> stopSection = new java.util.LinkedHashMap<>();
                    stopSection.put("command", entry.containsKey("custom-command")
                        ? entry.get("custom-command") : "stop");
                    stopSection.put("wait-to-stop", entry.containsKey("wait-to-stop")
                        ? entry.get("wait-to-stop") : true);
                    stopSection.put("wait", entry.containsKey("wait")
                        ? entry.get("wait") : 10);
                    entry.put("stop", stopSection);
                    entry.remove("custom-command");
                    entry.remove("wait-to-stop");
                    entry.remove("wait");
                    needsRewrite = true;
                }

                ServerEntry se = new ServerEntry();
                se.setName((String) entry.get("name"));
                se.setJar((String) entry.get("jar"));
                se.setDirectory((String) entry.get("directory"));
                se.setFlags((String) entry.get("flags"));

                Object portObj = entry.get("port");
                se.setPort(portObj instanceof Number ? ((Number) portObj).intValue() : 0);

                Map<String, Object> autoSection = (Map<String, Object>) entry.get("auto");
                if (autoSection != null) {
                    Object startObj = autoSection.get("start");
                    se.setAutoStart(!(startObj instanceof Boolean) || (Boolean) startObj);
                    Object restartObj = autoSection.get("restart");
                    se.setAutoRestart(restartObj instanceof Boolean && (Boolean) restartObj);
                    Object retryObj = autoSection.get("retry-count");
                    se.setRetryCount(retryObj instanceof Number ? ((Number) retryObj).intValue() : 0);
                } else {
                    se.setAutoStart(true);
                    se.setAutoRestart(false);
                    se.setRetryCount(0);
                }

                Map<String, Object> stopSection = (Map<String, Object>) entry.get("stop");
                if (stopSection != null) {
                    se.setStopCommand((String) stopSection.get("command"));
                    Object wtsObj = stopSection.get("wait-to-stop");
                    se.setWaitToStop(!(wtsObj instanceof Boolean) || (Boolean) wtsObj);
                    Object wObj = stopSection.get("wait");
                    se.setWait(wObj instanceof Number ? ((Number) wObj).intValue() : 10);
                } else {
                    se.setStopCommand("stop");
                    se.setWaitToStop(true);
                    se.setWait(10);
                }

                servers.add(se);
            }

            if (needsRewrite) {
                yaml.dump(data, Files.newBufferedWriter(configFile));
                logger.info("Config updated with missing default fields");
            }
        } catch (IOException e) {
            logger.error("Failed to load config", e);
        }
    }

    private void registerCommand() {
        commandManager.register(
            commandManager.metaBuilder("sis").build(),
            new SisCommand(this)
        );
        logger.info("Registered /sis command");
    }

    private void startAllAutoStartServers() {
        int velocityPort = proxyServer.getBoundAddress().getPort();
        logger.info("Velocity port: {}", velocityPort);

        for (int i = 0; i < servers.size(); i++) {
            ServerEntry entry = servers.get(i);
            if (!entry.isAutoStart()) {
                logger.info("Skipping server '{}' (auto-start disabled)", entry.getName());
                continue;
            }
            assignPort(entry, i, velocityPort);
            startServerProcess(entry);
        }
    }

    private void assignPort(ServerEntry entry, int index, int velocityPort) {
        if (entry.getPort() == 0) {
            entry.setPort(velocityPort + 2 + index);
        }
    }

    public List<ServerEntry> getServerEntries() {
        return servers;
    }

    public boolean isServerRunning(String name) {
        Process process = processes.get(name);
        return process != null && process.isAlive();
    }

    private Optional<ServerEntry> findEntry(String name) {
        return servers.stream().filter(s -> s.getName().equals(name)).findFirst();
    }

    public boolean startServer(String name) {
        Optional<ServerEntry> opt = findEntry(name);
        if (opt.isEmpty()) {
            logger.warn("Unknown server '{}'", name);
            return false;
        }

        ServerEntry entry = opt.get();
        int velocityPort = proxyServer.getBoundAddress().getPort();
        int idx = servers.indexOf(entry);
        assignPort(entry, idx, velocityPort);
        return startServerProcess(entry);
    }

    private boolean startServerProcess(ServerEntry entry) {
        String name = entry.getName();
        if (isServerRunning(name)) {
            logger.warn("Server '{}' is already running", name);
            return false;
        }

        String jarPath = entry.getJar();
        if (jarPath == null || jarPath.isBlank()) {
            logger.error("No jar path for server '{}'", name);
            return false;
        }

        String dirPath = entry.getDirectory() != null ? entry.getDirectory() : "servers/" + name;
        int port = entry.getPort();

        logger.info("Starting server '{}' on port {}...", name, port);

        try {
            Path serverDir = Path.of(dirPath);
            Files.createDirectories(serverDir);

            generateServerProperties(serverDir, port);

            if (autoEula) {
                Path eulaFile = serverDir.resolve("eula.txt");
                if (!Files.exists(eulaFile)) {
                    Files.writeString(eulaFile, "eula=true" + System.lineSeparator());
                }
            }

            Path jarAbsolute = Path.of(jarPath);
            if (!jarAbsolute.isAbsolute()) {
                jarAbsolute = Path.of("").toAbsolutePath().resolve(jarPath);
            }

            if (!Files.exists(jarAbsolute)) {
                logger.error("Jar file not found for server '{}': {}", name, jarAbsolute);
                return false;
            }

            String os = System.getProperty("os.name").toLowerCase();
            String javaBin = Path.of(System.getProperty("java.home"), "bin",
                os.contains("win") ? "java.exe" : "java").toString();

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            String flags = entry.getFlags();
            if (flags != null && !flags.isBlank()) {
                cmd.addAll(List.of(flags.split("\\s+")));
            } else {
                cmd.add("-Xmx1G");
                cmd.add("-Xms512M");
            }
            cmd.add("-jar");
            cmd.add(jarAbsolute.toString());
            cmd.add("nogui");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(serverDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            processes.put(name, process);

            ServerInfo serverInfo = new ServerInfo(name,
                InetSocketAddress.createUnresolved("127.0.0.1", port));

            unregisterIfExists(name);
            proxyServer.registerServer(serverInfo);
            logger.info("Server '{}' registered at 127.0.0.1:{}", name, port);

            boolean autoRestart = entry.isAutoRestart();
            int retryCount = entry.getRetryCount();
            if (autoRestart) {
                int effective = retryCount == 0 ? -1 : retryCount;
                remainingRetries.put(name, new AtomicInteger(effective));
            }

            Thread consoleThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[{}] {}", name, line);
                    }
                } catch (IOException ignored) {
                }
                int exitCode;
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException e) {
                    exitCode = -1;
                    Thread.currentThread().interrupt();
                }
                logger.info("Server '{}' exited with code {}", name, exitCode);
                processes.remove(name, process);

                if (autoRestart) {
                    handleAutoRestart(entry);
                }
            }, "console-" + name);
            consoleThread.setDaemon(true);
            consoleThread.start();

            return true;

        } catch (IOException e) {
            logger.error("Failed to start server '{}'", name, e);
            return false;
        }
    }

    private void unregisterIfExists(String name) {
        proxyServer.getServer(name).ifPresent(registered ->
            proxyServer.unregisterServer(registered.getServerInfo()));
    }

    private void handleAutoRestart(ServerEntry entry) {
        String name = entry.getName();
        AtomicInteger rem = remainingRetries.get(name);
        if (rem == null) return;

        if (rem.get() == -1 || rem.getAndDecrement() > 1) {
            logger.info("Auto-restarting server '{}'...", name);

            ServerInfo serverInfo = new ServerInfo(name,
                InetSocketAddress.createUnresolved("127.0.0.1", entry.getPort()));
            unregisterIfExists(name);
            proxyServer.registerServer(serverInfo);

            startServerProcess(entry);
        } else {
            logger.info("Server '{}' auto-restart retries exhausted", name);
            remainingRetries.remove(name);
            unregisterIfExists(name);
        }
    }

    public void stopServer(String name) {
        Process process = processes.get(name);
        if (process == null || !process.isAlive()) {
            logger.warn("Server '{}' is not running", name);
            return;
        }

        remainingRetries.remove(name);

        ServerEntry entry = findEntry(name).orElse(null);
        String stopCmd = (entry != null && entry.getStopCommand() != null)
            ? entry.getStopCommand() : "stop";
        boolean waitToStop = entry == null || entry.isWaitToStop();
        int waitSec = (entry != null) ? entry.getWait() : 10;

        logger.info("Stopping server '{}' with command '{}'...", name, stopCmd);
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((stopCmd + "\n").getBytes());
            stdin.flush();
        } catch (IOException e) {
            logger.warn("Could not send stop command to server '{}'", name);
        }

        if (waitToStop) {
            try {
                if (!process.waitFor(waitSec, TimeUnit.SECONDS)) {
                    logger.warn("Server '{}' did not stop in time, force killing...", name);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        processes.remove(name);
        unregisterIfExists(name);
        logger.info("Server '{}' removed from proxy", name);
    }

    public void sendCommand(String name, String command) {
        Process process = processes.get(name);
        if (process == null || !process.isAlive()) {
            logger.warn("Server '{}' is not running, cannot send command", name);
            return;
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((command + "\n").getBytes());
            stdin.flush();
            logger.info("Sent command '{}' to server '{}'", command, name);
        } catch (IOException e) {
            logger.warn("Failed to send command to server '{}'", name, e);
        }
    }

    public void restartServer(String name) {
        stopServer(name);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        startServer(name);
    }

    private void stopAllServers() {
        for (String name : List.copyOf(processes.keySet())) {
            stopServer(name);
        }
    }

    public void forceStopAll() {
        logger.info("Force stopping all servers...");
        remainingRetries.clear();
        for (String name : List.copyOf(processes.keySet())) {
            Process process = processes.get(name);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            processes.remove(name);
            unregisterIfExists(name);
            logger.info("Server '{}' force stopped", name);
        }
    }

    public void forceStopAndShutdown() {
        forceStopAll();
        logger.info("Shutting down Velocity...");
        proxyServer.shutdown();
    }

    public void reloadConfig() {
        logger.info("Reloading config...");
        stopAllServers();
        remainingRetries.clear();
        servers.clear();
        loadConfig(configFile);
        startAllAutoStartServers();
        updateVelocityConfig();
        logger.info("Config reloaded");
    }

    private void updateVelocityConfig() {
        Path velocityConfig = Path.of("velocity.toml");
        if (!Files.exists(velocityConfig)) return;

        try {
            List<String> lines = Files.readAllLines(velocityConfig);
            List<String> newLines = new ArrayList<>();
            boolean inServersSection = false;
            boolean serversSectionExists = false;
            boolean hasChanges = false;

            Map<String, String> ourServers = new LinkedHashMap<>();
            for (ServerEntry entry : servers) {
                if (entry.getPort() > 0) {
                    ourServers.put(entry.getName(), "127.0.0.1:" + entry.getPort());
                }
            }

            Set<String> foundServers = new HashSet<>();

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.equals("[servers]")) {
                    inServersSection = true;
                    serversSectionExists = true;
                    newLines.add(line);
                    continue;
                }

                if (inServersSection && trimmed.startsWith("[")) {
                    inServersSection = false;
                    for (Map.Entry<String, String> server : ourServers.entrySet()) {
                        if (!foundServers.contains(server.getKey())) {
                            newLines.add("  " + server.getKey() + " = \"" + server.getValue() + "\"");
                            hasChanges = true;
                        }
                    }
                }

                if (inServersSection && trimmed.contains("=")) {
                    String[] parts = trimmed.split("=", 2);
                    String name = parts[0].trim();
                    String value = parts[1].trim().replaceAll("[\"']", "");
                    if (ourServers.containsKey(name)) {
                        newLines.add("  " + name + " = \"" + ourServers.get(name) + "\"");
                        foundServers.add(name);
                        hasChanges = true;
                        continue;
                    }
                }

                newLines.add(line);
            }

            if (inServersSection) {
                for (Map.Entry<String, String> server : ourServers.entrySet()) {
                    if (!foundServers.contains(server.getKey())) {
                        newLines.add("  " + server.getKey() + " = \"" + server.getValue() + "\"");
                        hasChanges = true;
                    }
                }
            }

            if (!serversSectionExists && !ourServers.isEmpty()) {
                newLines.add("");
                newLines.add("[servers]");
                for (Map.Entry<String, String> server : ourServers.entrySet()) {
                    newLines.add("  " + server.getKey() + " = \"" + server.getValue() + "\"");
                }
                hasChanges = true;
            }

            if (hasChanges) {
                Files.write(velocityConfig, newLines);
                logger.info("Updated velocity.toml with server entries");
            }
        } catch (IOException e) {
            logger.error("Failed to update velocity.toml", e);
        }
    }

    private void generateServerProperties(Path serverDir, int port) throws IOException {
        Path propsFile = serverDir.resolve("server.properties");
        if (Files.exists(propsFile)) {
            List<String> lines = new ArrayList<>(Files.readAllLines(propsFile));
            boolean portFound = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("server-port=")) {
                    lines.set(i, "server-port=" + port);
                    portFound = true;
                    break;
                }
            }
            if (!portFound) {
                lines.add("server-port=" + port);
            }
            if (lines.stream().noneMatch(l -> l.startsWith("online-mode="))) {
                lines.add("online-mode=false");
            }
            Files.write(propsFile, lines);
        } else {
            Files.writeString(propsFile,
                "server-port=" + port + System.lineSeparator()
                + "online-mode=false" + System.lineSeparator()
                + "motd=A Server-In-Server managed server" + System.lineSeparator());
        }
    }
}
