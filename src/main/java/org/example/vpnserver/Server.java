package org.example.vpnserver;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class Server {

    private static final int MAX_PACKET_SIZE = 65535;
    private static final int LOG_EVERY_PACKETS = 100;
    private static final int IPV4_HEADER_MIN_LENGTH = 20;
    private static final int ICMP_HEADER_LENGTH = 8;
    private static final int ICMP_PROTOCOL = 1;
    private static final int ICMP_ECHO_REQUEST = 8;
    private static final int ICMP_ECHO_REPLY = 0;
    private static final long RX_TIMEOUT_MS = 115000;

    private final TunDevice tunDevice;
    private final BlockingQueue<byte[]> priorityToClient = new ArrayBlockingQueue<>(4096);
    private final BlockingQueue<byte[]> normalToClient = new ArrayBlockingQueue<>(65536);
    private final AtomicLong httpToTunCounter = new AtomicLong();
    private final AtomicLong tunToHttpCounter = new AtomicLong();
    private final AtomicLong queuedToClientCounter = new AtomicLong();
    private final AtomicLong rxReturnedCounter = new AtomicLong();

    @Value("${vpn.tun.enabled:true}")
    private boolean tunEnabled;

    @Value("${vpn.tun.name:tun-http}")
    private String tunName;

    @Value("${vpn.tun.address:10.8.0.1/24}")
    private String tunAddress;

    @Value("${vpn.network:10.8.0.0/24}")
    private String vpnNetwork;

    @Value("${vpn.mtu:1400}")
    private int mtu;

    @Value("${vpn.probe-ip:1.1.1.1}")
    private String probeIp;

    public Server(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (!tunEnabled) {
            System.out.println("HTTP VPN SERVER READY WITHOUT TUN");
            return;
        }

        runCommandIgnoreError("ip", "link", "delete", tunName);
        tunDevice.open(tunName);
        configureLinuxVpnNetwork();

        Thread thread = new Thread(this::readTunAndQueueHttp, "tun-to-http");
        thread.setDaemon(true);
        thread.start();

        System.out.println("HTTP VPN SERVER READY");
    }

    @GetMapping("/health")
    public String health() {
        return "ok\n";
    }

    @PostMapping(value = "/tx", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> tx(@RequestBody byte[] packet) {
        if (packet.length == 0 || packet.length > MAX_PACKET_SIZE || !isIpv4Packet(packet)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] icmpReply = buildIcmpEchoReplyIfGatewayPing(packet);
        if (icmpReply != null) {
            offer(priorityToClient, icmpReply, "priority", "gateway-ping");
            return ResponseEntity.noContent().build();
        }

        if (tunEnabled) {
            tunDevice.writePacket(packet);
            logEvery(httpToTunCounter, "http -> tun", packet);
        } else {
            offer(normalToClient, ("ECHO_FROM_SERVER: " + printable(packet)).getBytes(StandardCharsets.UTF_8), "normal", "test-echo");
        }

        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/rx", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> rx() {
        try {
            byte[] packet = priorityToClient.poll();
            if (packet == null) {
                packet = normalToClient.poll(RX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }

            if (packet == null) {
                return ResponseEntity.noContent().build();
            }

            long value = rxReturnedCounter.incrementAndGet();
            if (value % LOG_EVERY_PACKETS == 0) {
                System.out.println("rx -> client packets=" + value + " last=" + packet.length + " bytes " + PacketInfo.info(packet));
            }

            return ResponseEntity.ok(packet);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.noContent().build();
        }
    }

    @PostMapping(
            value = "/packet",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<byte[]> packet(
            @RequestParam long id,
            @RequestBody byte[] packet
    ) {
        if (packet.length == 0 || packet.length > MAX_PACKET_SIZE || !isIpv4Packet(packet)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] icmpReply = buildIcmpEchoReplyIfGatewayPing(packet);
        if (icmpReply != null) {
            return ResponseEntity.ok(icmpReply);
        }

        if (tunEnabled) {
            tunDevice.writePacket(packet);
            logEvery(httpToTunCounter, "http -> tun", packet);
        }

        return ResponseEntity.noContent().build();
    }

    private void readTunAndQueueHttp() {
        while (true) {
            try {
                byte[] packet = tunDevice.readPacket();
                if (packet == null || !isIpv4Packet(packet)) {
                    continue;
                }

                if (isIcmpPacket(packet)) {
                    offer(priorityToClient, packet, "priority", "tun-icmp");
                } else {
                    offer(normalToClient, packet, "normal", "tun-read");
                }

                logEvery(tunToHttpCounter, "tun -> http", packet);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isIcmpPacket(byte[] packet) {
        return isIpv4Packet(packet) && (packet[9] & 0xff) == ICMP_PROTOCOL;
    }

    private void offer(BlockingQueue<byte[]> queue, byte[] packet, String queueName, String reason) {
        if (!queue.offer(packet)) {
            queue.poll();
            queue.offer(packet);
        }
        long value = queuedToClientCounter.incrementAndGet();
        if (value % LOG_EVERY_PACKETS == 0) {
            System.out.println("queue -> client packets=" + value + " queue=" + queueName + " reason=" + reason + " size=" + queue.size() + " last=" + packet.length + " bytes " + PacketInfo.info(packet));
        }
    }

    private byte[] buildIcmpEchoReplyIfGatewayPing(byte[] request) {
        if (request.length < IPV4_HEADER_MIN_LENGTH + ICMP_HEADER_LENGTH) {
            return null;
        }

        int version = (request[0] >> 4) & 0x0f;
        int ihl = (request[0] & 0x0f) * 4;
        if (version != 4 || ihl < IPV4_HEADER_MIN_LENGTH || request.length < ihl + ICMP_HEADER_LENGTH) {
            return null;
        }

        int totalLength = u16(request, 2);
        if (totalLength < ihl + ICMP_HEADER_LENGTH || totalLength > request.length) {
            return null;
        }

        int protocol = request[9] & 0xff;
        if (protocol != ICMP_PROTOCOL) {
            return null;
        }

        String dst = ip(request, 16);
        if (!gatewayIp().equals(dst)) {
            return null;
        }

        int icmpOffset = ihl;
        int icmpType = request[icmpOffset] & 0xff;
        if (icmpType != ICMP_ECHO_REQUEST) {
            return null;
        }

        byte[] reply = Arrays.copyOf(request, totalLength);

        for (int i = 0; i < 4; i++) {
            reply[12 + i] = request[16 + i];
            reply[16 + i] = request[12 + i];
        }

        reply[8] = 64;
        reply[10] = 0;
        reply[11] = 0;
        putU16(reply, 10, checksum(reply, 0, ihl));

        reply[icmpOffset] = ICMP_ECHO_REPLY;
        reply[icmpOffset + 1] = 0;
        reply[icmpOffset + 2] = 0;
        reply[icmpOffset + 3] = 0;
        int icmpLength = totalLength - ihl;
        putU16(reply, icmpOffset + 2, checksum(reply, icmpOffset, icmpLength));

        return reply;
    }

    private boolean isIpv4Packet(byte[] packet) {
        return packet.length >= IPV4_HEADER_MIN_LENGTH && ((packet[0] >> 4) & 0x0f) == 4;
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        while (length > 1) {
            sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            i += 2;
            length -= 2;
        }
        if (length > 0) {
            sum += (data[i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private String gatewayIp() {
        return tunAddress.contains("/") ? tunAddress.substring(0, tunAddress.indexOf('/')) : tunAddress;
    }

    private void configureLinuxVpnNetwork() throws Exception {

        String externalInterface = getExternalInterface();

        runCommand("ip", "addr", "flush", "dev", tunName);
        runCommand("ip", "addr", "add", tunAddress, "dev", tunName);
        runCommand("ip", "link", "set", "dev", tunName, "mtu", String.valueOf(mtu));
        runCommand("ip", "link", "set", tunName, "up");

        runCommand("sysctl", "-w", "net.ipv4.ip_forward=1");

        ensureIptables("-t", "nat", "-A", "POSTROUTING", "-s", vpnNetwork, "-o", externalInterface, "-j", "MASQUERADE");
        ensureIptables("-A", "FORWARD", "-i", tunName, "-o", externalInterface, "-j", "ACCEPT");
        ensureIptables("-A", "FORWARD", "-i", externalInterface, "-o", tunName, "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT");

        System.out.println("LINUX VPN NETWORK CONFIGURED: " + tunName + " " + tunAddress + ", MTU " + mtu + ", NAT via " + externalInterface);
    }

    private void ensureIptables(String... rule) throws Exception {
        String[] checkRule = rule.clone();
        for (int i = 0; i < checkRule.length; i++) {
            if ("-A".equals(checkRule[i])) {
                checkRule[i] = "-C";
                break;
            }
        }

        if (runCommandStatus(prepend("iptables", checkRule)) != 0) {
            runCommand(prepend("iptables", rule));
        }
    }

    private String getExternalInterface() throws Exception {

        Process process = new ProcessBuilder("sh", "-c", "ip route get " + probeIp + " | sed -n 's/.* dev \\([^ ]*\\).*/\\1/p' | head -n 1")
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

    private String printable(byte[] data) {
        boolean text = Arrays.stream(new String(data, StandardCharsets.UTF_8).chars().toArray())
                .allMatch(ch -> ch == '\r' || ch == '\n' || ch == '\t' || ch >= 32);

        if (text) {
            return new String(data, StandardCharsets.UTF_8);
        }

        return PacketInfo.info(data);
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xff) + "." + (data[offset + 1] & 0xff) + "." + (data[offset + 2] & 0xff) + "." + (data[offset + 3] & 0xff);
    }

    private void runCommand(String... command) throws Exception {

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException("Command failed, code=" + code + ", command=" + String.join(" ", command));
        }
    }

    private int runCommandStatus(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return process.waitFor();
    }

    private void runCommandIgnoreError(String... command) {

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor();
        } catch (Exception ignored) {
        }
    }

    private String[] prepend(String first, String[] rest) {
        String[] result = new String[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
