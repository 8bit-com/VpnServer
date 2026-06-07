package org.example.vpnserver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

@Service
@RequiredArgsConstructor
public class TunDevice {

    private static final int O_RDWR = 2;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final long TUNSETIFF = 0x400454caL;

    private int fd;
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

    public void writePacket(
            int fd,
            byte[] data
    ) {

        int written =
                LibC.INSTANCE.write(
                        fd,
                        data,
                        data.length
                );

        if (written < 0) {
            throw new RuntimeException(
                    "tun write failed"
            );
        }
    }

    private int openTun() {

        int fd =
                LibC.INSTANCE.open(
                        "/dev/net/tun",
                        O_RDWR
                );

        IfReq ifr = new IfReq();

        System.arraycopy(
                "tun0".getBytes(),
                0,
                ifr.ifr_name,
                0,
                4
        );

        ifr.ifr_flags =
                (short) (IFF_TUN | IFF_NO_PI);

        ifr.write();

        int result =
                LibC.INSTANCE.ioctl(
                        fd,
                        TUNSETIFF,
                        ifr
                );

        if (result < 0) {
            throw new RuntimeException("ioctl failed");
        }

        return fd;
    }

    public void readPackets(DatagramSocket socket) {

        byte[] buffer = new byte[65535];

        while (true) {

            int len =
                    LibC.INSTANCE.read(
                            fd,
                            buffer,
                            buffer.length
                    );

            if (len <= 0) {
                continue;
            }

            for (InetSocketAddress peer : udpPeers.getAll()) {

                try {

                    socket.send(
                            new DatagramPacket(
                                    buffer,
                                    len,
                                    peer.getAddress(),
                                    peer.getPort()
                            )
                    );

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            System.out.println("tun -> client : " + len + " bytes " + ipInfo(buffer, len));
        }
    }

    private String ipInfo(byte[] data, int len) {

        if (len < 20 || (data[0] & 0xF0) != 0x40) {
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
}
