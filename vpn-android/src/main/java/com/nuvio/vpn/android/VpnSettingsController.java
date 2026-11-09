package com.nuvio.vpn.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logic behind the VPN page in Settings, for a Compose screen that stays free of the VPN internals.
 * It does the same things the older VpnProfilesActivity does: importing a profile never enables
 * protection or starts a tunnel, and all storage work runs off the main thread. Every change is
 * reported to the listener on the main thread.
 */
public final class VpnSettingsController {
    public static final int MODE_OFF = 0, MODE_HTTP = 1, MODE_HTTP_HTTPS = 2, MODE_APP = 3;
    public static final int LOGIN_NONE = 0, LOGIN_REQUIRED = 1, LOGIN_SAVED = 2;

    public interface Listener { void onState(State state); }

    public static final class Profile {
        public final String id, name, kind;
        public final boolean readable, selected;
        public final int login;
        Profile(String id, String name, String kind, boolean readable, boolean selected, int login) {
            this.id = id; this.name = name; this.kind = kind;
            this.readable = readable; this.selected = selected; this.login = login;
        }
    }

    public static final class State {
        public final boolean busy, shared, loaded;
        public final int mode;
        public final List<Profile> profiles;
        /** Result of the last action, already worded for the person. Null when there is none. */
        public final String notice;
        public final boolean noticeIsError;
        /** Set after importing an OpenVPN profile that still needs its service login. */
        public final String askCredentialsFor;
        State(boolean busy, boolean loaded, boolean shared, int mode, List<Profile> profiles,
              String notice, boolean noticeIsError, String askCredentialsFor) {
            this.busy = busy; this.loaded = loaded; this.shared = shared; this.mode = mode;
            this.profiles = profiles; this.notice = notice; this.noticeIsError = noticeIsError;
            this.askCredentialsFor = askCredentialsFor;
        }
        State with(boolean busyValue) {
            return new State(busyValue, loaded, shared, mode, profiles, notice, noticeIsError, askCredentialsFor);
        }
    }

    public static final class Connection {
        public final String title, detail, traffic;
        public final boolean canTurnOff;
        Connection(String title, String detail, String traffic, boolean canTurnOff) {
            this.title = title; this.detail = detail; this.traffic = traffic; this.canTurnOff = canTurnOff;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AndroidProfileStore store;
    private volatile State state = new State(false, false, true, MODE_OFF, Collections.<Profile>emptyList(), null, false, null);
    private volatile ProtectionMode savedMode = ProtectionMode.OFF;
    private volatile String selectedId;
    private volatile boolean needsCredentials;
    private volatile boolean closed;

    public VpnSettingsController(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.store = new AndroidProfileStore(app);
    }

    public State state() { return state; }

    public void close() {
        closed = true;
        worker.shutdown();
    }

    private AndroidVpnSettings settings() {
        return new AndroidVpnSettings(app, VpnPlaybackProtection.activeUserProfile());
    }

    private String text(int id) { return app.getString(id); }

    private void publish(State next) {
        state = next;
        main.post(() -> { if (!closed) listener.onState(next); });
    }

    private void setBusy(boolean busy) { publish(state.with(busy)); }

    private static int modeCode(ProtectionMode mode) {
        return mode == ProtectionMode.APP_WIDE ? MODE_APP
                : mode == ProtectionMode.HTTP_HTTPS ? MODE_HTTP_HTTPS
                : mode == ProtectionMode.HTTP_ONLY ? MODE_HTTP : MODE_OFF;
    }

    private static ProtectionMode modeOf(int code) {
        return code == MODE_APP ? ProtectionMode.APP_WIDE
                : code == MODE_HTTP_HTTPS ? ProtectionMode.HTTP_HTTPS
                : code == MODE_HTTP ? ProtectionMode.HTTP_ONLY : ProtectionMode.OFF;
    }

    private void run(Runnable job) {
        if (closed) return;
        try { worker.execute(job); } catch (RuntimeException ignored) { /* closed while the page was leaving */ }
    }

    /** Reads everything again. A notice may be shown with the new list. */
    public void refresh() { refreshWith(0, false, null, null); }

    private void refreshWith(int notice, boolean error, String reason, String askCredentials) {
        run(() -> {
            try {
                List<AndroidProfileStore.Entry> entries = store.list();
                AndroidVpnSettings settings = settings();
                VpnPreferences preferences = settings.read();
                String selected = preferences.getSelectedProfileId();
                boolean available = selected == null;
                for (AndroidProfileStore.Entry entry : entries) {
                    if (entry.readable && entry.id.equals(selected)) available = true;
                }
                if (!available) {
                    // Never silently switch to another profile after loss, corruption or key invalidation.
                    settings.selectProfile(null);
                    VpnConnectionService.settingsChanged(app);
                    preferences = settings.read();
                    selected = preferences.getSelectedProfileId();
                }
                boolean shared = !settings.snapshot().overrides.containsKey(VpnPlaybackProtection.activeUserProfile());
                boolean credentials = false;
                ArrayList<Profile> list = new ArrayList<>();
                for (AndroidProfileStore.Entry entry : entries) {
                    boolean isSelected = entry.id.equals(selected);
                    if (isSelected) credentials = entry.login == OpenVpnCredentials.Login.REQUIRED;
                    int login = entry.login == OpenVpnCredentials.Login.REQUIRED ? LOGIN_REQUIRED
                            : entry.login == OpenVpnCredentials.Login.SAVED ? LOGIN_SAVED : LOGIN_NONE;
                    list.add(new Profile(entry.id,
                            entry.readable ? entry.name : text(R.string.vpn_profiles_unreadable),
                            entry.protocol == ProfileImport.Protocol.OPENVPN ? "OpenVPN" : "WireGuard",
                            entry.readable, isSelected && entry.readable, entry.readable ? login : LOGIN_NONE));
                }
                savedMode = preferences.getMode();
                selectedId = selected;
                needsCredentials = credentials;
                int message = available ? notice : R.string.vpn_profiles_selection_unavailable;
                String shown = message == 0 ? null : text(message);
                boolean failed = error;
                if (!available) failed = false;
                if (shown != null && reason != null) shown = shown + "\n\n" + app.getString(R.string.vpn_profiles_import_reason, reason);
                publish(new State(false, true, shared, modeCode(savedMode), Collections.unmodifiableList(list),
                        shown, failed, askCredentials));
            } catch (Exception e) {
                publish(new State(false, true, state.shared, state.mode, state.profiles,
                        text(R.string.vpn_profiles_storage_failed), true, null));
            }
        });
    }

    public void selectProfile(String id) {
        if (state.busy) return;
        setBusy(true);
        run(() -> {
            int message = R.string.vpn_profiles_selection_saved;
            boolean failed = false;
            try {
                AndroidVpnSettings settings = settings();
                if (id == null) settings.selectProfile(null);
                else {
                    // Re-authenticate storage immediately before selecting; the list may be stale.
                    try (ProfileEnvelope.Opened opened = store.open(id)) {
                        settings.selectProfile(id);
                    }
                }
                VpnConnectionService.settingsChanged(app, true);
            } catch (Exception e) {
                message = R.string.vpn_profiles_selection_failed;
                failed = true;
            }
            refreshWith(message, failed, null, null);
        });
    }

    public void remove(String id) {
        if (state.busy) return;
        setBusy(true);
        run(() -> {
            int message = R.string.vpn_profiles_removed;
            boolean failed = false;
            try {
                // Clear the reference first so an interrupted deletion cannot leave a selected target.
                settings().forgetVpn(id);
                VpnConnectionService.settingsChanged(app);
                store.delete(id);
            } catch (Exception e) {
                message = R.string.vpn_profiles_remove_failed;
                failed = true;
            }
            refreshWith(message, failed, null, null);
        });
    }

    public void setMode(int code) {
        if (state.busy) return;
        ProtectionMode mode = modeOf(code);
        setBusy(true);
        run(() -> {
            int message = R.string.vpn_scope_saved;
            boolean failed = false;
            try {
                settings().saveMode(mode);
                VpnConnectionService.settingsChanged(app, true);
            } catch (Exception e) {
                message = R.string.vpn_scope_failed;
                failed = true;
            }
            refreshWith(message, failed, null, null);
        });
    }

    public void setShared(boolean shared) {
        if (state.busy) return;
        setBusy(true);
        run(() -> {
            int message = R.string.vpn_scope_saved;
            boolean failed = false;
            try {
                settings().setShared(shared);
                VpnConnectionService.settingsChanged(app, true);
            } catch (IOException e) {
                message = R.string.vpn_scope_failed;
                failed = true;
            }
            refreshWith(message, failed, null, null);
        });
    }

    /** Same as the "Turn off" button: ends a running connection, or sets the scope to Off. */
    public void turnOff() {
        if (state.busy) return;
        if (savedMode == ProtectionMode.OFF) VpnConnectionService.disconnect(app);
        else setMode(MODE_OFF);
    }

    public void importProfile(Uri uri, boolean openVpn) {
        if (state.busy) return;
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            publish(new State(false, true, state.shared, state.mode, state.profiles,
                    text(R.string.vpn_profiles_import_failed), true, null));
            return;
        }
        ProfileImport.Protocol protocol = openVpn ? ProfileImport.Protocol.OPENVPN : ProfileImport.Protocol.WIREGUARD;
        publish(new State(true, true, state.shared, state.mode, state.profiles,
                text(R.string.vpn_profiles_importing), false, null));
        run(() -> {
            int message = R.string.vpn_profiles_imported;
            boolean failed = false;
            String reason = null;
            String prompt = null;
            try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Configuration is unavailable.");
                try (ProfileImport.Candidate candidate = ProfileImport.read(input, protocol)) {
                    if (protocol == ProfileImport.Protocol.WIREGUARD) WireGuardProfiles.parse(candidate);
                    String id = java.util.UUID.randomUUID().toString();
                    if (protocol == ProfileImport.Protocol.OPENVPN && OpenVpnSession.evaluate(candidate.copyContents()) == 2)
                        prompt = id;
                    store.save(id, displayName(uri, protocol), candidate);
                }
                // Keep no URI grant, plaintext copy, or persisted document reference.
            } catch (Exception | LinkageError e) {
                message = R.string.vpn_profiles_import_failed;
                failed = true;
                reason = importFailureReason(e);
                prompt = null;
            }
            refreshWith(message, failed, reason, prompt);
        });
    }

    /** Plain reason for a failed import. Only fixed app messages are shown, never file contents. */
    private String importFailureReason(Throwable e) {
        if (e instanceof LinkageError) return text(R.string.vpn_import_reason_engine);
        if (e instanceof java.security.GeneralSecurityException) return text(R.string.vpn_import_reason_storage);
        String message = e.getMessage();
        if (e instanceof IOException && message != null && message.length() <= 200) return message;
        return text(R.string.vpn_import_reason_other);
    }

    public void saveCredentials(String id, String username, String password) {
        if (state.busy) return;
        setBusy(true);
        run(() -> {
            int message;
            boolean failed = false;
            try {
                byte[] revision = store.revision(id);
                VpnBackup.updateCredentials(app, id, revision, username, password);
                if (id.equals(settings().read().getSelectedProfileId()))
                    VpnPlaybackProtection.requestAutomaticAppWide(true);
                message = R.string.vpn_credentials_saved;
            } catch (Exception | LinkageError failure) {
                message = R.string.vpn_credentials_failed;
                failed = true;
            }
            refreshWith(message, failed, null, null);
        });
    }

    private String displayName(Uri uri, ProfileImport.Protocol protocol) {
        String name = null;
        try (Cursor cursor = app.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { /* Display names are optional/untrusted metadata. */ }
        if (name != null) {
            name = name.replaceFirst("(?i)\\.(ovpn|conf)$", "")
                    .replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim();
            if (name.length() > 60) name = name.substring(0, 60);
        }
        return name == null || name.isEmpty() ? (protocol == ProfileImport.Protocol.OPENVPN ? "OpenVPN" : "WireGuard") : name;
    }

    /** The live connection card. Cheap; the screen reads it a couple of times a second. */
    public Connection connection() {
        VpnConnectionService.Snapshot snapshot = VpnConnectionService.snapshot();
        boolean active = VpnConnectionService.isRunning();
        boolean verifying = active && snapshot.phase != VpnConnectionService.Phase.STANDBY;
        ProtectionMode saved = savedMode;
        int title = snapshot.phase == VpnConnectionService.Phase.CONNECTED
                ? (snapshot.mode.isPlaybackScoped() ? (snapshot.mode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_connected_web : R.string.vpn_connected_http) : R.string.vpn_connected_app)
                : snapshot.phase == VpnConnectionService.Phase.CONNECTING ? R.string.vpn_connecting
                : snapshot.phase == VpnConnectionService.Phase.WAITING ? R.string.vpn_waiting_handshake
                : snapshot.phase == VpnConnectionService.Phase.ERROR ? R.string.vpn_connection_failed
                : R.string.vpn_connection_unavailable;
        int detail = snapshot.phase == VpnConnectionService.Phase.CONNECTED
                ? (snapshot.mode.isPlaybackScoped() ? (snapshot.mode == ProtectionMode.HTTP_HTTPS ? R.string.vpn_active_web_detail : R.string.vpn_active_http_detail) : R.string.vpn_active_app_detail)
                : active && snapshot.authRejected ? R.string.vpn_auth_rejected
                : active && snapshot.phase == VpnConnectionService.Phase.ERROR ? R.string.vpn_connection_error_detail
                : verifying ? R.string.vpn_verification_pending
                : selectedId == null ? R.string.vpn_select_wireguard_profile
                : needsCredentials ? R.string.vpn_credentials_required
                : saved == ProtectionMode.OFF ? R.string.vpn_requires_app_scope
                : saved.isPlaybackScoped() ? (saved == ProtectionMode.HTTP_HTTPS ? R.string.vpn_ready_web : R.string.vpn_ready_http) : R.string.vpn_ready_to_connect;
        String traffic = null;
        if (active) {
            traffic = app.getString(R.string.vpn_connection_traffic,
                    android.text.format.Formatter.formatShortFileSize(app, snapshot.received),
                    android.text.format.Formatter.formatShortFileSize(app, snapshot.sent));
            if (snapshot.egress != null) traffic = traffic + "\n" + app.getString(R.string.vpn_egress_address, snapshot.egress);
        }
        boolean canTurnOff = active || saved != ProtectionMode.OFF;
        return new Connection(text(title), text(detail), traffic, canTurnOff);
    }
}
