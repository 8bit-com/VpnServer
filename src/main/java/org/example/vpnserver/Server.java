package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

@Service
@RequiredArgsConstructor
public class Server {

    private final TunDevice tunDevice;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        tunDevice.start();
    }
}