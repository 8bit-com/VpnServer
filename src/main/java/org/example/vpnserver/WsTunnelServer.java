package org.example.vpnserver;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WsTunnelServer {

    private static final int MAX_PACKET_SIZE = 65535;
    private static final long LOG_FIRST_PACKETS = 30;
    private static final long LOG_EVERY_PACKETS = 500;

    private final TunDevice tunDevice;
    private final AtomicReference<WebSocket> client = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong wsToTunCounter = new AtomicLong();
    private final AtomicLong tunToWsCounter = new AtomicLong();
    private final AtomicLong dropCounter = new AtomicLong();

    @Value("${vpn.ws.enabled:true}")
    private boolean enabled;

    @Value("${vpn.ws.port:18080}")
    private int port;

    public WsTunnelServer(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        if (!enabled || !started.compareAndSet(false, true)) {
            return;
        }

        WebSocketServer server = new WebSocketServer(new InetSocketAddress("0.0.0.0", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                client.set(conn);
                System.out.println("WS client connected: " + conn.getRemoteSocketAddress());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                client.compareAndSet(conn, null);
                System.out.println("WS client closed: code=" + code + " remote=" + remote + " reason=" + reason);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                if (client.get() != conn) {
                    return;
                }

                byte[] packet = new byte[message.remaining()];
                message.get(packet);

                if (!isIpv4(packet) || packet.length > MAX_PACKET_SIZE || !shouldTunnel(packet)) {
                    logDrop("WS -> TUN drop", packet);
                    return;
                }

                long id = wsToTunCounter.incrementAndGet();
                logPacket("WS -> TUN", id, packet);
                tunDevice.writePacket(packet);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                System.out.println("WS error: " + ex.getClass().getName() + ": " + ex.getMessage());
            }

            @Override
            public void onStart() {
                System.out.println("WS VPN SERVER READY ON PORT " + port);
            }
        };

        server.start();

        Thread tunReader = new Thread(this::tunToWs, "tun-to-ws");
        tunReader.setDaemon(true);
        tunReader.start();
    }

    private void tunToWs() {
        while (true) {
            try {
                byte[] packet = tunDevice.readPacket();
                if (!isIpv4(packet) || packet.length > MAX_PACKET_SIZE || !shouldTunnel(packet)) {
                    logDrop("TUN -> WS drop", packet);
                    continue;
                }

                WebSocket conn = client.get();
                if (conn == null || !conn.isOpen()) {
                    continue;
                }

                long id = tunToWsCounter.incrementAndGet();
                logPacket("TUN -> WS", id, packet);
                conn.send(packet);
            } catch (Exception e) {
                System.out.println("tun-to-ws error: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }

    private boolean shouldTunnel(byte[] packet) {
        int src0 = packet[12] & 0xff;
        int src1 = packet[13] & 0xff;
        int dst0 = packet[16] & 0xff;
        int dst1 = packet[17] & 0xff;

        return isAllowedIp(src0, src1) && isAllowedIp(dst0, dst1);
    }

    private boolean isAllowedIp(int b0, int b1) {
        if (b0 == 0 || b0 == 127 || b0 >= 224) {
            return false;
        }
        if (b0 == 169 && b1 == 254) {
            return false;
        }
        return true;
    }

    private void logPacket(String direction, long id, byte[] packet) {
        if (id <= LOG_FIRST_PACKETS || id % LOG_EVERY_PACKETS == 0) {
            System.out.println(direction + " id=" + id + " len=" + packet.length + " " + ipInfo(packet));
        }
    }

    private void logDrop(String direction, byte[] packet) {
        long id = dropCounter.incrementAndGet();
        if (id <= LOG_FIRST_PACKETS || id % LOG_EVERY_PACKETS == 0) {
            if (packet == null) {
                System.out.println(direction + " id=" + id + " null");
            } else if (isIpv4(packet)) {
                System.out.println(direction + " id=" + id + " len=" + packet.length + " " + ipInfo(packet));
            } else {
                System.out.println(direction + " id=" + id + " len=" + packet.length + " non-ipv4");
            }
        }
    }

    private boolean isIpv4(byte[] packet) {
        return packet != null && packet.length >= 20 && ((packet[0] >> 4) & 0x0f) == 4;
    }

    private String ipInfo(byte[] packet) {
        return ip(packet, 12) + " -> " + ip(packet, 16) + " proto=" + (packet[9] & 0xff);
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xff) + "." +
                (data[offset + 1] & 0xff) + "." +
                (data[offset + 2] & 0xff) + "." +
                (data[offset + 3] & 0xff);
    }
}
