package com.dishii.soh;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AssetCopyUtil {

    private static final int COPY_THREADS = 6;

    /** Copies one bundled asset folder (recursively) with several writers at once. */
    public static void copyAssetsToExternal(Context context, String assetsFolderPath, String externalFolderPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        ArrayList<String[]> jobs = new ArrayList<>();
        collectAssets(assetManager, assetsFolderPath, externalFolderPath, jobs);
        copyAll(assetManager, jobs);
    }

    /**
     * Copies only the files directly inside a bundled folder, not its sub-folders.
     * The extractor YAML is split per ROM version; each version folder is copied on demand.
     */
    public static void copyTopLevelFiles(Context context, String assetsFolderPath, String externalFolderPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        String[] entries = assetManager.list(assetsFolderPath);
        if (entries == null) throw new IOException("Unable to list bundled assets: " + assetsFolderPath);
        File targetDir = new File(externalFolderPath);
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create asset directory: " + targetDir.getAbsolutePath());
        }
        ArrayList<String[]> jobs = new ArrayList<>();
        for (String entry : entries) {
            String assetPath = assetsFolderPath + File.separator + entry;
            if (isAssetDirectory(assetManager, assetPath, entry)) continue;
            jobs.add(new String[]{assetPath, externalFolderPath + File.separator + entry});
        }
        copyAll(assetManager, jobs);
    }

    // The bundled extractor tree holds only .yml files, so a ".yml" name is a file without asking the
    // AssetManager (a list() call per file made copying ~20,000 of them very slow).
    private static boolean isAssetDirectory(AssetManager assetManager, String assetPath, String name) throws IOException {
        if (name.endsWith(".yml")) return false;
        String[] children = assetManager.list(assetPath);
        return children != null && children.length > 0;
    }

    private static void collectAssets(AssetManager assetManager, String assetsFolderPath, String externalFolderPath,
                                      ArrayList<String[]> jobs) throws IOException {
        String[] assetFiles = assetManager.list(assetsFolderPath);
        if (assetFiles == null) throw new IOException("Unable to list bundled assets: " + assetsFolderPath);
        File externalDir = new File(externalFolderPath);
        if (!externalDir.isDirectory() && !externalDir.mkdirs()) {
            throw new IOException("Failed to create asset directory: " + externalDir.getAbsolutePath());
        }
        for (String assetFile : assetFiles) {
            String assetPath = assetsFolderPath + File.separator + assetFile;
            String externalPath = externalFolderPath + File.separator + assetFile;
            if (isAssetDirectory(assetManager, assetPath, assetFile)) {
                collectAssets(assetManager, assetPath, externalPath, jobs);
            } else {
                jobs.add(new String[]{assetPath, externalPath});
            }
        }
    }

    private static void copyAll(AssetManager assetManager, ArrayList<String[]> jobs) throws IOException {
        if (jobs.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(COPY_THREADS, jobs.size()));
        try {
            ArrayList<Future<Void>> results = new ArrayList<>(jobs.size());
            for (String[] job : jobs) {
                results.add(pool.submit(() -> {
                    copyAsset(assetManager, job[0], new File(job[1]));
                    return null;
                }));
            }
            for (Future<Void> result : results) {
                try {
                    result.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Asset copy interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // Existing files count as copied, but a file is written under a temporary name and renamed,
    // so an interrupted copy never leaves a truncated file that looks complete.
    private static void copyAsset(AssetManager assetManager, String assetPath, File target) throws IOException {
        if (target.isFile()) return;
        File parentDir = target.getParentFile();
        if (parentDir != null && !parentDir.isDirectory() && !parentDir.mkdirs() && !parentDir.isDirectory()) {
            throw new IOException("Failed to create asset parent directory: " + parentDir.getAbsolutePath());
        }
        File partial = new File(target.getPath() + ".part");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = assetManager.open(assetPath); OutputStream out = new FileOutputStream(partial)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Failed to finish copying " + target.getAbsolutePath());
        }
    }

    public static void copyDirectory(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create target directory: " + targetDir.getAbsolutePath());
        }
        File[] files = sourceDir.listFiles();
        if (files == null) {
            throw new IOException("Failed to list source directory: " + sourceDir.getAbsolutePath());
        }
        for (File file : files) {
            File dest = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, dest);
            } else {
                copyFile(file, dest);
            }
        }
    }

    public static void copyDirectoryNoOverwrite(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create target directory: " + targetDir.getAbsolutePath());
        }
        File[] files = sourceDir.listFiles();
        if (files == null) {
            throw new IOException("Failed to list source directory: " + sourceDir.getAbsolutePath());
        }
        for (File file : files) {
            File dest = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectoryNoOverwrite(file, dest);
            } else {
                copyFileNoOverwrite(file, dest);
            }
        }
    }

    public static void copyDirectoryContentsNoOverwrite(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create target directory: " + targetDir.getAbsolutePath());
        }

        File[] files = sourceDir.listFiles();
        if (files == null) {
            throw new IOException("Failed to list source directory: " + sourceDir.getAbsolutePath());
        }

        for (File file : files) {
            File dest = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectoryNoOverwrite(file, dest);
            } else {
                copyFileNoOverwrite(file, dest);
            }
        }
    }

    public static void copyFile(File source, File dest) throws IOException {
        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create target parent directory: " + parentDir.getAbsolutePath());
        }

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64*1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static void copyFileNoOverwrite(File source, File dest) throws IOException {
        if (dest.exists()) {
            return;
        }

        copyFile(source, dest);
    }
}
