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

        while (true) {

            try {

                DatagramPacket packet =
                        new DatagramPacket(
                                new byte[65535],
                                65535
                        );

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

                if (packet.getLength() == 5) {
                    continue;
                }

                byte[] data = new byte[packet.getLength()];

                System.arraycopy(
                        packet.getData(),
                        0,
                        data,
                        0,
                        packet.getLength()
                );

                tunDevice.writePacket(
                        tunDevice.getFd(),
                        data
                );

                System.out.println(
                        "udp -> tun : " +
                                data.length +
                                " bytes"
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}