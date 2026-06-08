package org.example.vpnserver;

import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;

@Service
public class UdpPeers {

    private volatile InetSocketAddress currentPeer;

    public void add(InetSocketAddress address) {

        if (!address.equals(currentPeer)) {
            currentPeer = address;
            System.out.println("peer set: " + address);
        }
    }

    public Set<InetSocketAddress> getAll() {

        InetSocketAddress peer = currentPeer;

        if (peer == null) {
            return Collections.emptySet();
        }

        return Collections.singleton(peer);
    }
}
