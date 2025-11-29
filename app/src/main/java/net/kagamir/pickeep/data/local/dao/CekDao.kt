package net.kagamir.pickeep.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.kagamir.pickeep.data.local.entity.ContentEncryptionKeyEntity

/**
 * 内容加密密钥数据访问对象
 */
@Dao
interface CekDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cek: ContentEncryptionKeyEntity)
    
    @Delete
    suspend fun delete(cek: ContentEncryptionKeyEntity)
    
    @Query("SELECT * FROM content_encryption_keys WHERE id = :id")
    suspend fun getById(id: String): ContentEncryptionKeyEntity?
    
    @Query("SELECT * FROM content_encryption_keys")
    suspend fun getAll(): List<ContentEncryptionKeyEntity>
    
    @Query("DELETE FROM content_encryption_keys WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("SELECT COUNT(*) FROM content_encryption_keys")
    suspend fun getCount(): Int
}

