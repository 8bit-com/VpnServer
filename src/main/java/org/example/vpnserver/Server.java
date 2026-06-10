package org.example.vpnserver;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@RestController
public class Server {

    private final TunDevice tunDevice;
    private final WsTunnelServer wsTunnelServer;

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

    public Server(TunDevice tunDevice, WsTunnelServer wsTunnelServer) {
        this.tunDevice = tunDevice;
        this.wsTunnelServer = wsTunnelServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (!tunEnabled) {
            System.out.println("VPN SERVER READY WITHOUT TUN");
            return;
        }

        runCommandIgnoreError("ip", "link", "delete", tunName);
        tunDevice.open(tunName);
        configureLinuxVpnNetwork();
        wsTunnelServer.start();

        System.out.println("VPN SERVER READY");
    }

    @GetMapping("/health")
    public String health() {
        return "ok\n";
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

    private String[] prepend(String first, String[] rest) {
        String[] result = new String[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private void runCommand(String... command) throws Exception {
        int code = runCommandStatus(command);
        if (code != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command));
        }
    }

    private int runCommandStatus(String... command) throws Exception {
        System.out.println("RUN: " + String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        return process.waitFor();
    }

    private void runCommandIgnoreError(String... command) {
        try {
            runCommand(command);
        } catch (Exception ignored) {
        }
    }
}
