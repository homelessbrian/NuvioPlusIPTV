package com.nuvio.vpn.android;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/** The compatibility engine is a separate, pinned OpenVPN2 process. It receives
 * only in-memory configuration and app-owned TUN/protected transport descriptors. */
final class OpenVpn2Session implements OpenVpnEngine, OpenVpn2Management.Callbacks {
    private static final int MAX_FDS = 8;
    private final VpnConnectionService service;
    private final HttpStreamTunnel proxy;
    private final OpenVpn2Config input;
    private final OpenVpn2Management management = new OpenVpn2Management(this);
    private final Deque<FileDescriptor> descriptors = new ArrayDeque<>();
    private final Object outputLock = new Object();
    private final File socketPath;
    private volatile java.lang.Process process;
    private volatile LocalSocket listener, socket;
    private volatile LocalServerSocket server;
    private volatile Thread reader;
    private volatile boolean closing;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String detail;
    private volatile int peerPid;

    private OpenVpn2Session(VpnConnectionService service, HttpStreamTunnel proxy, OpenVpn2Config input) throws IOException {
        this.service = service;
        this.proxy = proxy;
        this.input = input;
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        StringBuilder name = new StringBuilder("ov2-");
        for (byte item : random) name.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        socketPath = new File(service.getCacheDir(), name.toString());
        if (socketPath.getAbsolutePath().getBytes(StandardCharsets.UTF_8).length >= 104)
            throw new IOException("The OpenVPN management socket could not be created safely.");
    }

    static void validate(Context context, byte[] configuration) throws IOException {
        java.lang.Process child = builder(context, "--nuvio-validate", "--config", "stdin").start();
        Thread output = discard(child.getInputStream());
        Thread writer = writeInput(child, configuration);
        try {
            int result = await(child, 15000);
            if (result != 0) throw new IOException("Unsupported or invalid OpenVPN compatibility profile.");
        } finally {
            terminate(child, 0);
            closeQuietly(child.getOutputStream());
            closeQuietly(child.getInputStream());
            join(writer); join(output);
        }
    }

    static OpenVpn2Session start(VpnConnectionService service, HttpStreamTunnel proxy, OpenVpn2Config input) throws IOException {
        OpenVpn2Session session = null;
        try {
            if (input.needsCredentials) throw new IOException("Set the OpenVPN service credentials in VPN profiles.");
            session = new OpenVpn2Session(service, proxy, input);
            LocalSocket bound = new LocalSocket();
            session.listener = bound;
            bound.bind(new LocalSocketAddress(session.socketPath.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(session.socketPath.getAbsolutePath(), 0600);
            session.server = new LocalServerSocket(bound.getFileDescriptor());
            session.process = builder(service, "--config", "stdin",
                    "--management", session.socketPath.getAbsolutePath(), "unix",
                    "--management-client", "--management-query-passwords", "--management-hold",
                    "--management-signal", "--script-security", "0", "--auth-nocache").start();
            discard(session.process.getInputStream());
            writeInput(session.process, input.bytes);
            OpenVpn2Session active = session;
            session.reader = daemon("Nuvio OpenVPN2 management", active::readManagement);
            daemon("Nuvio OpenVPN2 lifetime", () -> {
                try { active.process.waitFor(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                active.management.end();
                active.close();
            });
            return session;
        } catch (Exception | LinkageError failure) {
            if (session != null) session.close(); else input.close();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("The OpenVPN compatibility engine could not start safely.");
        }
    }

    private static String executable(Context context) throws IOException {
        File directory = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        File executable = new File(directory, "libnuvio-openvpn2.so").getCanonicalFile();
        File engine = new File(directory, "libnuvio-openvpn2-engine.so").getCanonicalFile();
        if (!directory.equals(executable.getParentFile()) || !directory.equals(engine.getParentFile())
                || !executable.isFile() || !executable.canExecute() || !engine.isFile())
            throw new IOException("This installation does not contain the OpenVPN compatibility engine.");
        return executable.getAbsolutePath();
    }

    private static ProcessBuilder builder(Context context, String... arguments) throws IOException {
        String binary = executable(context);
        String[] command = new String[arguments.length + 1];
        command[0] = binary;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Android intentionally omits executable RUNPATH. Never append a writable
        // inherited search directory when loading the separately licensed engine.
        builder.environment().put("LD_LIBRARY_PATH", new File(binary).getParentFile().getCanonicalPath());
        // Android has no /tmp. Keep the engine's filesystem preflight in the
        // app's private cache instead of inheriting an external temporary path.
        File temporary = context.getCacheDir().getCanonicalFile();
        if (!temporary.isDirectory() || !temporary.canWrite() || !temporary.canExecute())
            throw new IOException("Private OpenVPN temporary storage is unavailable.");
        builder.environment().put("TMPDIR", temporary.getAbsolutePath());
        return builder;
    }

    private void readManagement() {
        byte[] bytes = new byte[2048];
        WipingBuffer pending = new WipingBuffer();
        try {
            LocalSocket connected = server.accept();
            socket = connected;
            if (closing || connected.getPeerCredentials().getUid() != android.os.Process.myUid())
                throw new IOException("The OpenVPN management peer could not be verified.");
            peerPid = connected.getPeerCredentials().getPid();
            if (peerPid <= 0) throw new IOException("The OpenVPN management process could not be verified.");
            closeQuietly(server); server = null;
            closeQuietly(listener); listener = null;
            socketPath.delete();
            command("version 3\n");
            InputStream stream = connected.getInputStream();
            int count;
            while (!closing && (count = stream.read(bytes)) != -1) {
                FileDescriptor[] incoming = connected.getAncillaryFileDescriptors();
                if (incoming != null) {
                    synchronized (descriptors) {
                        if (closing) {
                            for (FileDescriptor descriptor : incoming) closeDescriptor(descriptor);
                            throw new IOException("The OpenVPN connection ended.");
                        }
                        if (incoming.length > MAX_FDS - descriptors.size()) {
                            for (FileDescriptor descriptor : incoming) closeDescriptor(descriptor);
                            throw new IOException("Too many OpenVPN transport descriptors.");
                        }
                        for (FileDescriptor descriptor : incoming) {
                            descriptors.addLast(descriptor);
                        }
                    }
                }
                for (int at = 0; at < count; at++) {
                    if (bytes[at] == '\n') {
                        byte[] lineBytes = pending.toByteArray();
                        try {
                            int length = lineBytes.length;
                            if (length > 0 && lineBytes[length - 1] == '\r') length--;
                            management.line(new String(lineBytes, 0, length, StandardCharsets.UTF_8));
                        } finally { Arrays.fill(lineBytes, (byte) 0); pending.wipe(); }
                    } else {
                        if (pending.size() >= 16384) throw new IOException("Invalid OpenVPN management response.");
                        pending.write(bytes[at]);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            // Only locally authored messages are displayed. Raw engine output is never logged.
            if (!closing) detail = "The OpenVPN compatibility connection could not apply its protected tunnel settings.";
        } finally {
            Arrays.fill(bytes, (byte) 0);
            pending.wipe();
            management.end();
            close();
        }
    }

    @Override public void command(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try { send(bytes, null); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private void send(byte[] bytes, FileDescriptor descriptor) throws IOException {
        synchronized (outputLock) {
            LocalSocket connected = socket;
            if (closing || connected == null) throw new IOException("The OpenVPN management connection ended.");
            try {
                connected.setFileDescriptorsForSend(descriptor == null ? null : new FileDescriptor[]{descriptor});
                connected.getOutputStream().write(bytes);
                connected.getOutputStream().flush();
            } finally { connected.setFileDescriptorsForSend(null); }
        }
    }

    @Override public void protect() throws IOException {
        FileDescriptor descriptor;
        synchronized (descriptors) { descriptor = descriptors.pollFirst(); }
        if (descriptor == null) throw new IOException("OpenVPN did not provide its transport descriptor.");
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor)) {
            if (proxy == null && !service.protect(duplicate.getFd()))
                throw new IOException("Android could not protect the OpenVPN transport socket.");
        } finally { closeDescriptor(descriptor); }
    }

    @Override public void openTun(String[] addresses, String[] dns, int mtu) throws IOException {
        if (closing) throw new IOException("The OpenVPN connection ended.");
        int descriptor = OpenVpnTun.establish(service, proxy, addresses, dns, mtu);
        if (descriptor < 0) throw new IOException("The protected OpenVPN tunnel could not be established.");
        try (ParcelFileDescriptor tun = ParcelFileDescriptor.adoptFd(descriptor)) {
            if (closing) throw new IOException("The OpenVPN connection ended.");
            byte[] acknowledgement = "needok 'OPENTUN' ok\n".getBytes(StandardCharsets.US_ASCII);
            send(acknowledgement, tun.getFileDescriptor());
        }
    }

    @Override public void credentials() throws IOException {
        byte[] username = OpenVpn2Config.credentialCommand("username", input.username);
        byte[] password = null;
        try {
            password = OpenVpn2Config.credentialCommand("password", input.password);
            send(username, null); send(password, null);
        } finally {
            Arrays.fill(username, (byte) 0);
            if (password != null) Arrays.fill(password, (byte) 0);
        }
    }

    @Override public boolean isUp() {
        java.lang.Process child = process;
        if (closing || management.ended() || child == null) return false;
        try { child.exitValue(); management.end(); return false; }
        catch (IllegalThreadStateException alive) { return true; }
    }
    @Override public boolean authRejected() { return management.authRejected(); }
    @Override public String lastDetail() { return detail; }
    @Override public TunnelTraffic statistics() {
        if (!isUp()) management.end();
        return management.statistics();
    }
    @Override public boolean probe() { return AppVpnProbe.verify(service); }
    @Override public String egress() { return AppVpnProbe.egress(service); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closing = true;
        // Close IPC before waiting for the management state lock: callbacks may be
        // blocked in a write, and disconnect must interrupt them immediately.
        closeQuietly(socket); socket = null;
        closeQuietly(server); server = null;
        closeQuietly(listener); listener = null;
        java.lang.Process child = process;
        if (child != null) {
            terminate(child, peerPid);
            closeQuietly(child.getOutputStream());
            closeQuietly(child.getInputStream());
        }
        management.end();
        synchronized (descriptors) {
            while (!descriptors.isEmpty()) closeDescriptor(descriptors.removeFirst());
        }
        input.close();
        socketPath.delete();
    }

    private static Thread writeInput(java.lang.Process child, byte[] bytes) {
        return daemon("Nuvio OpenVPN2 input", () -> {
            try (OutputStream stream = child.getOutputStream()) { stream.write(bytes); stream.flush(); }
            catch (IOException failure) { child.destroy(); }
        });
    }
    private static void terminate(java.lang.Process child, int verifiedPid) {
        try { child.exitValue(); return; }
        catch (IllegalThreadStateException alive) { }
        if (Build.VERSION.SDK_INT >= 26) child.destroyForcibly();
        else if (verifiedPid > 0) android.os.Process.killProcess(verifiedPid);
        else child.destroy(); // The native validation-only watchdog also bounds this path on API24/25.
    }
    private static Thread discard(InputStream stream) {
        return daemon("Nuvio OpenVPN2 output", () -> {
            byte[] buffer = new byte[2048];
            try { while (stream.read(buffer) != -1) Arrays.fill(buffer, (byte) 0); }
            catch (IOException ignored) { }
            finally { Arrays.fill(buffer, (byte) 0); closeQuietly(stream); }
        });
    }
    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); return thread;
    }
    private static int await(java.lang.Process child, long timeout) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { return child.exitValue(); }
            catch (IllegalThreadStateException alive) { SystemClock.sleep(20); }
        }
        throw new IOException("The OpenVPN compatibility profile validation timed out.");
    }
    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static void closeQuietly(java.io.Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }
    private static void closeDescriptor(FileDescriptor descriptor) {
        try { Os.close(descriptor); } catch (Exception ignored) { }
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
