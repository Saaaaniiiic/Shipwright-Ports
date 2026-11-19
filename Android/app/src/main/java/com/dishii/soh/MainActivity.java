
package com.dishii.soh;
import org.libsdl.app.SDLActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;

import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Build;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.util.Log;

import android.view.KeyEvent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.graphics.Rect;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.Arrays;

import java.util.concurrent.Executors;
import android.app.AlertDialog;

import android.view.InputDevice;
import android.os.Vibrator;
import android.os.VibrationEffect;

//This class is the main SDLActivity and just sets up a bunch of default files
public class MainActivity extends SDLActivity{

    SharedPreferences preferences;
    private static final CountDownLatch setupLatch = new CountDownLatch(1);
    private volatile boolean mIsAiming = false;
    private static final int COPY_BUFFER_SIZE = 65536;
    private static final int RUMBLE_MAX_DURATION_MS = 5000;
    private static final String DATA_FOLDER_NAME = BuildConfig.DEBUG ? "SOH93-Test" : "SOH";
    private static String currentDataRootPath = "/storage/emulated/0/" + DATA_FOLDER_NAME;
    private static final String PREF_DATA_ROOT_PATH = "dataRootPath";
    private static final String PREF_LEGACY_DATA_MIGRATION_COMPLETE = "legacyDataMigrationComplete";
    private static final String PREF_TOUCH_CONTROLS_DISABLED = "touchControlsDisabled";
    // Legacy key name: true means the touch controls are hidden, not visible.
    private static final String PREF_TOUCH_CONTROLS_HIDDEN = "controlsVisible";
    private static final String PREF_TOUCH_LEFT_STICK_FLOATING = "touchLeftStickFloating";
    private static final String SUPPORT_FILES_VERSION_MARKER = ".android_support_files_version";
    // Bump only for support archive/layout changes. Extractor YAML is content-versioned separately.
    private static final String SUPPORT_FILES_VERSION = "soh-9.3.1-torch-support-2";
    private AlertDialog dataRootMigrationDialog;
    private AlertDialog setupProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i("SoH", "onCreate start");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Apply Android's immersive window style while support files are prepared,
        // before the native renderer creates its fullscreen window.
        setWindowStyle(true);

        preferences = getSharedPreferences("com.dishii.soh.prefs", Context.MODE_PRIVATE);
        updateCurrentDataRootPath();

        Log.i("SoH", "hasStoragePermission=" + hasStoragePermission());

        // Check if storage permissions are granted
        if (hasStoragePermission()) {
            beginSetupOrChooseDataRoot();
        } else {
            requestStoragePermission();
        }

        setupControllerOverlay();
        attachController();

        Log.i("SoH", "onCreate complete");
    }

    public static void waitForSetupFromNative() {
        try {
            setupLatch.await();  // Block until setup is complete
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static String getDataRootPathFromNative() {
        return currentDataRootPath;
    }

    private void doVersionCheck() {
        int currentVersion = BuildConfig.VERSION_CODE;
        int storedVersion = preferences.getInt("appVersion", 1);

        if (currentVersion > storedVersion) {
            preferences.edit().putInt("appVersion", currentVersion).apply();
        }
    }

    private void refreshBundledSupportFiles() {
        File targetRootFolder = getTargetRootFolder();

        // These files come from the APK. ROM archives are user-generated and
        // are retained across support updates; native version checks request
        // re-extraction only when their format is actually incompatible.
        deleteIfExists(new File(targetRootFolder, "soh.otr"));
        deleteIfExists(new File(targetRootFolder, "soh.o2r"));
        deleteIfExists(getSupportFilesMarkerFile(targetRootFolder));
    }

    private File getSupportFilesMarkerFile(File targetRootFolder) {
        return new File(targetRootFolder, SUPPORT_FILES_VERSION_MARKER);
    }

    private boolean isSupportFilesMarkerCurrent() {
        File markerFile = getSupportFilesMarkerFile(getTargetRootFolder());
        if (!markerFile.exists()) {
            return false;
        }

        try (InputStream in = new FileInputStream(markerFile)) {
            byte[] buffer = new byte[(int) markerFile.length()];
            int read = in.read(buffer);
            if (read <= 0) {
                return false;
            }
            String markerVersion = new String(buffer, 0, read).trim();
            return markerVersion.equals(SUPPORT_FILES_VERSION);
        } catch (IOException e) {
            Log.w("setupFiles", "Unable to read support files marker", e);
            return false;
        }
    }

    private void writeSupportFilesMarker(File targetRootFolder) throws IOException {
        File markerFile = getSupportFilesMarkerFile(targetRootFolder);
        try (OutputStream out = new FileOutputStream(markerFile)) {
            out.write(SUPPORT_FILES_VERSION.getBytes());
        }
    }

    private void deleteIfExists(File file) {
        if (file.exists()) {
            if (file.delete()) {
                Log.i("deleteAssets", "Deleted file: " + file.getAbsolutePath());
            } else {
                Log.w("deleteAssets", "Failed to delete file: " + file.getAbsolutePath());
            }
        } else {
            Log.i("deleteAssets", "File not found (skipped): " + file.getAbsolutePath());
        }
    }

    private void deleteRecursiveIfExists(File dir) throws IOException {
        if (dir.exists()) {
            deleteRecursive(dir);
            Log.i("deleteAssets", "Deleted directory: " + dir.getAbsolutePath());
        } else {
            Log.i("deleteAssets", "Directory not found (skipped): " + dir.getAbsolutePath());
        }
    }

    private void deleteRecursive(File fileOrDirectory) throws IOException {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children == null) {
                throw new IOException("Unable to list bundled asset directory: " + fileOrDirectory);
            }
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        deleteRequiredFile(fileOrDirectory);
    }

    private void deleteRequiredFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to refresh bundled support file: " + file);
        }
    }

    private File getTargetRootFolder() {
        String configuredPath = preferences.getString(PREF_DATA_ROOT_PATH, null);
        if (configuredPath == null || configuredPath.isEmpty()) {
            return getDefaultDataRootFolder();
        }

        return new File(configuredPath);
    }

    private File getDefaultDataRootFolder() {
        return new File(Environment.getExternalStorageDirectory(), DATA_FOLDER_NAME);
    }

    private void updateCurrentDataRootPath() {
        currentDataRootPath = getTargetRootFolder().getAbsolutePath();
        Log.i("setupFiles", "Current data root: " + currentDataRootPath);
    }

    private void beginSetupOrChooseDataRoot() {
        if (!preferences.contains(PREF_DATA_ROOT_PATH)) {
            preferences.edit().putString(PREF_DATA_ROOT_PATH, getDefaultDataRootFolder().getAbsolutePath()).apply();
        }
        beginSetupIfStorageReady();
    }

    private static class DataRootOption {
        final String label;
        final File folder;

        DataRootOption(String label, File folder) {
            this.label = label;
            this.folder = folder;
        }
    }

    private List<DataRootOption> getDataRootOptions() {
        Map<String, DataRootOption> options = new LinkedHashMap<>();
        File defaultFolder = getDefaultDataRootFolder();
        options.put(defaultFolder.getAbsolutePath(),
                new DataRootOption("Internal storage: " + defaultFolder.getAbsolutePath(), defaultFolder));

        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File externalDir : externalDirs) {
                if (externalDir == null || !Environment.isExternalStorageRemovable(externalDir)) {
                    continue;
                }

                File volumeRoot = getVolumeRootFromExternalFilesDir(externalDir);
                if (volumeRoot == null) {
                    continue;
                }

                File sdFolder = new File(volumeRoot, DATA_FOLDER_NAME);
                options.put(sdFolder.getAbsolutePath(),
                        new DataRootOption("SD card: " + sdFolder.getAbsolutePath(), sdFolder));
            }
        }

        return new ArrayList<>(options.values());
    }

    private File getVolumeRootFromExternalFilesDir(File externalDir) {
        String path = externalDir.getAbsolutePath();
        String suffix = "/Android/data/" + getPackageName() + "/files";
        int suffixIndex = path.indexOf(suffix);
        if (suffixIndex > 0) {
            return new File(path.substring(0, suffixIndex));
        }

        File parent = externalDir;
        for (int i = 0; i < 4 && parent != null; i++) {
            parent = parent.getParentFile();
        }
        return parent;
    }

    private void showDataRootChooser(boolean restartRequired) {
        List<DataRootOption> options = getDataRootOptions();

        if (options.size() < 2) {
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Change Data Folder")
                    .setMessage("No alternate writable data folder was found. Insert or mount an SD card and try again.")
                    .setPositiveButton("OK", null)
                    .setCancelable(true)
                    .show());
            return;
        }

        runOnUiThread(() -> {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            TextView message = new TextView(this);
            message.setText("Choose where Ship of Harkinian stores saves, mods, settings, and support files.");
            message.setTextColor(Color.BLACK);
            layout.addView(message);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Change Data Folder")
                    .setView(layout)
                    .setNegativeButton("Cancel", null)
                    .setCancelable(true)
                    .create();

            for (DataRootOption option : options) {
                Button optionButton = new Button(this);
                optionButton.setAllCaps(false);
                optionButton.setText(option.label);
                optionButton.setTextColor(Color.BLACK);
                optionButton.setOnClickListener((view) -> {
                    dialog.dismiss();
                    Executors.newSingleThreadExecutor()
                            .execute(() -> applyDataRootSelection(option.folder, restartRequired));
                });
                layout.addView(optionButton);
            }

            dialog.show();
        });
    }

    private void applyDataRootSelection(File targetRootFolder, boolean restartRequired) {
        File previousRoot = getTargetRootFolder();
        Log.i("setupFiles", "Changing data root from " + previousRoot.getAbsolutePath() +
                " to " + targetRootFolder.getAbsolutePath());
        preferences.edit().putString(PREF_DATA_ROOT_PATH, targetRootFolder.getAbsolutePath()).apply();
        updateCurrentDataRootPath();

        if (!ensureTargetRootFolderReady(targetRootFolder)) {
            preferences.edit().putString(PREF_DATA_ROOT_PATH, previousRoot.getAbsolutePath()).apply();
            updateCurrentDataRootPath();
            showSetupFailure("The selected data folder is not writable.");
            return;
        }

        boolean willMigrateData = shouldMigrateExistingRoot(previousRoot, targetRootFolder) ||
                shouldMigrateExistingRoot(getDefaultDataRootFolder(), targetRootFolder);
        if (willMigrateData) {
            showDataRootMigrationDialog();
        }
        migrateExistingRootIfNeeded(previousRoot, targetRootFolder);
        migrateExistingRootIfNeeded(getDefaultDataRootFolder(), targetRootFolder);
        if (willMigrateData) {
            dismissDataRootMigrationDialog();
        }

        if (restartRequired) {
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Restart Required")
                    .setMessage("Ship of Harkinian will use the new data folder after restarting. Existing data was copied when needed; the old folder was left in place.")
                    .setCancelable(false)
                    .setPositiveButton("Close App", (dialog, which) -> finish())
                    .show());
            return;
        }

        beginSetupIfStorageReady();
    }

    private void showDataRootMigrationDialog() {
        runOnUiThread(() -> {
            if (dataRootMigrationDialog != null && dataRootMigrationDialog.isShowing()) {
                return;
            }

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            ProgressBar progressBar = new ProgressBar(this);
            progressBar.setIndeterminate(true);
            layout.addView(progressBar);

            TextView message = new TextView(this);
            message.setText("Copying saves, mods, settings, and support files. This may take a few minutes.");
            message.setTextColor(Color.BLACK);
            layout.addView(message);

            dataRootMigrationDialog = new AlertDialog.Builder(this)
                    .setTitle("Moving Data Folder")
                    .setView(layout)
                    .setCancelable(false)
                    .create();
            dataRootMigrationDialog.show();
        });
    }

    private void dismissDataRootMigrationDialog() {
        runOnUiThread(() -> {
            if (dataRootMigrationDialog != null && dataRootMigrationDialog.isShowing()) {
                dataRootMigrationDialog.dismiss();
            }
            dataRootMigrationDialog = null;
        });
    }

    private void showSetupProgressDialog(String title, String text) {
        runOnUiThread(() -> {
            if (setupProgressDialog != null && setupProgressDialog.isShowing()) {
                return;
            }

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            ProgressBar progressBar = new ProgressBar(this);
            progressBar.setIndeterminate(true);
            layout.addView(progressBar);

            TextView message = new TextView(this);
            message.setText(text);
            message.setTextColor(Color.BLACK);
            layout.addView(message);

            setupProgressDialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(layout)
                    .setCancelable(false)
                    .create();
            setupProgressDialog.show();
        });
    }

    private void dismissSetupProgressDialog() {
        runOnUiThread(() -> {
            if (setupProgressDialog != null && setupProgressDialog.isShowing()) {
                setupProgressDialog.dismiss();
            }
            setupProgressDialog = null;
        });
    }

    private void migrateExistingRootIfNeeded(File sourceRoot, File targetRoot) {
        if (!shouldMigrateExistingRoot(sourceRoot, targetRoot)) {
            return;
        }

        try {
            Log.i("setupFiles", "Copying existing data from " + sourceRoot.getAbsolutePath() +
                    " to " + targetRoot.getAbsolutePath());
            AssetCopyUtil.copyDirectoryContentsNoOverwrite(sourceRoot, targetRoot);
            Log.i("setupFiles", "Copied existing data from: " + sourceRoot.getAbsolutePath());
        } catch (IOException e) {
            Log.e("setupFiles", "Failed to copy existing data from: " + sourceRoot.getAbsolutePath(), e);
            runOnUiThread(() -> Toast.makeText(this, "Could not copy existing data", Toast.LENGTH_LONG).show());
        }
    }

    private boolean shouldMigrateExistingRoot(File sourceRoot, File targetRoot) {
        return sourceRoot != null && targetRoot != null &&
                !sourceRoot.getAbsolutePath().equals(targetRoot.getAbsolutePath()) &&
                sourceRoot.isDirectory() && isDirectoryEmpty(targetRoot);
    }

    private boolean isDirectoryEmpty(File directory) {
        File[] files = directory.listFiles();
        return files == null || files.length == 0;
    }



    // Check if storage permission is granted
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 and above
            return Environment.isExternalStorageManager();
        } else {
            // Android 10 and below
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED;
        }
    }

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2296;
    private static final int FILE_PICKER_REQUEST_CODE = 0;

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ → MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                beginSetupOrChooseDataRoot();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–10 → request READ/WRITE at runtime
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_REQUEST_CODE);
        } else {
            // Below Android 6 → permissions granted at install time
            beginSetupOrChooseDataRoot();
        }
    }

    private void beginSetupIfStorageReady() {
        updateCurrentDataRootPath();
        File targetRootFolder = getTargetRootFolder();
        Log.i("setupFiles", "Beginning setup for data root: " + targetRootFolder.getAbsolutePath());
        if (!ensureTargetRootFolderReady(targetRootFolder)) {
            showStorageAccessFailure();
            return;
        }

        doVersionCheck();
        checkAndSetupFiles();
    }

    private boolean ensureTargetRootFolderReady(File targetRootFolder) {
        if (!isExternalStorageWritable() || !hasStoragePermission()) {
            return false;
        }

        if (!targetRootFolder.exists() && !targetRootFolder.mkdirs()) {
            Log.e("setupFiles", "Failed to create root folder: " + targetRootFolder.getAbsolutePath());
            return false;
        }

        if (!targetRootFolder.isDirectory() || !targetRootFolder.canWrite()) {
            Log.e("setupFiles", "Root folder is not writable: " + targetRootFolder.getAbsolutePath());
            return false;
        }

        File probeFile = new File(targetRootFolder, ".write_test");
        try (OutputStream out = new FileOutputStream(probeFile)) {
            out.write(0);
        } catch (IOException e) {
            Log.e("setupFiles", "Write probe failed for: " + targetRootFolder.getAbsolutePath(), e);
            return false;
        }

        if (probeFile.exists() && !probeFile.delete()) {
            Log.w("setupFiles", "Failed to delete write probe: " + probeFile.getAbsolutePath());
        }

        return true;
    }

    public void checkAndSetupFiles() {
        File targetRootFolder = getTargetRootFolder();
        File assetsFolder = new File(targetRootFolder, "assets");
        File sohOtrFile = new File(targetRootFolder, "soh.o2r");

        boolean isMissingAssets = !assetsFolder.exists() || assetsFolder.listFiles() == null || assetsFolder.listFiles().length == 0;
        boolean isMissingSohOtr = !sohOtrFile.isFile() || sohOtrFile.length() == 0;

        if (!targetRootFolder.exists() || isMissingAssets || isMissingSohOtr || !isSupportFilesMarkerCurrent()
                || !ExtractorAssetState.isCurrent(targetRootFolder, getBundledExtractorVersion())) {
            showSetupProgressDialog("Preparing Data Folder",
                    "Copying required support files. Please keep Ship of Harkinian open; SD cards may take a few minutes.");
            Executors.newSingleThreadExecutor().execute(() -> setupFilesInBackground(targetRootFolder));
        } else {
            setupLatch.countDown();
        }
    }

    private String getBundledExtractorVersion() {
        try (InputStream input = getAssets().open("extractor.version")) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            String revision = count > 0 ? new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            return revision.matches("[0-9a-f]{64}") ? revision : null;
        } catch (IOException e) {
            Log.e("setupFiles", "Missing bundled extractor revision", e);
            return null;
        }
    }

    private void setupFilesInBackground(File targetRootFolder) {
        boolean setupFailed = false;
        Log.i("setupFiles", "Copying support files to: " + targetRootFolder.getAbsolutePath());

        if (!ensureTargetRootFolderReady(targetRootFolder)) {
            dismissSetupProgressDialog();
            showStorageAccessFailure();
            return;
        }

        String extractorVersion = getBundledExtractorVersion();
        if (extractorVersion == null) {
            dismissSetupProgressDialog();
            showSetupFailure("The bundled extractor version is missing. Please install a complete APK build.");
            return;
        }
        // Read legacy state before refreshing its support marker. Code/shader-only
        // updates must not delete and recopy over 20,000 unchanged YAML files.
        boolean copyExtractor = ExtractorAssetState.needsCopy(targetRootFolder, extractorVersion);
        if (!isSupportFilesMarkerCurrent()) {
            refreshBundledSupportFiles();
        }

        migrateLegacyAppDataIfNeeded(targetRootFolder);

        // Always ensure mods folder exists
        File targetModsDir = new File(targetRootFolder, "mods");
        if (!targetModsDir.exists() && !targetModsDir.mkdirs()) {
            dismissSetupProgressDialog();
            showSetupFailure("Failed to create the mods folder.");
            return;
        }

        // Copy assets/ from internal
        File targetAssetsDir = new File(targetRootFolder, "assets");
        try {
            if (copyExtractor) {
                // Invalidate first: an interrupted copy must never look complete.
                deleteRequiredFile(getSupportFilesMarkerFile(targetRootFolder));
                deleteRequiredFile(new File(targetRootFolder, ExtractorAssetState.MARKER));
                deleteRecursiveIfExists(targetAssetsDir);
                if (!targetAssetsDir.mkdirs() && !targetAssetsDir.isDirectory()) {
                    throw new IOException("Failed to create assets folder: " + targetAssetsDir.getAbsolutePath());
                }
                // Only config.yml is needed up front. Each ROM version's YAML (about 1,450 of the 20,000
                // files) is copied by ensureExtractorVersionFromNative once a ROM has been chosen.
                AssetCopyUtil.copyTopLevelFiles(this, "assets", targetAssetsDir.getAbsolutePath());
                Log.i("setupFiles", "Extractor config copied for revision " + extractorVersion);
            } else {
                Log.i("setupFiles", "Reusing unchanged extractor files");
            }
            ExtractorAssetState.markComplete(targetRootFolder, extractorVersion);
        } catch (IOException e) {
            e.printStackTrace();
            setupFailed = true;
            runOnUiThread(() -> Toast.makeText(this, "Error copying assets", Toast.LENGTH_LONG).show());
        }

        // The matching support archive is required and bundled with this APK.
        File supportArchive = new File(targetRootFolder, "soh.o2r");
        if (!isSupportFilesMarkerCurrent() || !supportArchive.isFile() || supportArchive.length() == 0) {
            try (InputStream assetIn = getAssets().open("soh.o2r")) {
                File targetOtrFile = new File(targetRootFolder, "soh.o2r");
                targetOtrFile.delete();
                try (OutputStream out = new FileOutputStream(targetOtrFile)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int read;
                    while ((read = assetIn.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, "soh.o2r copied", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                Log.e("setupFiles", "Failed to copy bundled soh.o2r", e);
                setupFailed = true;
            }
        }

        if (setupFailed) {
            dismissSetupProgressDialog();
            showSetupFailure("Required support files could not be copied. Please install a complete APK build.");
            return;
        }

        try {
            writeSupportFilesMarker(targetRootFolder);
        } catch (IOException e) {
            Log.e("setupFiles", "Failed to write support files marker", e);
            dismissSetupProgressDialog();
            showSetupFailure("Required support files could not be finalized.");
            return;
        }

        dismissSetupProgressDialog();
        setupLatch.countDown();
    }

    private static final String VERSION_COPIED_MARKER = ".copied";

    /**
     * Called by the native extractor (on its own thread) once it knows which ROM version it is
     * extracting. Copies config.yml and that version's YAML (about 1,450 small files) out of the APK
     * into the app's private storage and returns that folder, which the extractor then reads.
     * Private storage is used because creating thousands of small files on shared storage
     * (/storage/emulated/0, a FUSE mount) takes minutes, while here it takes a second or two.
     * Returns null on failure.
     */
    public synchronized String ensureExtractorVersionFromNative(String versionDir) {
        if (versionDir == null || !versionDir.matches("[A-Za-z0-9_\\-]+")) {
            Log.e("setupFiles", "Rejected extractor version folder: " + versionDir);
            return null;
        }
        String revision = getBundledExtractorVersion();
        if (revision == null) return null;
        File root = new File(getFilesDir(), "extractor");
        File versionFolder = new File(root, versionDir);
        File marker = new File(versionFolder, VERSION_COPIED_MARKER);
        File config = new File(root, "config.yml");
        if (revision.equals(ExtractorAssetState.readMarker(marker)) && config.isFile()) {
            return root.getAbsolutePath();
        }
        try {
            long start = System.currentTimeMillis();
            // Anything already here is from another revision or an interrupted copy: start clean.
            deleteRecursiveIfExists(versionFolder);
            if (config.exists() && !config.delete()) throw new IOException("Failed to replace " + config);
            AssetCopyUtil.copyTopLevelFiles(this, "assets", root.getAbsolutePath());
            AssetCopyUtil.copyAssetsToExternal(this, "assets/" + versionDir, versionFolder.getAbsolutePath());
            try (OutputStream out = new FileOutputStream(marker)) {
                out.write(revision.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Log.i("setupFiles", "Extractor files for " + versionDir + " copied in "
                    + (System.currentTimeMillis() - start) + " ms");
            return root.getAbsolutePath();
        } catch (IOException e) {
            Log.e("setupFiles", "Failed to copy extractor files for " + versionDir, e);
            return null;
        }
    }

    private void migrateLegacyAppDataIfNeeded(File targetRootFolder) {
        if (preferences.getBoolean(PREF_LEGACY_DATA_MIGRATION_COMPLETE, false)) {
            return;
        }

        File sourceOldRoot = getExternalFilesDir(null);
        File sourceSavesDir = sourceOldRoot == null ? null : new File(sourceOldRoot, "Save");
        if (sourceOldRoot == null || sourceSavesDir == null || !sourceSavesDir.isDirectory()) {
            preferences.edit().putBoolean(PREF_LEGACY_DATA_MIGRATION_COMPLETE, true).apply();
            return;
        }

        Log.i("setupFiles", "Migrating old data without overwriting current files: " + sourceOldRoot.getAbsolutePath());

        File[] sourceFiles = sourceOldRoot.listFiles();
        if (sourceFiles != null) {
            for (File file : sourceFiles) {
                String name = file.getName();
                if (name.equals("assets") || name.equals("soh.otr") || name.equals("oot-mq.otr") ||
                        name.equals("oot.otr") || name.equals("soh.o2r") || name.equals("oot-mq.o2r") ||
                        name.equals("oot.o2r")) {
                    continue;
                }

                File dest = new File(targetRootFolder, name);
                try {
                    if (file.isDirectory()) {
                        AssetCopyUtil.copyDirectoryNoOverwrite(file, dest);
                    } else {
                        AssetCopyUtil.copyFileNoOverwrite(file, dest);
                    }
                    Log.i("setupFiles", "Migrated missing legacy data: " + name);
                } catch (IOException e) {
                    Log.e("setupFiles", "Failed to migrate legacy data: " + name, e);
                }
            }
        }

        preferences.edit().putBoolean(PREF_LEGACY_DATA_MIGRATION_COMPLETE, true).apply();
        runOnUiThread(() -> Toast.makeText(this, "Existing save data checked", Toast.LENGTH_SHORT).show());
    }

    private void showSetupFailure(String message) {
        setupLatch.countDown();
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Setup Failed")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Close", (dialog, which) -> finish())
                .show());
    }




    private native void nativeHandleSelectedFile(String filePath);
    private native void nativeDialogResult(int result);

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Handle file selection
            Uri selectedFileUri = data.getData();
            String fileName = "OOT.z64";

            File destinationDirectory = getTargetRootFolder();
            File destinationFile = new File(destinationDirectory, fileName);

            if (selectedFileUri != null && ensureTargetRootFolderReady(destinationDirectory)) {
                destinationFile.delete();
                try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
                     OutputStream out = new FileOutputStream(destinationFile)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    nativeHandleSelectedFile(null);
                    showSetupFailure("The selected file could not be copied into the SOH folder.");
                    return;
                }
            } else {
                showStorageAccessFailure();
                return;
            }

            if (destinationFile.exists() && destinationFile.length() > 0) {
                nativeHandleSelectedFile(destinationFile.getPath());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "Failed to copy ROM file", Toast.LENGTH_LONG).show());
                nativeHandleSelectedFile(null);
            }

        } else if (requestCode == FILE_PICKER_REQUEST_CODE) {
            nativeHandleSelectedFile(null);
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            // Handle MANAGE_EXTERNAL_STORAGE result
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    beginSetupOrChooseDataRoot();
                } else {
                    showStorageAccessFailure();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (hasStoragePermission()) {
                beginSetupOrChooseDataRoot();
            } else {
                showStorageAccessFailure();
            }
        }
    }

    public void showAlertDialog(String title, String message, String btn1, String btn2) {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(btn1, (dialog, which) -> nativeDialogResult(0));
            if (btn2 != null && !btn2.isEmpty()) {
                builder.setNegativeButton(btn2, (dialog, which) -> nativeDialogResult(1));
            }
            AlertDialog dialog = builder.create();
            // FLAG_NOT_FOCUSABLE prevents stealing SDL window focus so onWindowFocusChanged
            // is never called — SDL never fires FOCUS_LOST, ImGui keeps gamepad state intact,
            // SELECT/BACK menu toggle continues to work after dialog dismissal.
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            dialog.show();
        });
    }

    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        runOnUiThread(() -> startActivityForResult(intent, 0));
    }

    public void changeDataFolderFromNative() {
        showDataRootChooser(true);
    }

    public String getAndroidVersionFromNative() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.isEmpty()) {
            return "Android API " + Build.VERSION.SDK_INT;
        }

        return "Android " + release + " (API " + Build.VERSION.SDK_INT + ")";
    }

    // Check if external storage is available and writable
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    private void showStorageAccessFailure() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("The app needs file access to create and update the SOH folder. Please grant storage access and try again.")
                .setCancelable(false)
                .setPositiveButton("Open Settings", (dialog, which) -> requestStoragePermission())
                .setNegativeButton("Close", (dialog, which) -> finish())
                .show());
    }

    public native void attachController();
    public native void detachController();
    // Native method for setting button state
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);
    public native void setCameraTouchActive(boolean active);
    private native void setItemButtonPulse();
    private native void setItemButtonHeld(boolean held);

    // Native method for setting joystick axis value
    public native void setAxis(int axis, short value);

    // Signals C++ that the gamepad BACK/SELECT button was pressed, bypassing SDL.
    public native void nativeGamepadBackPressed();
    // Injects a directional menu nav key: dir 0=up 1=down 2=left 3=right.
    public native void nativeMenuNavKey(int dir, boolean pressed);

    public void SetFirstPersonAimingActive(boolean active) {
        mIsAiming = active;
    }

    @Override
    protected void onPause() {
        if (integratedTouchView != null) integratedTouchView.suspend();
        super.onPause();
        setItemButtonHeld(false);
        mIsAiming = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (integratedTouchView != null) integratedTouchView.resume();
    }

    @Override
    public void onBackPressed() {
        if (integratedTouchView == null || !integratedTouchView.cancelEditing()) super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int keyCode = event.getKeyCode();
            // SDL loses controller tracking after activity transitions; catch BACK at the activity level.
            boolean isGamepad = (event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) != 0
                             || (event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                    (keyCode == KeyEvent.KEYCODE_BACK && isGamepad)) {
                nativeGamepadBackPressed();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private IntegratedTouchView integratedTouchView;
    private boolean integratedLookHeld;
    native int integratedTouchState();
    native int[] integratedTouchBindings(int mode);
    native void integratedTouchButton(int mask, boolean down);
    native void integratedTouchStick(int x, int y);
    native void integratedTouchReset();
    native void integratedTouchLayout(float[] anchors, boolean active, boolean naviUtilityIcon);

    public void setIntegratedTouchFromNative(int mode, float scale, float opacity, boolean transparentSticks, boolean floatingRight) {
        // The right stick is gone (the camera is a touch area), so floatingRight is accepted but unused.
        preferences.edit().putInt("integratedTouchMode", Math.max(1, Math.min(3, mode)))
                .putFloat("integratedTouchScale", scale).putFloat("integratedTouchOpacity", opacity)
                .putBoolean("touchTransparentSticks", transparentSticks).apply();
        runOnUiThread(this::applyTouchControlsVisibility);
    }
    public void editIntegratedTouchFromNative() {
        runOnUiThread(() -> {
            if (preferences.getBoolean(PREF_TOUCH_CONTROLS_DISABLED, false)) {
                Toast.makeText(this, "Enable touch controls first.", Toast.LENGTH_LONG).show();
            } else if (integratedTouchView != null) integratedTouchView.startEditing();
        });
    }
    void integratedMenuButton(int mask, boolean down) {
        int direction = mask == 0x800 ? 0 : mask == 0x400 ? 1 : mask == 0x200 ? 2 :
                mask == 0x100 ? 3 : mask == 0x8000 ? 4 : mask == 0x4000 ? 5 : -1;
        if (direction >= 0) nativeMenuNavKey(direction, down);
    }
    native boolean integratedRightStick(int x, int y);
    void integratedLook(float x, float y, boolean active) {
        if (integratedRightStick(active ? Math.round(x*85) : 0, active ? Math.round(-y*85) : 0)) {
            resetRightTouch(); integratedLookHeld = false; return;
        }
        if (!active) { integratedLookHeld = false; resetRightTouch(); return; }
        if (mIsAiming) {
            if (!integratedLookHeld) { setItemButtonPulse(); setItemButtonHeld(true); }
            setAxis(ControllerButtons.AXIS_RX, (short) 0);
            setAxis(ControllerButtons.AXIS_RY, (short) 0);
            setCameraTouchActive(true);
            setCameraState(0, x * 170); setCameraState(1, y * 170);
        } else {
            setCameraTouchActive(false); setCameraState(0, 0); setCameraState(1, 0);
            setAxis(ControllerButtons.AXIS_RX, (short)(x * Short.MAX_VALUE));
            setAxis(ControllerButtons.AXIS_RY, (short)(y * Short.MAX_VALUE));
        }
        integratedLookHeld = true;
    }

    private void resetRightTouch() {
        setAxis(ControllerButtons.AXIS_RX, (short) 0);
        setAxis(ControllerButtons.AXIS_RY, (short) 0);
        setCameraTouchActive(false);
        setCameraState(0, 0.0f);
        setCameraState(1, 0.0f);
        if (mIsAiming) {
            setItemButtonHeld(false);
        }
    }

    // The integrated touch overlay is the only touch interface.
    private void setupControllerOverlay() {
        ViewGroup view = (ViewGroup) findViewById(android.R.id.content);
        view.setKeepScreenOn(true);
        integratedTouchView = new IntegratedTouchView(this);
        view.addView(integratedTouchView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyTouchControlsVisibility();
    }

    public void setTouchControlsDisabledFromNative(boolean disabled) {
        preferences.edit().putBoolean(PREF_TOUCH_CONTROLS_DISABLED, disabled).apply();
        runOnUiThread(this::applyTouchControlsVisibility);
    }

    public void setTouchLeftStickFloatingFromNative(boolean floating) {
        preferences.edit().putBoolean(PREF_TOUCH_LEFT_STICK_FLOATING, floating).apply();
        runOnUiThread(this::applyTouchControlsVisibility);
    }

    private void applyTouchControlsVisibility() {
        if (integratedTouchView == null) {
            return;
        }
        boolean touchControlsDisabled = preferences.getBoolean(PREF_TOUCH_CONTROLS_DISABLED, false);
        int mode = preferences.getInt("integratedTouchMode", 3);
        if (mode < 1 || mode > 3) mode = 3; // an old "Classic" setting becomes Modern
        integratedTouchView.configure(mode, !touchControlsDisabled,
                preferences.getBoolean(PREF_TOUCH_LEFT_STICK_FLOATING, true),
                true, // the camera touch area is always floating
                preferences.getFloat("integratedTouchScale", 1),
                preferences.getFloat("integratedTouchOpacity", .55f),
                preferences.getBoolean("touchTransparentSticks", false));
    }

    void SetToggleButtonVisible(boolean visible) {
        // Called by the engine; the eye button belongs to the integrated overlay now.
    }

    public void startRumble(int lowIntensity, int highIntensity) {
        int amplitude = Math.max(lowIntensity, highIntensity);
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
                (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
            Vibrator dv = device.getVibrator();
            if (dv != null && dv.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dv.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_DURATION_MS, amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    dv.vibrate(RUMBLE_MAX_DURATION_MS);
                }
                return;
            }
        }
        Vibrator sv = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (sv != null && sv.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sv.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_DURATION_MS, amplitude > 0 ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                sv.vibrate(RUMBLE_MAX_DURATION_MS);
            }
        }
    }

    public void stopRumble() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
                (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
            Vibrator dv = device.getVibrator();
            if (dv != null && dv.hasVibrator()) dv.cancel();
        }
        Vibrator sv = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (sv != null) sv.cancel();
    }

}
