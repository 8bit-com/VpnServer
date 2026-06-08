package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class TunDevice {

    private static final int O_RDWR = 2;
    private static final int LOG_EVERY_PACKETS = 100;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final long TUNSETIFF = 0x400454caL;

    private int fd;
    private final AtomicLong tunToClientCounter = new AtomicLong();
    private final UdpPeers udpPeers;

    public void start(DatagramSocket socket) {

        open();
        readPackets(socket);
    }

    public void open() {

        fd = openTun();

        System.out.println("tun0 opened");
    }

    public int getFd() {
        return fd;
    }

    public void writePacket(int fd, byte[] data) {

        int written = LibC.INSTANCE.write(fd, data, data.length);

        if (written != data.length) {
            throw new RuntimeException("tun write failed, written=" + written + ", expected=" + data.length + ", errno=" + LibC.errno());
        }
    }

    private int openTun() {

        int fd = LibC.INSTANCE.open("/dev/net/tun", O_RDWR);

        if (fd < 0) {
            throw new RuntimeException("open /dev/net/tun failed, errno=" + LibC.errno());
        }

        IfReq ifr = new IfReq();

        System.arraycopy("tun0".getBytes(), 0, ifr.ifr_name, 0, 4);

        ifr.ifr_flags = (short) (IFF_TUN | IFF_NO_PI);

        ifr.write();

        int result = LibC.INSTANCE.ioctl(fd, TUNSETIFF, ifr);

        if (result < 0) {
            throw new RuntimeException("ioctl TUNSETIFF failed, errno=" + LibC.errno());
        }

        return fd;
    }

    public void readPackets(DatagramSocket socket) {

        byte[] buffer = new byte[65535];

        while (true) {

            try {
                int len = LibC.INSTANCE.read(fd, buffer, buffer.length);

                if (len <= 0) {
                    continue;
                }

                byte[] packet = Arrays.copyOf(buffer, len);

                for (InetSocketAddress peer : udpPeers.getAll()) {
                    socket.send(new DatagramPacket(packet, packet.length, peer.getAddress(), peer.getPort()));
                }

                logEvery(packet, packet.length);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void logEvery(byte[] data, int len) {

        long value = tunToClientCounter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println("tun -> client packets=" + value + " last=" + len + " bytes " + PacketInfo.info(data, len));
    }
}
