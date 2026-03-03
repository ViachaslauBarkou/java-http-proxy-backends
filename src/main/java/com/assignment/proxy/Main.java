package com.assignment.proxy;

import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        int proxyPort = 8080;
        int storagePort = 9000;

        new FramedStorageBackendServer(storagePort).start();
        new PlainHttpBackendServer(9001, "api-1").start();
        new PlainHttpBackendServer(9002, "api-2").start();
        new PlainHttpBackendServer(9003, "api-3").start();
        new PlainHttpBackendServer(9004, "api-4").start();

        ProxyServer proxy = new ProxyServer(proxyPort, List.of(
                new ProxyServer.BackendTarget("127.0.0.1", storagePort, ProxyServer.BackendType.FRAMED, "storage"),
                new ProxyServer.BackendTarget("127.0.0.1", 9001, ProxyServer.BackendType.PLAIN, "api-1"),
                new ProxyServer.BackendTarget("127.0.0.1", 9002, ProxyServer.BackendType.PLAIN, "api-2"),
                new ProxyServer.BackendTarget("127.0.0.1", 9003, ProxyServer.BackendType.PLAIN, "api-3"),
                new ProxyServer.BackendTarget("127.0.0.1", 9004, ProxyServer.BackendType.PLAIN, "api-4")
        ));
        proxy.start();

        System.out.println("Proxy started on :" + proxyPort);
        Thread.currentThread().join();
    }
}
