package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class Server {

    private static final int PORT = 51888;
    private static final int LOG_EVERY_PACKETS = 1;
    private static final int MTU = 1200;
    private static final int TCP_MSS = 1160;
    private static final String TUN_NAME = "tun0";
    private static final String TUN_IP = "10.0.0.1/24";
    private static final String VPN_NETWORK = "10.0.0.0/24";
    private static final int MAX_PACKET_SIZE = 65535;

    private final AtomicLong tcpToTunCounter = new AtomicLong();
    private final AtomicLong tunToTcpCounter = new AtomicLong();
    private final AtomicReference<DataOutputStream> activeClientOut = new AtomicReference<>();
    private final TunDevice tunDevice;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        tunDevice.open();
        configureLinuxVpnNetwork();

        new Thread(this::readTunAndSendToTcp, "tun-to-tcp").start();
        listenTcpClients();
    }

    private void listenTcpClients() throws Exception {

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("TCP VPN SERVER LISTENING ON PORT " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                System.out.println("tcp client connected: " + socket.getRemoteSocketAddress());

                new Thread(() -> handleTcpClient(socket), "tcp-client").start();
            }
        }
    }

    private void handleTcpClient(Socket socket) {

        DataOutputStream currentOut = null;

        try (socket;
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            currentOut = out;
            activeClientOut.set(out);

            while (true) {
                int len = in.readInt();

                if (len <= 0 || len > MAX_PACKET_SIZE) {
                    throw new RuntimeException("bad tcp frame length: " + len);
                }

                byte[] data = in.readNBytes(len);

                if (data.length != len) {
                    throw new RuntimeException("tcp frame truncated: " + data.length + "/" + len);
                }

                tunDevice.writePacket(tunDevice.getFd(), data);

                logEvery(tcpToTunCounter, "tcp -> tun", data);
            }

        } catch (Exception e) {
            System.out.println("tcp client disconnected: " + socket.getRemoteSocketAddress() + " error=" + e.getMessage());
        } finally {
            if (currentOut != null) {
                activeClientOut.compareAndSet(currentOut, null);
            }
        }
    }

    private void readTunAndSendToTcp() {

        byte[] buffer = new byte[MAX_PACKET_SIZE];

        while (true) {
            try {
                int len = LibC.INSTANCE.read(tunDevice.getFd(), buffer, buffer.length);

                if (len <= 0) {
                    continue;
                }

                byte[] data = Arrays.copyOf(buffer, len);

                DataOutputStream out = activeClientOut.get();

                if (out == null) {
                    continue;
                }

                synchronized (out) {
                    out.writeInt(data.length);
                    out.write(data);
                    out.flush();
                }

                logEvery(tunToTcpCounter, "tun -> tcp", data);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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

    private void logEvery(AtomicLong counter, String direction, byte[] data) {

        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(direction + " packets=" + value + " last=" + data.length + " bytes " + PacketInfo.info(data));
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
