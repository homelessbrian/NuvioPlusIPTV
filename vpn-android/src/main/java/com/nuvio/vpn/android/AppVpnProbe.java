package com.nuvio.vpn.android;

import android.content.Context;
import java.net.HttpURLConnection;
import java.net.URL;

final class AppVpnProbe {
    private AppVpnProbe() {}
    /** Addresses that answer a quick check; any one answering proves the tunnel carries traffic. */
    private static final String[] CHECKS = {
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://1.1.1.1/cdn-cgi/trace",
    };
    private static final long CHECK_DEADLINE_MS = 6000;
    private static final java.util.concurrent.ExecutorService checks = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vpn-check");
        t.setDaemon(true);
        return t;
    });

    /**
     * Asks every check address at once through the VPN and returns as soon as one answers, so a VPN
     * server that blocks or slows one of them (Google, say) never makes a working tunnel look dead.
     */
    static boolean verify(Context context) {
        java.util.concurrent.CompletionService<Boolean> results = new java.util.concurrent.ExecutorCompletionService<>(checks);
        java.util.List<java.util.concurrent.Future<Boolean>> started = new java.util.ArrayList<>();
        try {
            for (String address : CHECKS) started.add(results.submit(() -> answers(context, address)));
            long deadline = android.os.SystemClock.elapsedRealtime() + CHECK_DEADLINE_MS;
            for (int i = 0; i < started.size(); i++) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                java.util.concurrent.Future<Boolean> done = results.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (done == null) return false;
                try { if (Boolean.TRUE.equals(done.get())) return true; } catch (java.util.concurrent.ExecutionException ignored) { }
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            for (java.util.concurrent.Future<Boolean> f : started) f.cancel(true);
        }
    }

    private static boolean answers(Context context, String address) {
        HttpURLConnection connection = null;
        try {
            connection = open(context, address);
            int code = connection.getResponseCode();
            if (address.endsWith("generate_204")) return code == 204;
            if (code != 200) return false;
            byte[] buffer = new byte[512];
            int n;
            StringBuilder body = new StringBuilder();
            try (java.io.InputStream input = connection.getInputStream()) {
                while (body.length() < 2048 && (n = input.read(buffer)) != -1)
                    body.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.US_ASCII));
            }
            return body.indexOf("ip=") >= 0;
        } catch (Exception failure) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
    static String egress(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = open(context,"https://api.ipify.org");
            if (connection.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                int value;
                while ((value = input.read()) != -1) {
                    if (bytes.size() == 64) return null;
                    bytes.write(value);
                }
            }
            return com.wireguard.config.InetAddresses.parse(new String(bytes.toByteArray(),
                    java.nio.charset.StandardCharsets.US_ASCII).trim()).getHostAddress();
        } catch (Exception failure) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static HttpURLConnection open(Context context, String address) throws Exception {
        android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network vpn = manager.getActiveNetwork();
        android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(vpn);
        if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
            throw new java.io.IOException("App VPN is unavailable.");
        HttpURLConnection connection = (HttpURLConnection) vpn.openConnection(new URL(address));
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
