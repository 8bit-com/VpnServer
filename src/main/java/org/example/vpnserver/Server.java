package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

@Service
@RequiredArgsConstructor
public class Server {

    private static final int PORT = 51888;
    private static final String TUN_NAME = "tun0";
    private static final String TUN_IP = "10.0.0.1/24";
    private static final String VPN_NETWORK = "10.0.0.0/24";

    private final UdpPeers udpPeers;
    private final TunDevice tunDevice;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket(PORT);

        new Thread(() -> listenClients(socket), "udp-listener").start();

        tunDevice.open();

        configureLinuxVpnNetwork();

        new Thread(() -> tunDevice.readPackets(socket), "tun-reader").start();
    }

    private void configureLinuxVpnNetwork() throws Exception {

        String externalInterface = getExternalInterface();

        runCommand("ip", "addr", "flush", "dev", TUN_NAME);
        runCommand("ip", "addr", "add", TUN_IP, "dev", TUN_NAME);
        runCommand("ip", "link", "set", TUN_NAME, "up");

        runCommand("sysctl", "-w", "net.ipv4.ip_forward=1");

        runCommandIgnoreError(
                "iptables",
                "-t",
                "nat",
                "-D",
                "POSTROUTING",
                "-s",
                VPN_NETWORK,
                "-o",
                externalInterface,
                "-j",
                "MASQUERADE"
        );

        runCommand(
                "iptables",
                "-t",
                "nat",
                "-A",
                "POSTROUTING",
                "-s",
                VPN_NETWORK,
                "-o",
                externalInterface,
                "-j",
                "MASQUERADE"
        );

        System.out.println("LINUX VPN NETWORK CONFIGURED: " + TUN_NAME + " " + TUN_IP + ", NAT via " + externalInterface);
    }

    private String getExternalInterface() throws Exception {

        Process process =
                new ProcessBuilder("sh", "-c", "ip route get 8.8.8.8 | sed -n 's/.* dev \\([^ ]*\\).*/\\1/p' | head -n 1")
                        .redirectErrorStream(true)
                        .start();

        String externalInterface;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            externalInterface = reader.readLine();
        }

        int code = process.waitFor();

        if (code != 0 || externalInterface == null || externalInterface.trim().isEmpty()) {
            throw new RuntimeException("Cannot detect external interface");
        }

        return externalInterface.trim();
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

    private void runCommand(String... command) throws Exception {

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException("Command failed, code=" + code + ", command=" + String.join(" ", command));
        }
    }

    private void runCommandIgnoreError(String... command) {

        try {
            Process process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start();

            process.waitFor();
        } catch (Exception ignored) {
        }
    }
}
