package directory;

import java.net.InetAddress;

public class ServerInfo {
    InetAddress address;
    int tcpPort;
    int syncPort;
    int dbVersion;
    long lastHeartbeat;
    long registrationTime;

    public ServerInfo(InetAddress address, int tcpPort, int syncPort, int dbVersion, long lastHeartbeat,
            long registrationTime) {
        this.address = address;
        this.tcpPort = tcpPort;
        this.syncPort = syncPort;
        this.dbVersion = dbVersion;
        this.lastHeartbeat = lastHeartbeat;
        this.registrationTime = registrationTime;
    }
}
