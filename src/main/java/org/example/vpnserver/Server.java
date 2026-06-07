package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

@Service
@RequiredArgsConstructor
public class Server {

    private static final int PORT = 51888;

    private final UdpPeers udpPeers;
    private final TunDevice tunDevice;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket(PORT);

        new Thread(() -> listenClients(socket), "udp-listener").start();

        tunDevice.start(socket);
    }

    private void listenClients(DatagramSocket socket) {

        byte[] buffer = new byte[65535];

        while (true) {
            try {
                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);

                udpPeers.add(
                        new InetSocketAddress(
                                packet.getAddress(),
                                packet.getPort()
                        )
                );

                System.out.println(
                        "udp from " +
                                packet.getAddress().getHostAddress() +
                                ":" +
                                packet.getPort() +
                                " size=" +
                                packet.getLength()
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}