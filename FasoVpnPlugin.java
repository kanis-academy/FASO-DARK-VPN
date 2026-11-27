package com.faso.dark;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Pont entre l'interface web (index.html) et le vrai VPN Android. */
@CapacitorPlugin(name = "FasoVpn")
public class FasoVpnPlugin extends Plugin {

    @Override
    public void load() {
        FasoVpnService.listener = new FasoVpnService.Listener() {
            @Override public void onStatus(String state, String message) {
                JSObject o = new JSObject();
                o.put("state", state);
                if (message != null) o.put("message", message);
                notifyListeners("status", o);
            }
            @Override public void onTraffic(long rx, long tx) {
                JSObject o = new JSObject();
                o.put("rx", rx);
                o.put("tx", tx);
                notifyListeners("traffic", o);
            }
        };
    }

    // ------------------------------------------------------------ autorisation

    @PluginMethod
    public void isPrepared(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", VpnService.prepare(getContext()) == null);
        call.resolve(r);
    }

    @PluginMethod
    public void prepare(PluginCall call) {
        Intent i = VpnService.prepare(getContext());
        if (i == null) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        startActivityForResult(call, i, "prepareResult");
    }

    @ActivityCallback
    private void prepareResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject r = new JSObject();
        r.put("granted", result.getResultCode() == Activity.RESULT_OK);
        call.resolve(r);
    }

    // --------------------------------------------------------------- connexion

    @PluginMethod
    public void connect(PluginCall call) {
        String kind = call.getString("kind", "singbox");
        String config = call.getString("config");
        if (config == null || config.isEmpty()) { call.reject("Configuration vide"); return; }
        if (VpnService.prepare(getContext()) != null) { call.reject("Autorisation VPN manquante"); return; }
        Intent i = new Intent(getContext(), FasoVpnService.class);
        i.setAction(FasoVpnService.ACTION_CONNECT);
        i.putExtra("kind", kind);
        i.putExtra("config", config);
        androidx.core.content.ContextCompat.startForegroundService(getContext(), i);
        call.resolve();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        Intent i = new Intent(getContext(), FasoVpnService.class);
        i.setAction(FasoVpnService.ACTION_DISCONNECT);
        getContext().startService(i);
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", FasoVpnService.state);
        call.resolve(r);
    }

    // ------------------------------------------------------------- IP / hôtes

    /** IP publique du téléphone (l'appli est hors du tunnel : c'est le vrai réseau). */
    @PluginMethod
    public void getIp(final PluginCall call) {
        new Thread(() -> {
            String[] urls = {"https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"};
            for (String u : urls) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("User-Agent", "FasoDark");
                    if (c.getResponseCode() == 200) {
                        InputStream in = c.getInputStream();
                        byte[] buf = new byte[128];
                        int n = in.read(buf);
                        in.close();
                        String ip = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
                        if (ip.matches("[0-9a-fA-F:.]{3,45}")) {
                            JSObject r = new JSObject();
                            r.put("ip", ip);
                            call.resolve(r);
                            return;
                        }
                    }
                } catch (Exception ignored) { }
            }
            call.reject("Impossible de lire l’IP : pas de connexion Internet");
        }, "faso-ip").start();
    }

    /** Test réel d'un hôte : résolution DNS puis connexion TCP (443, puis 80 si aucun port donné). */
    @PluginMethod
    public void testHost(final PluginCall call) {
        final String raw = call.getString("host", "");
        final int explicitPort = call.getInt("port", 0);
        final int timeout = call.getInt("timeout", 5000);
        new Thread(() -> {
            String h = raw.trim().replaceFirst("^[a-zA-Z]+://", "");
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
            int port = explicitPort;
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {
                try { port = Integer.parseInt(h.substring(colon + 1)); h = h.substring(0, colon); } catch (Exception ignored) { }
            }
            JSObject r = new JSObject();
            r.put("ok", false);
            r.put("ms", 0);
            int[] ports = port > 0 ? new int[]{port} : new int[]{443, 80};
            try {
                InetAddress a = InetAddress.getByName(h);
                for (int p : ports) {
                    long t0 = System.nanoTime();
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(a, p), timeout);
                        long ms = (System.nanoTime() - t0) / 1000000L;
                        r.put("ok", true);
                        r.put("ms", ms);
                        r.put("ip", a.getHostAddress());
                        r.put("port", p);
                        break;
                    } catch (Exception ignored) {
                    } finally {
                        try { s.close(); } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
            call.resolve(r);
        }, "faso-host").start();
    }

    // ------------------------------------------------------------- appareil

    @PluginMethod
    public void deviceInfo(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        r.put("id", id == null ? "" : id.toUpperCase());
        r.put("rooted", isRooted());
        r.put("wifi", onWifi(c));
        call.resolve(r);
    }

    private static boolean isRooted() {
        String tags = Build.TAGS;
        if (tags != null && tags.contains("test-keys")) return true;
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/data/local/su", "/su/bin/su"};
        for (String p : paths) if (new File(p).exists()) return true;
        return false;
    }

    private static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------- fichiers / partage

    /** Enregistre un fichier dans Téléchargements. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("name", "faso.fasodark");
        String data = call.getString("data", "");
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("Enregistrement refusé");
                try (OutputStream os = cr.openOutputStream(uri)) { os.write(bytes); }
            } else {
                File dir = getContext().getExternalFilesDir(null);
                try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Enregistrement impossible : " + e.getMessage());
        }
    }

    @PluginMethod
    public void shareText(PluginCall call) {
        String text = call.getString("text", "");
        String title = call.getString("title", "Faso DARK");
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(s, title);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(chooser);
        call.resolve();
    }
}
