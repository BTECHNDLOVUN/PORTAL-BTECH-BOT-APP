package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_accounts")
data class SavedAccountEntity(
    @PrimaryKey val broker: String,
    val platform: String,
    val login: String,
    val encryptedPassword: String,
    val server: String,
    val serverId: String,
    val terminalUrl: String,
    val authenticated: Boolean,
    val connectionStatus: String,
    val lastCheckedAt: String,
    val isActive: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "allowed_symbols")
data class AllowedSymbolEntity(
    @PrimaryKey val symbol: String,
    val platform: String,
    val lot: String,
    val direction: String,
    val trades: String,
    val live: Boolean,
)

@Dao
interface BtechDao {
    @Query("SELECT * FROM saved_accounts ORDER BY updatedAt DESC")
    fun observeAccounts(): Flow<List<SavedAccountEntity>>

    @Query("UPDATE saved_accounts SET isActive = 0")
    suspend fun clearActiveAccountFlags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: SavedAccountEntity)

    @Transaction
    suspend fun saveActiveAccount(account: SavedAccountEntity) {
        clearAllAccounts()
        upsertAccount(account.copy(isActive = true, updatedAt = System.currentTimeMillis()))
    }

    @Query("DELETE FROM saved_accounts")
    suspend fun clearAllAccounts()

    @Query("SELECT * FROM allowed_symbols ORDER BY symbol ASC")
    fun observeSymbols(): Flow<List<AllowedSymbolEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSymbols(symbols: List<AllowedSymbolEntity>)

    @Query("DELETE FROM allowed_symbols")
    suspend fun clearAllSymbols()

    @Transaction
    suspend fun replaceSymbols(symbols: List<AllowedSymbolEntity>) {
        clearAllSymbols()
        if (symbols.isNotEmpty()) {
            insertSymbols(symbols)
        }
    }
}

@Database(
    entities = [SavedAccountEntity::class, AllowedSymbolEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BtechDatabase : RoomDatabase() {
    abstract fun btechDao(): BtechDao

    companion object {
        @Volatile
        private var INSTANCE: BtechDatabase? = null

        fun getInstance(context: Context): BtechDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BtechDatabase::class.java,
                    "btech_finance.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
