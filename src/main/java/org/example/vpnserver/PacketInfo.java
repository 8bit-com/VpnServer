package org.example.vpnserver;

public final class PacketInfo {

    private PacketInfo() {
    }

    public static String info(byte[] data) {
        return info(data, data.length);
    }

    public static String info(byte[] data, int len) {
        if (len < 20 || (data[0] & 0xF0) != 0x40) {
            return "not-ipv4";
        }

        int ihl = (data[0] & 0x0F) * 4;
        int protocol = data[9] & 0xFF;
        String src = ip(data, 12);
        String dst = ip(data, 16);

        if (protocol == 1) {
            return "ICMP " + src + " -> " + dst + icmp(data, len, ihl);
        }

        if (protocol == 6) {
            return "TCP " + tcp(data, len, ihl, src, dst);
        }

        if (protocol == 17) {
            return "UDP " + udp(data, len, ihl, src, dst);
        }

        return "PROTO " + protocol + " " + src + " -> " + dst;
    }

    private static String tcp(byte[] data, int len, int ihl, String src, String dst) {
        if (len < ihl + 20) {
            return src + " -> " + dst + " bad-tcp";
        }

        int srcPort = u16(data, ihl);
        int dstPort = u16(data, ihl + 2);
        int flags = data[ihl + 13] & 0xFF;

        return src + ":" + srcPort + " -> " + dst + ":" + dstPort + " flags=" + flags(flags);
    }

    private static String udp(byte[] data, int len, int ihl, String src, String dst) {
        if (len < ihl + 8) {
            return src + " -> " + dst + " bad-udp";
        }

        int srcPort = u16(data, ihl);
        int dstPort = u16(data, ihl + 2);

        return src + ":" + srcPort + " -> " + dst + ":" + dstPort;
    }

    private static String icmp(byte[] data, int len, int ihl) {
        if (len < ihl + 4) {
            return " bad-icmp";
        }

        return " type=" + (data[ihl] & 0xFF) + " code=" + (data[ihl + 1] & 0xFF);
    }

    private static String flags(int flags) {
        StringBuilder result = new StringBuilder();

        if ((flags & 0x01) != 0) result.append("FIN,");
        if ((flags & 0x02) != 0) result.append("SYN,");
        if ((flags & 0x04) != 0) result.append("RST,");
        if ((flags & 0x08) != 0) result.append("PSH,");
        if ((flags & 0x10) != 0) result.append("ACK,");

        if (result.length() == 0) {
            return "NONE";
        }

        result.setLength(result.length() - 1);
        return result.toString();
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }
}
