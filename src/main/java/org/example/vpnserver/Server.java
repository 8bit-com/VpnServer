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

        byte[] buffer = new byte[65535];

        while (true) {

            DatagramPacket packet =
                    new DatagramPacket(buffer, buffer.length);

            socket.receive(packet);

            String message = new String(
                    packet.getData(),
                    0,
                    packet.getLength()
            );

            System.out.println(
                    packet.getAddress() +
                            ":" +
                            packet.getPort() +
                            " -> " +
                            message
            );

            byte[] response =
                    ("PONG: " + message).getBytes();

            socket.send(
                    new DatagramPacket(
                            response,
                            response.length,
                            packet.getAddress(),
                            packet.getPort()
                    )
            );
        }
    }
}
