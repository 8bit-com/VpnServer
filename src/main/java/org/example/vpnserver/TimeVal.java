package org.example.vpnserver;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class TimeVal extends Structure {

    public long tv_sec;
    public long tv_usec;

    public TimeVal() {
    }

    public TimeVal(long seconds, long microseconds) {
        this.tv_sec = seconds;
        this.tv_usec = microseconds;
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("tv_sec", "tv_usec");
    }
}
