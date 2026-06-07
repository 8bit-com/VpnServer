package org.example.vpnserver;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

@Service
public class Server {

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket(51888);

        System.out.println("VPN SERVER STARTED");

        while (true) {

            byte[] buffer = new byte[65535];

            DatagramPacket packet =
                    new DatagramPacket(buffer, buffer.length);

            socket.receive(packet);

            System.out.println(
                    "RECV "
                            + packet.getAddress()
                            + ":"
                            + packet.getPort()
                            + " len="
                            + packet.getLength()
            );

            DatagramPacket response =
                    new DatagramPacket(
                            packet.getData(),
                            packet.getLength(),
                            packet.getAddress(),
                            packet.getPort()
                    );

            socket.send(response);
        }
    }
}
