package org.example.vpnserver;

import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UdpPeers {

    private final Set<InetSocketAddress> peers =
            ConcurrentHashMap.newKeySet();

    public void add(InetSocketAddress address) {
        peers.add(address);
        System.out.println("peer added: " + address);
    }

    public Set<InetSocketAddress> getAll() {
        return peers;
    }
}
