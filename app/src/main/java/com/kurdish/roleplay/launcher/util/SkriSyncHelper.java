package com.kurdish.roleplay.launcher.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.kurdish.roleplay.game.SAMP;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SkriSyncHelper {
    private static final String TAG = "SkriSyncHelper";
    private static final String MANIFEST_URL = "http://10.25.60.18:8090/launcher/manifest.json";
    private static final String DEFAULT_GAME_ZIP_URL = "http://10.25.60.18:8090/launcher/game.zip";
    private static final String PREFS_NAME = "skri_mods_sync";

    private SkriSyncHelper() {}

    public static void launchWithSync(final Activity activity) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage("Verificando archivos y mods...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                File gameDir = GameStorage.getGameBaseDirectory(activity);
                if (!gameDir.exists()) gameDir.mkdirs();

                JSONObject manifest = fetchJson(MANIFEST_URL);
                boolean hasGameData = hasCoreGameData(gameDir);

                if (!hasGameData) {
                    String gameZipUrl = DEFAULT_GAME_ZIP_URL;
                    if (manifest != null) {
                        String u = manifest.optString("gameZipUrl", "");
                        if (!u.isEmpty()) gameZipUrl = u;
                    }
                    File zipFile = new File(GameStorage.getDownloadDirectory(activity), "game.zip");
                    downloadFile(activity, progress, "Descargando datos del juego...", gameZipUrl, zipFile);
                    updateStatus(activity, progress, "Extrayendo game.zip...", true, 0, 100);
                    unzipSafe(zipFile, gameDir, null);
                    zipFile.delete();
                }

                if (manifest != null) {
                    syncMods(activity, progress, gameDir, manifest.optJSONArray("mods"));
                }

                ConfigValidator.validateConfigFiles(activity);
                activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong("last_launch_ms", System.currentTimeMillis()).apply();

                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    Intent intent = new Intent(activity, SAMP.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(intent);
                    activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                });
            } catch (final Exception e) {
                Log.e(TAG, "Sync error", e);
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    if (hasCoreGameData(GameStorage.getGameBaseDirectory(activity))) {
                        Toast.makeText(activity, "Aviso de red, iniciando juego local...", Toast.LENGTH_SHORT).show();
                        ConfigValidator.validateConfigFiles(activity);
                        Intent intent = new Intent(activity, SAMP.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        activity.startActivity(intent);
                    } else {
                        Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private static boolean hasCoreGameData(File gameDir) {
        File anim = new File(gameDir, "anim/ped.ifp");
        File data = new File(gameDir, "data");
        return anim.exists() && data.exists() && data.isDirectory();
    }

    private static void syncMods(Activity activity, ProgressDialog progress, File gameDir, JSONArray mods) throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> activeIds = new HashSet<>();
        if (mods != null) {
            for (int i = 0; i < mods.length(); i++) {
                JSONObject mod = mods.optJSONObject(i);
                if (mod == null || !mod.optBoolean("enabled", true)) continue;
                String id = mod.optString("id", "");
                String url = mod.optString("url", "");
                String sha = mod.optString("sha256", mod.optString("version", "1"));
                String name = mod.optString("name", id);
                if (id.isEmpty() || url.isEmpty()) continue;
                activeIds.add(id);

                String installedSha = prefs.getString("sha_" + id, "");
                if (!sha.equals(installedSha)) {
                    File modZip = new File(GameStorage.getDownloadDirectory(activity), "mod_" + id + ".zip");
                    downloadFile(activity, progress, "Descargando mod: " + name, url, modZip);
                    updateStatus(activity, progress, "Instalando mod: " + name, true, 0, 100);
                    Set<String> extracted = new HashSet<>();
                    unzipSafe(modZip, gameDir, extracted);
                    modZip.delete();
                    prefs.edit()
                            .putString("sha_" + id, sha)
                            .putStringSet("files_" + id, extracted)
                            .apply();
                }
            }
        }

        Set<String> knownIds = new HashSet<>(prefs.getStringSet("known_ids", new HashSet<>()));
        for (String oldId : knownIds) {
            if (!activeIds.contains(oldId)) {
                Set<String> files = prefs.getStringSet("files_" + oldId, null);
                if (files != null) {
                    for (String rel : files) {
                        File f = new File(gameDir, rel);
                        if (f.exists() && f.isFile()) f.delete();
                    }
                }
                prefs.edit().remove("sha_" + oldId).remove("files_" + oldId).apply();
            }
        }
        prefs.edit().putStringSet("known_ids", activeIds).apply();
    }

    private static JSONObject fetchJson(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "Manifest fetch skipped: " + e.getMessage());
            return null;
        }
    }

    private static void downloadFile(Activity activity, ProgressDialog progress, String label, String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " descargando " + urlStr);
        long total = conn.getContentLength();
        InputStream in = new BufferedInputStream(conn.getInputStream());
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[32768];
        long done = 0;
        int n;
        long lastUi = 0;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            long now = System.currentTimeMillis();
            if (now - lastUi > 150) {
                lastUi = now;
                int pct = total > 0 ? (int) ((done * 100L) / total) : 0;
                updateStatus(activity, progress, label, total <= 0, pct, 100);
            }
        }
        out.flush();
        out.close();
        in.close();
    }

    private static void unzipSafe(File zipFile, File targetDir, Set<String> outRelativePaths) throws IOException {
        String canonicalDest = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buf = new byte[32768];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(canonicalDest)) {
                    throw new SecurityException("Zip entry fuera de destino: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    if (outRelativePaths != null) outRelativePaths.add(entry.getName());
                }
                zis.closeEntry();
            }
        }
    }

    private static void updateStatus(Activity activity, ProgressDialog progress, String msg, boolean indet, int val, int max) {
        activity.runOnUiThread(() -> {
            progress.setMessage(msg);
            progress.setIndeterminate(indet);
            if (!indet) {
                progress.setMax(max);
                progress.setProgress(val);
            }
        });
    }

    public static void checkQuickCrashLog(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long last = prefs.getLong("last_launch_ms", 0L);
        if (last <= 0) return;
        long elapsed = System.currentTimeMillis() - last;
        prefs.edit().putLong("last_launch_ms", 0L).apply();
        if (elapsed > 30000L) return;

        File logFile = new File(GameStorage.getGameBaseDirectory(activity), "samp_log.txt");
        if (!logFile.exists()) return;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > 18) lines.remove(0);
            }
            if (lines.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append("
");
            new AlertDialog.Builder(activity)
                    .setTitle("Diagnostico de cierre (samp_log.txt)")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }
}
