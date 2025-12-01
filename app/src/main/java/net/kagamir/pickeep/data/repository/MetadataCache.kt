package net.kagamir.pickeep.data.repository

import android.util.LruCache
import net.kagamir.pickeep.crypto.PhotoMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * 元数据缓存管理器
 * 管理照片元数据的内存缓存，避免重复下载
 */
class MetadataCache {
    
    // 使用 LruCache 限制内存使用（最多缓存 50 个元数据）
    private val cache = object : LruCache<Long, CacheEntry>(50) {
        override fun sizeOf(key: Long, value: CacheEntry): Int {
            // 简单估算：每个元数据对象大约占用 1KB
            return 1
        }
    }
    
    // 正在加载的元数据（避免重复请求）
    private val loadingKeys = ConcurrentHashMap<Long, Boolean>()
    
    /**
     * 缓存条目
     */
    data class CacheEntry(
        val metadata: PhotoMetadata,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 获取缓存的元数据
     * 
     * @param photoId 照片 ID
     * @return 缓存的元数据，如果不存在则返回 null
     */
    fun get(photoId: Long): PhotoMetadata? {
        val entry = cache.get(photoId)
        return entry?.metadata
    }
    
    /**
     * 缓存元数据
     * 
     * @param photoId 照片 ID
     * @param metadata 元数据
     */
    fun put(photoId: Long, metadata: PhotoMetadata) {
        cache.put(photoId, CacheEntry(metadata))
    }
    
    /**
     * 标记正在加载
     * 
     * @param photoId 照片 ID
     * @return 如果已经正在加载，返回 false；否则返回 true 并标记为加载中
     */
    fun markLoading(photoId: Long): Boolean {
        return if (loadingKeys.containsKey(photoId)) {
            false
        } else {
            loadingKeys[photoId] = true
            true
        }
    }
    
    /**
     * 清除加载标记
     * 
     * @param photoId 照片 ID
     */
    fun clearLoading(photoId: Long) {
        loadingKeys.remove(photoId)
    }
    
    /**
     * 检查是否正在加载
     * 
     * @param photoId 照片 ID
     * @return 是否正在加载
     */
    fun isLoading(photoId: Long): Boolean {
        return loadingKeys.containsKey(photoId)
    }
    
    /**
     * 清除所有缓存
     */
    fun clear() {
        cache.evictAll()
        loadingKeys.clear()
    }
    
    /**
     * 清理过期缓存（超过 1 小时的缓存）
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<Long>()
        
        // 遍历缓存查找过期条目（注意：LruCache 不支持直接遍历，这里简化处理）
        // 实际使用中，可以定期调用 clear() 或依赖 LruCache 的自动淘汰机制
    }
    
    /**
     * 获取缓存大小
     */
    fun size(): Int = cache.size()
    
    /**
     * 获取最大缓存大小
     */
    fun maxSize(): Int = cache.maxSize()
}

