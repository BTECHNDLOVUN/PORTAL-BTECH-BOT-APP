package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.AllowedSymbolEntity
import com.example.data.local.BtechDatabase
import com.example.data.local.SavedAccountEntity
import com.example.model.AllowedSymbolConfig
import com.example.model.NavStyle
import com.example.model.PlatformType
import com.example.model.SavedAccount
import com.example.model.TradeDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import org.json.JSONArray
import org.json.JSONObject

private val Context.btechDataStore: DataStore<Preferences> by preferencesDataStore(name = "btech_prefs")

data class PersistedBtechData(
    val hasRobot: Boolean = false,
    val botName: String = "BTECH SCALPER V2.3",
    val botOwner: String = "Bee Blessings",
    val licenseKey: String = "",
    val accentHex: String = "#9138EE",
    val customColors: List<String> = List(16) { "" },
    val isDarkTheme: Boolean = false,
    val navStyle: NavStyle = NavStyle.INDICATOR,
    val profileName: String = "BTECH Client",
    val allowedSymbols: List<AllowedSymbolConfig> = emptyList(),
    val savedAccount: SavedAccount? = null,
    val savedAccountsByBroker: Map<String, SavedAccount> = emptyMap(),
)

class BtechPreferencesRepository(private val context: Context) {

    private val dao = BtechDatabase.getInstance(context).btechDao()

    private object Keys {
        val HAS_ROBOT = booleanPreferencesKey("has_robot")
        val BOT_NAME = stringPreferencesKey("bot_name")
        val BOT_OWNER = stringPreferencesKey("bot_owner")
        val LICENSE_KEY = stringPreferencesKey("license_key")
        val ACCENT_HEX = stringPreferencesKey("accent_hex")
        val CUSTOM_COLORS = stringPreferencesKey("custom_colors_json")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val NAV_STYLE = stringPreferencesKey("nav_style")
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val ALLOWED_SYMBOLS = stringPreferencesKey("allowed_symbols_json")
        val SAVED_ACCOUNT = stringPreferencesKey("saved_account_json")
    }

    val dataFlow: Flow<PersistedBtechData> = combine(
        context.btechDataStore.data,
        dao.observeAccounts(),
        dao.observeSymbols(),
    ) { prefs, accountEntities, symbolEntities ->
        val mappedAccounts = accountEntities.map { it.toModel() }
        val activeAccount = accountEntities.firstOrNull { it.isActive }?.toModel()
            ?: mappedAccounts.firstOrNull()
        PersistedBtechData(
            hasRobot = prefs[Keys.HAS_ROBOT] ?: false,
            botName = prefs[Keys.BOT_NAME] ?: "BTECH SCALPER V2.3",
            botOwner = prefs[Keys.BOT_OWNER] ?: "Bee Blessings",
            licenseKey = prefs[Keys.LICENSE_KEY] ?: "",
            accentHex = prefs[Keys.ACCENT_HEX] ?: "#9138EE",
            customColors = decodeCustomColors(prefs[Keys.CUSTOM_COLORS]),
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: false,
            navStyle = NavStyle.fromId(prefs[Keys.NAV_STYLE] ?: NavStyle.INDICATOR.id),
            profileName = prefs[Keys.PROFILE_NAME] ?: "BTECH Client",
            allowedSymbols = symbolEntities.map { it.toModel() },
            savedAccount = activeAccount,
            savedAccountsByBroker = mappedAccounts.associateBy { it.broker },
        )
    }.onStart {
        migrateLegacyPrefsToRoomIfNeeded()
    }

    private suspend fun migrateLegacyPrefsToRoomIfNeeded() {
        val prefs = context.btechDataStore.data.first()
        val legacySymbols = prefs[Keys.ALLOWED_SYMBOLS]
        val legacyAccount = prefs[Keys.SAVED_ACCOUNT]
        if (legacySymbols.isNullOrBlank() && legacyAccount.isNullOrBlank()) return

        if (!legacySymbols.isNullOrBlank()) {
            val decoded = decodeSymbols(legacySymbols)
            if (decoded.isNotEmpty()) {
                dao.replaceSymbols(decoded.map { it.toEntity() })
            }
        }
        if (!legacyAccount.isNullOrBlank()) {
            decodeAccount(legacyAccount)?.let { acc ->
                dao.saveActiveAccount(acc.toEntity(isActive = true))
            }
        }
        context.btechDataStore.edit {
            it.remove(Keys.ALLOWED_SYMBOLS)
            it.remove(Keys.SAVED_ACCOUNT)
        }
    }

    suspend fun setRobotConnected(hasRobot: Boolean, licenseKey: String = "") {
        context.btechDataStore.edit { prefs ->
            prefs[Keys.HAS_ROBOT] = hasRobot
            if (hasRobot) {
                prefs[Keys.LICENSE_KEY] = licenseKey
            } else {
                prefs.remove(Keys.LICENSE_KEY)
            }
        }
        if (!hasRobot) {
            dao.clearAllSymbols()
        }
    }

    suspend fun saveAllowedSymbols(symbols: List<AllowedSymbolConfig>) {
        dao.replaceSymbols(symbols.map { it.toEntity() })
    }

    suspend fun saveAccount(account: SavedAccount?) {
        if (account == null) {
            dao.clearAllAccounts()
        } else {
            dao.saveActiveAccount(account.toEntity(isActive = true))
        }
    }

    suspend fun setTheme(isDark: Boolean) {
        context.btechDataStore.edit { it[Keys.IS_DARK_THEME] = isDark }
    }

    suspend fun setAccent(hex: String) {
        context.btechDataStore.edit { it[Keys.ACCENT_HEX] = hex }
    }

    suspend fun saveCustomColors(colors: List<String>) {
        val normalized = List(16) { idx -> colors.getOrNull(idx).orEmpty() }
        val arr = JSONArray()
        normalized.forEach { arr.put(it) }
        context.btechDataStore.edit { it[Keys.CUSTOM_COLORS] = arr.toString() }
    }

    suspend fun setNavStyle(style: NavStyle) {
        context.btechDataStore.edit { it[Keys.NAV_STYLE] = style.id }
    }

    suspend fun setProfileName(name: String) {
        context.btechDataStore.edit { it[Keys.PROFILE_NAME] = name }
    }

    private fun AllowedSymbolConfig.toEntity() = AllowedSymbolEntity(
        symbol = symbol,
        platform = platform.name,
        lot = lot,
        direction = direction.name,
        trades = trades,
        live = live,
    )

    private fun AllowedSymbolEntity.toModel() = AllowedSymbolConfig(
        symbol = symbol,
        platform = runCatching { PlatformType.valueOf(platform) }.getOrDefault(PlatformType.MT5),
        lot = lot,
        direction = runCatching { TradeDirection.valueOf(direction) }.getOrDefault(TradeDirection.BOTH),
        trades = trades,
        live = live,
    )

    private fun SavedAccount.toEntity(isActive: Boolean) = SavedAccountEntity(
        broker = broker,
        platform = platform.name,
        login = login,
        encryptedPassword = CryptoHelper.encrypt(password),
        server = server,
        serverId = serverId,
        terminalUrl = terminalUrl,
        authenticated = authenticated,
        connectionStatus = connectionStatus,
        lastCheckedAt = lastCheckedAt,
        isActive = isActive,
    )

    private fun SavedAccountEntity.toModel() = SavedAccount(
        broker = broker,
        platform = runCatching { PlatformType.valueOf(platform) }.getOrDefault(PlatformType.MT5),
        login = login,
        password = CryptoHelper.decrypt(encryptedPassword),
        server = server,
        serverId = serverId,
        terminalUrl = terminalUrl,
        authenticated = authenticated,
        connectionStatus = connectionStatus,
        lastCheckedAt = lastCheckedAt,
    )

    private fun decodeSymbols(raw: String?): List<AllowedSymbolConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        AllowedSymbolConfig(
                            symbol = obj.getString("symbol"),
                            platform = PlatformType.valueOf(obj.optString("platform", "MT5")),
                            lot = obj.optString("lot", "0.01"),
                            direction = TradeDirection.valueOf(obj.optString("direction", "BOTH")),
                            trades = obj.optString("trades", "4"),
                            live = obj.optBoolean("live", true),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeAccount(raw: String?): SavedAccount? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            SavedAccount(
                broker = obj.getString("broker"),
                platform = PlatformType.valueOf(obj.optString("platform", "MT5")),
                login = obj.getString("login"),
                password = obj.optString("password", ""),
                server = obj.getString("server"),
                serverId = obj.optString("serverId", ""),
                terminalUrl = obj.optString("terminalUrl", ""),
                authenticated = obj.optBoolean("authenticated", false),
                connectionStatus = obj.optString("connectionStatus", "Not connected"),
                lastCheckedAt = obj.optString("lastCheckedAt", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCustomColors(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return List(16) { "" }
        return try {
            val arr = JSONArray(raw)
            List(16) { idx -> arr.optString(idx, "") }
        } catch (_: Exception) {
            List(16) { "" }
        }
    }
}
