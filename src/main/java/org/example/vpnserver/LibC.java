package org.example.vpnserver;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

public interface LibC extends Library {

    LibC INSTANCE = Native.load("c", LibC.class);

    int AF_INET = 2;
    int SOCK_RAW = 3;
    int IPPROTO_ICMP = 1;
    int SOL_SOCKET = 1;
    int SO_RCVTIMEO = 20;
    int SO_SNDTIMEO = 21;

    int open(String path, int flags);

    int ioctl(int fd, long request, Structure arg);

    int read(int fd, byte[] buffer, int count);

    int write(int fd, byte[] buffer, int count);

    int socket(int domain, int type, int protocol);

    int recvfrom(int sockfd, byte[] buffer, int length, int flags, SockAddrIn srcAddr, IntByReference addrLen);

    int sendto(int sockfd, byte[] buffer, int length, int flags, SockAddrIn destAddr, int addrLen);

    int setsockopt(int sockfd, int level, int optname, TimeVal optval, int optlen);

    static int errno() {
        return Native.getLastError();
    }
}
