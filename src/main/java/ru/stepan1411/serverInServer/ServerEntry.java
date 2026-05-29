package ru.stepan1411.serverInServer;

public class ServerEntry {
    private String name;
    private String jar;
    private String directory;
    private int port;
    private String flags;
    private boolean autoStart;
    private boolean autoRestart;
    private int retryCount;
    private String motd;
    private String stopCommand;
    private boolean waitToStop;
    private int wait;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getJar() { return jar; }
    public void setJar(String jar) { this.jar = jar; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getFlags() { return flags; }
    public void setFlags(String flags) { this.flags = flags; }

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

    public boolean isAutoRestart() { return autoRestart; }
    public void setAutoRestart(boolean autoRestart) { this.autoRestart = autoRestart; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getMotd() { return motd; }
    public void setMotd(String motd) { this.motd = motd; }

    public String getStopCommand() { return stopCommand; }
    public void setStopCommand(String stopCommand) { this.stopCommand = stopCommand; }

    public boolean isWaitToStop() { return waitToStop; }
    public void setWaitToStop(boolean waitToStop) { this.waitToStop = waitToStop; }

    public int getWait() { return wait; }
    public void setWait(int wait) { this.wait = wait; }
}
