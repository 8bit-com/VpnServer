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
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class Server {

    private static final int PORT = 51888;
    private static final int LOG_EVERY_PACKETS = 50;
    private static final int MTU = 1200;
    private static final int TCP_MSS = 1160;
    private static final String TUN_NAME = "tun0";
    private static final String TUN_IP = "10.0.0.1/24";
    private static final String VPN_NETWORK = "10.0.0.0/24";

    private final AtomicLong udpToTunCounter = new AtomicLong();
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
        runCommand("ip", "link", "set", "dev", TUN_NAME, "mtu", String.valueOf(MTU));
        runCommand("ip", "link", "set", TUN_NAME, "up");

        runCommand("sysctl", "-w", "net.ipv4.ip_forward=1");

        deleteForwardRules(externalInterface);
        addForwardRules(externalInterface);

        runCommandIgnoreError("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", VPN_NETWORK, "-o", externalInterface, "-j", "MASQUERADE");
        runCommand("iptables", "-t", "nat", "-A", "POSTROUTING", "-s", VPN_NETWORK, "-o", externalInterface, "-j", "MASQUERADE");

        System.out.println("LINUX VPN NETWORK CONFIGURED: " + TUN_NAME + " " + TUN_IP + ", MTU " + MTU + ", MSS " + TCP_MSS + ", NAT via " + externalInterface);
    }

    private void deleteForwardRules(String externalInterface) {

        runCommandIgnoreError("iptables", "-D", "FORWARD", "-i", TUN_NAME, "-o", externalInterface, "-j", "ACCEPT");
        runCommandIgnoreError("iptables", "-D", "FORWARD", "-i", externalInterface, "-o", TUN_NAME, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        runCommandIgnoreError("iptables", "-t", "mangle", "-D", "FORWARD", "-i", TUN_NAME, "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--set-mss", String.valueOf(TCP_MSS));
    }

    private void addForwardRules(String externalInterface) throws Exception {

        runCommand("iptables", "-A", "FORWARD", "-i", TUN_NAME, "-o", externalInterface, "-j", "ACCEPT");
        runCommand("iptables", "-A", "FORWARD", "-i", externalInterface, "-o", TUN_NAME, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        runCommand("iptables", "-t", "mangle", "-A", "FORWARD", "-i", TUN_NAME, "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--set-mss", String.valueOf(TCP_MSS));
    }

    private String getExternalInterface() throws Exception {

        Process process = new ProcessBuilder("sh", "-c", "ip route get 8.8.8.8 | sed -n 's/.* dev \\([^ ]*\\).*/\\1/p' | head -n 1")
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

                DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);

                socket.receive(packet);

                udpPeers.add(new InetSocketAddress(packet.getAddress(), packet.getPort()));

                if (packet.getLength() == 5) {
                    System.out.println("udp register from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());
                    continue;
                }

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                tunDevice.writePacket(tunDevice.getFd(), data);

                logEvery(udpToTunCounter, "udp -> tun", data);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void logEvery(AtomicLong counter, String direction, byte[] data) {

        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(direction + " packets=" + value + " last=" + data.length + " bytes " + ipInfo(data));
    }

    private String ipInfo(byte[] data) {

        if (data.length < 20 || (data[0] & 0xF0) != 0x40) {
            return "";
        }

        return ip(data, 12) + " -> " + ip(data, 16);
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }

    private void runCommand(String... command) throws Exception {

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException("Command failed, code=" + code + ", command=" + String.join(" ", command));
        }
    }

    private void runCommandIgnoreError(String... command) {

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor();
        } catch (Exception ignored) {
        }
    }
}
