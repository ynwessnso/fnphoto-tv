package com.fnphoto.tv.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageCacheManager {
    private static final String TAG = "ImageCacheManager";
    private static final String CACHE_DIR = "image_cache";
    private static final String ORIGINAL_CACHE_DIR = "image_cache_original";
    private static final long DEFAULT_CACHE_EXPIRY = 10 * 24 * 60 * 60 * 1000; // 10 * 24小时
    private static final long MAX_CACHE_SIZE = 200 * 1024 * 1024; // 200MB
    private long maxOriginalCacheSize = 500 * 1024 * 1024L; // 默认 500MB

    public void setMaxOriginalCacheSize(long size) {
        this.maxOriginalCacheSize = size;
    }

    public long getMaxOriginalCacheSize() {
        return maxOriginalCacheSize;
    }
    
    private static ImageCacheManager instance;
    private File cacheDir;
    private File originalCacheDir;
    private ExecutorService executorService;

    // LRU 内存缓存（最多 80 张缩略图）
    private static final int MEMORY_CACHE_SIZE = 80;
    private final LinkedHashMap<String, Bitmap> memoryCache = new LinkedHashMap<String, Bitmap>(
            MEMORY_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MEMORY_CACHE_SIZE;
        }
    };
    
    public static synchronized ImageCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new ImageCacheManager(context);
        }
        return instance;
    }
    
    private ImageCacheManager(Context context) {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        originalCacheDir = new File(context.getCacheDir(), ORIGINAL_CACHE_DIR);
        if (!originalCacheDir.exists()) {
            originalCacheDir.mkdirs();
        }
        executorService = Executors.newFixedThreadPool(4);
    }
    
    /**
     * 获取缓存的Bitmap
     * @param url 图片URL
     * @return 缓存的Bitmap，如果没有缓存或已过期则返回null
     */
    public Bitmap getCachedBitmap(String url) {
        // 1. 先查内存缓存（最快）
        synchronized (memoryCache) {
            Bitmap memBitmap = memoryCache.get(url);
            if (memBitmap != null && !memBitmap.isRecycled()) {
                return memBitmap;
            }
        }

        // 2. 再查磁盘缓存
        String fileName = hashUrl(url);
        File cacheFile = new File(cacheDir, fileName);

        if (!cacheFile.exists()) {
            return null;
        }

        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > DEFAULT_CACHE_EXPIRY) {
            cacheFile.delete();
            return null;
        }

        try {
            // 使用 inSampleSize 降低解码开销
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // 比 ARGB_8888 省一半内存
            Bitmap bitmap = BitmapFactory.decodeStream(new FileInputStream(cacheFile), null, opts);
            if (bitmap != null) {
                synchronized (memoryCache) {
                    memoryCache.put(url, bitmap);
                }
            }
            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "Error reading cache file", e);
            cacheFile.delete();
            return null;
        }
    }
    
    /**
     * 将Bitmap保存到缓存
     * @param url 图片URL
     * @param bitmap Bitmap对象
     */
    public void saveBitmapToCache(String url, Bitmap bitmap) {
        // 同时写入内存缓存
        synchronized (memoryCache) {
            memoryCache.put(url, bitmap);
        }

        executorService.execute(() -> {
            try {
                ensureCacheSize();

                String fileName = hashUrl(url);
                File cacheFile = new File(cacheDir, fileName);

                FileOutputStream fos = new FileOutputStream(cacheFile);
                // JPEG 格式，85% 质量 — 比 PNG 小 5-10 倍，编码快 3-5 倍
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error saving cache file", e);
            }
        });
    }
    
    /**
     * 检查缓存是否存在且有效
     * @param url 图片URL
     * @return true 如果缓存存在且未过期
     */
    public boolean isCacheValid(String url) {
        String fileName = hashUrl(url);
        File cacheFile = new File(cacheDir, fileName);
        
        if (!cacheFile.exists()) {
            return false;
        }
        
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        return age <= DEFAULT_CACHE_EXPIRY;
    }
    
    /**
     * 清空所有缓存
     */
    public void clearCache() {
        synchronized (memoryCache) {
            memoryCache.clear();
        }
        executorService.execute(() -> {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            Log.d(TAG, "Cache cleared");
        });
    }
    
    /**
     * 获取缓存文件
     */
    public File getCacheFile(String url) {
        String fileName = hashUrl(url);
        File cacheFile = new File(cacheDir, fileName);
        
        if (cacheFile.exists()) {
            // 检查是否过期
            long age = System.currentTimeMillis() - cacheFile.lastModified();
            if (age <= DEFAULT_CACHE_EXPIRY) {
                return cacheFile;
            } else {
                cacheFile.delete();
            }
        }
        return null;
    }
    
    /**
     * 获取缓存文件路径（用于Glide加载）
     */
    public String getCacheFilePath(String url) {
        File cacheFile = getCacheFile(url);
        return cacheFile != null ? cacheFile.getAbsolutePath() : null;
    }
    
    /**
     * 确保缓存大小不超过限制
     */
    private void ensureCacheSize() {
        long totalSize = getCacheSize();
        if (totalSize > MAX_CACHE_SIZE) {
            // 删除最旧的文件
            File[] files = cacheDir.listFiles();
            if (files != null && files.length > 0) {
                // 按修改时间排序
                java.util.Arrays.sort(files, (f1, f2) -> 
                    Long.compare(f1.lastModified(), f2.lastModified()));
                
                // 删除最旧的50%文件
                int deleteCount = files.length / 2;
                for (int i = 0; i < deleteCount; i++) {
                    files[i].delete();
                }
                Log.d(TAG, "Cleaned " + deleteCount + " old cache files");
            }
        }
    }
    
    /**
     * 获取当前缓存大小
     */
    public long getCacheSize() {
        long size = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }
    
    /**
     * 使用MD5哈希URL作为文件名
     */
    private String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(url.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".jpg";
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(url.hashCode()) + ".jpg";
        }
    }
    
    // ==================== 原图缓存 ====================

    /**
     * 获取原图缓存文件（仅硬盘，不缓存到内存）
     */
    public File getOriginalCacheFile(String url) {
        String fileName = hashUrl(url);
        File cacheFile = new File(originalCacheDir, fileName);

        if (cacheFile.exists()) {
            long age = System.currentTimeMillis() - cacheFile.lastModified();
            if (age <= DEFAULT_CACHE_EXPIRY) {
                return cacheFile;
            } else {
                cacheFile.delete();
            }
        }
        return null;
    }

    /**
     * 保存原图到硬盘缓存（异步）
     */
    public void saveOriginalToCache(String url, Bitmap bitmap) {
        executorService.execute(() -> {
            try {
                ensureOriginalCacheSize();
                String fileName = hashUrl(url);
                File cacheFile = new File(originalCacheDir, fileName);
                FileOutputStream fos = new FileOutputStream(cacheFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();
                Log.d(TAG, "原图已缓存: " + cacheFile.length() / 1024 + "KB");
            } catch (IOException e) {
                Log.e(TAG, "原图缓存失败", e);
            }
        });
    }

    /**
     * 清空原图缓存
     */
    public void clearOriginalCache() {
        File[] files = originalCacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        Log.d(TAG, "原图缓存已清空");
    }

    public long getOriginalCacheSize() {
        long size = 0;
        File[] files = originalCacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }

    private void ensureOriginalCacheSize() {
        long totalSize = getOriginalCacheSize();
        if (totalSize > maxOriginalCacheSize) {
            File[] files = originalCacheDir.listFiles();
            if (files != null && files.length > 0) {
                java.util.Arrays.sort(files, (f1, f2) ->
                    Long.compare(f1.lastModified(), f2.lastModified()));
                int deleteCount = files.length / 2;
                for (int i = 0; i < deleteCount; i++) {
                    files[i].delete();
                }
                Log.d(TAG, "原图缓存清理: 删除 " + deleteCount + " 个文件");
            }
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
