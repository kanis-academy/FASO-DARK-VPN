package com.faso.dark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;


import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import io.nekohasekai.libbox.BoxService;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.PlatformInterface;

/**
 * Vrai VPN de Faso DARK.
 * - VMess, VLESS (Reality), Trojan, Shadowsocks, SOCKS, HTTP, JSON perso : moteur sing-box (libbox.aar).
 * - WireGuard : bibliothèque officielle wireguard-android.
 * L'application elle-même est exclue du tunnel : sa recherche d'IP et ses tests d'hôtes
 * voient donc le vrai réseau du téléphone.
 */
public class FasoVpnService extends VpnService {

    public interface Listener {
        void onStatus(String state, String message);
        void onTraffic(long rxPerSec, long txPerSec);
    }

    public static final String ACTION_CONNECT = "com.faso.dark.CONNECT";
    public static final String ACTION_DISCONNECT = "com.faso.dark.DISCONNECT";
    public static volatile Listener listener;
    public static volatile String state = "disconnected";

    private static final String CHANNEL_ID = "faso_dark_vpn";
    private static final int NOTIF_ID = 42;
    private static final String TUN_ADDRESS = "172.19.0.1";
    private static final int TUN_PREFIX = 30;
    private static final String TUN_DNS = "172.19.0.2";
    private static final int TUN_MTU = 9000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable statsLoop;
    private long lastRx, lastTx;

    private BoxService box;
    private ParcelFileDescriptor tun;
    private GoBackend wgBackend;
    private Tunnel wgTunnel;
    private volatile boolean stopping = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            final String kind = intent.getStringExtra("kind");
            final String config = intent.getStringExtra("config");
            connect(kind, config);
        } else if (ACTION_DISCONNECT.equals(action)) {
            teardown(true);
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void emit(String s, String msg) {
        state = s;
        Listener l = listener;
        if (l != null) l.onStatus(s, msg);
    }

    private void connect(final String kind, final String config) {
        stopping = false;
        emit("connecting", null);
        startFg("Connexion…");
        new Thread(() -> {
            try {
                stopEngines();
                if ("wireguard".equals(kind)) startWireGuard(config);
                else startSingBox(config);
                if (stopping) return;
                startFg("Connecté");
                emit("connected", null);
                ui.post(this::startStats);
            } catch (Throwable t) {
                Log.e("FasoDark", "connect", t);
                String m = t.getMessage();
                if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
                emit("error", m);
                ui.post(() -> teardown(false));
            }
        }, "faso-connect").start();
    }

    // ---------------------------------------------------------------- sing-box

    private static boolean libboxReady = false;

    /** libbox.setup() a changé de forme selon les versions de sing-box : essai souple. */
    private void setupLibbox() {
        if (libboxReady) return;
        libboxReady = true;
        try {
            String base = getFilesDir().getAbsolutePath();
            String work = getExternalFilesDir(null) != null ? getExternalFilesDir(null).getAbsolutePath() : base;
            String tmp = getCacheDir().getAbsolutePath();
            for (Method m : Libbox.class.getMethods()) {
                if (!m.getName().equals("setup")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == String.class && p[1] == String.class && p[2] == String.class) {
                    Object last = p[3] == boolean.class ? (Object) Boolean.FALSE : null;
                    m.invoke(null, base, work, tmp, last);
                    return;
                }
                if (p.length == 1) {
                    Object o = p[0].getDeclaredConstructor().newInstance();
                    trySet(o, "setBasePath", base);
                    trySet(o, "setWorkingPath", work);
                    trySet(o, "setTempPath", tmp);
                    m.invoke(null, o);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w("FasoDark", "libbox.setup: " + t);
        }
    }

    private static void trySet(Object o, String name, String v) {
        try { o.getClass().getMethod(name, String.class).invoke(o, v); } catch (Throwable ignored) { }
    }

    private void startSingBox(String config) throws Exception {
        setupLibbox();
        Libbox.checkConfig(config); // lève une erreur lisible si la config est fausse

        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "openTun": return openTun();
                case "usePlatformAutoDetectInterfaceControl": return Boolean.TRUE;
                case "autoDetectInterfaceControl": protect((Integer) args[0]); return null;
                case "writeLog": Log.d("sing-box", args != null && args.length > 0 ? String.valueOf(args[0]) : ""); return null;
                case "useProcFS": return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
                case "findConnectionOwner":
                case "uidByPackageName": return -1;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == (args != null && args.length > 0 ? args[0] : null);
                case "toString": return "FasoPlatform";
                default: return defaultFor(method.getReturnType());
            }
        };
        PlatformInterface platform = (PlatformInterface) Proxy.newProxyInstance(
                PlatformInterface.class.getClassLoader(), new Class<?>[]{PlatformInterface.class}, handler);

        BoxService svc = Libbox.newService(config, platform);
        box = svc;
        svc.start();
    }

    private int openTun() {
        Builder b = new Builder()
                .setSession("Faso DARK")
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS)
                .setMtu(TUN_MTU);
        try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) throw new IllegalStateException("Autorisation VPN refusée ou retirée");
        tun = pfd;
        return pfd.getFd();
    }

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0.0;
        if (t == String.class) return "";
        return null;
    }

    // --------------------------------------------------------------- WireGuard

    private void startWireGuard(String text) throws Exception {
        Config cfg = Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        wgBackend = new GoBackend(getApplicationContext());
        wgTunnel = new Tunnel() {
            @Override public String getName() { return "fasodark"; }
            @Override public void onStateChange(Tunnel.State s) { }
        };
        wgBackend.setState(wgTunnel, Tunnel.State.UP, cfg);
    }

    // ------------------------------------------------------------------- stats

    private void startStats() {
        stopStats();
        final int uid = Process.myUid();
        lastRx = Math.max(0, TrafficStats.getUidRxBytes(uid));
        lastTx = Math.max(0, TrafficStats.getUidTxBytes(uid));
        statsLoop = new Runnable() {
            @Override public void run() {
                long rx = Math.max(0, TrafficStats.getUidRxBytes(uid));
                long tx = Math.max(0, TrafficStats.getUidTxBytes(uid));
                Listener l = listener;
                if (l != null) l.onTraffic(Math.max(0, rx - lastRx), Math.max(0, tx - lastTx));
                lastRx = rx; lastTx = tx;
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(statsLoop, 1000);
    }

    private void stopStats() {
        if (statsLoop != null) ui.removeCallbacks(statsLoop);
        statsLoop = null;
    }

    // ---------------------------------------------------------------- teardown

    private void stopEngines() {
        try { if (box != null) box.close(); } catch (Throwable t) { Log.w("FasoDark", "box.close: " + t); }
        box = null;
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        tun = null;
        try { if (wgBackend != null && wgTunnel != null) wgBackend.setState(wgTunnel, Tunnel.State.DOWN, null); } catch (Throwable ignored) { }
        wgBackend = null; wgTunnel = null;
    }

    private void teardown(boolean notify) {
        stopping = true;
        stopStats();
        new Thread(this::stopEngines, "faso-stop").start();
        if (notify) emit("disconnected", null); else state = "disconnected";
        stopForeground(true);
        stopSelf();
    }

    @Override public void onRevoke() { teardown(true); super.onRevoke(); }

    @Override public void onDestroy() {
        stopStats();
        stopEngines();
        state = "disconnected";
        super.onDestroy();
    }

    // ------------------------------------------------------------ notification

    private void startFg(String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification notification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Faso DARK", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Faso DARK")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
