package org.example.vpnserver;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class SockAddrIn extends Structure {

    public short sin_family;
    public short sin_port;
    public int sin_addr;
    public byte[] sin_zero = new byte[8];

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("sin_family", "sin_port", "sin_addr", "sin_zero");
    }
}
