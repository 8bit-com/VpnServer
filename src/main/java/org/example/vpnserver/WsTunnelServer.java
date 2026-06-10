package org.example.vpnserver;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WsTunnelServer {

    private static final int MAX_PACKET_SIZE = 65535;

    private final TunDevice tunDevice;
    private final AtomicReference<WebSocket> client = new AtomicReference<>();

    @Value("${vpn.ws.enabled:true}")
    private boolean enabled;

    @Value("${vpn.ws.port:18080}")
    private int port;

    public WsTunnelServer(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }

        WebSocketServer server = new WebSocketServer(new InetSocketAddress("0.0.0.0", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                WebSocket old = client.getAndSet(conn);
                if (old != null && old.isOpen()) {
                    old.close();
                }
                System.out.println("WS client connected: " + conn.getRemoteSocketAddress());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                client.compareAndSet(conn, null);
                System.out.println("WS client closed: " + code + " " + reason);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                byte[] packet = new byte[message.remaining()];
                message.get(packet);

                if (packet.length == 0 || packet.length > MAX_PACKET_SIZE || !isIpv4(packet)) {
                    return;
                }

                tunDevice.writePacket(packet);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                System.out.println("WS error: " + ex.getMessage());
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
                if (packet == null || !isIpv4(packet)) {
                    continue;
                }

                WebSocket conn = client.get();
                if (conn != null && conn.isOpen()) {
                    conn.send(packet);
                }
            } catch (Exception e) {
                System.out.println("tun-to-ws error: " + e.getMessage());
            }
        }
    }

    private boolean isIpv4(byte[] packet) {
        return packet.length >= 20 && ((packet[0] >> 4) & 0x0f) == 4;
    }
}
