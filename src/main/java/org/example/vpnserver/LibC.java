package org.example.vpnserver;


import com.sun.jna.Library;
import com.sun.jna.Structure;
import com.sun.jna.Native;

public interface LibC extends Library {

    LibC INSTANCE = Native.load("c", LibC.class);

    int open(String path, int flags);

    int ioctl(int fd, long request, Structure arg);

    int read(int fd, byte[] buffer, int count);
}
