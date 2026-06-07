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

    private final UdpPeers udpPeers;

    public void start(DatagramSocket socket) {

        int fd = openTun();

        System.out.println("tun0 opened");

        new Thread(() -> {
            try {
                while (true) {

                    Thread.sleep(5000);

                    byte[] data = "TEST".getBytes();

                    for (InetSocketAddress peer : udpPeers.getAll()) {

                        socket.send(
                                new DatagramPacket(
                                        data,
                                        data.length,
                                        peer.getAddress(),
                                        peer.getPort()
                                )
                        );

                        System.out.println("sent test packet");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        readPackets(fd, socket);
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

    private void readPackets(int fd, DatagramSocket socket) {

        byte[] buffer = new byte[2000];

        while (true) {

            int len =
                    LibC.INSTANCE.read(
                            fd,
                            buffer,
                            buffer.length
                    );

            if (len > 0) {

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

                System.out.println("tun -> udp: " + len + " bytes");
            }
        }
    }
}