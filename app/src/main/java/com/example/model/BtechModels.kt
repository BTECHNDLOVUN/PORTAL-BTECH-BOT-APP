package com.example.model

enum class PlatformType(val label: String) {
    MT5("MT5"),
    MT4("MT4"),
}

enum class TradeDirection(val label: String) {
    BUY("BUY"),
    SELL("SELL"),
    BOTH("BOTH"),
}

enum class NavStyle(val id: String, val title: String, val description: String) {
    INDICATOR(
        id = "indicator",
        title = "Default — top indicator",
        description = "Split header and bottom area with a theme-coloured indicator bar at the boundary. Icons remain level."
    ),
    RAISED_TOP(
        id = "raised-top",
        title = "Raised icon — upper-colour outline",
        description = "Upper header stays visible with no indicator line. Active icon lifts slightly with a 4dp upper-colour ring."
    ),
    RAISED_BOTTOM(
        id = "raised-bottom",
        title = "Raised icon — lower-colour outline",
        description = "Upper header stays visible with no indicator line. Active icon lifts slightly with a 4dp menu-colour ring."
    );

    companion object {
        fun fromId(id: String): NavStyle = entries.firstOrNull { it.id == id } ?: INDICATOR
    }
}

enum class BubbleGlowStyle(
    val id: String,
    val label: String,
    val hex: String,
) {
    AUTO("auto", "Auto Cycle", "#84CC16"),
    PRIME("prime", "Prime Color", "#9138EE"),
    LIME("lime", "Lime", "#84CC16"),
    PURPLE("purple", "Purple", "#9138EE"),
    BLUE("blue", "Blue", "#2563EB"),
    DARK_STYLE("dark_style", "Dark Style", "#1E293B"),
    ORANGE_RED("orange_red", "Orange Red", "#FF4500"),
    PINK_RED("pink_red", "Pink Red", "#FF1E6E");

    companion object {
        val CYCLE_HEX_COLORS = listOf(
            LIME.hex,
            PURPLE.hex,
            BLUE.hex,
            DARK_STYLE.hex,
            ORANGE_RED.hex,
            PINK_RED.hex,
        )

        fun fromId(id: String?): BubbleGlowStyle =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: AUTO
    }
}

data class MarketSymbol(
    val symbol: String,
    val description: String,
)

data class AllowedSymbolConfig(
    val symbol: String,
    val platform: PlatformType = PlatformType.MT5,
    val lot: String = "0.01",
    val direction: TradeDirection = TradeDirection.BOTH,
    val trades: String = "4",
    val live: Boolean = true,
)

data class BrokerServer(
    val id: String,
    val name: String,
    val environment: String,
    val terminalUrl: String,
    val isUrlVerified: Boolean = false,
)

data class BrokerInfo(
    val id: String,
    val logo: String,
    val name: String,
    val summary: String,
    val mt5Servers: List<BrokerServer>,
    val defaultMt4TerminalUrl: String = "",
)

data class SavedAccount(
    val broker: String,
    val platform: PlatformType,
    val login: String,
    val password: String,
    val server: String,
    val serverId: String,
    val terminalUrl: String,
    val authenticated: Boolean,
    val connectionStatus: String,
    val lastCheckedAt: String = "",
)

data class SimulatedSignalRequest(
    val symbol: String,
    val direction: TradeDirection,
    val lot: String,
    val stopLoss: String,
    val takeProfit: String,
    val comment: String,
    val tradeCount: Int,
    val platform: PlatformType? = null,
    val signalId: String = "",
)

data class SymbolDiscoveryRequest(
    val requestedSymbol: String,
    val broker: String,
    val platform: PlatformType,
    val server: String,
)

data class SymbolDiscoveryResult(
    val success: Boolean,
    val requestedSymbol: String,
    val actualBrokerSymbol: String?,
    val broker: String,
    val platform: PlatformType,
    val server: String,
    val matchType: String,
    val message: String,
    val discoveredSymbols: List<String> = emptyList(),
)

enum class BrokerResultStatus(val wireValue: String) {
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromWire(value: String?): BrokerResultStatus =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class BrokerTradeResult(
    val status: BrokerResultStatus,
    val platform: PlatformType,
    val requestedSymbol: String,
    val actualSymbol: String,
    val direction: String,
    val lot: String,
    val tradeIndex: Int,
    val tradeCount: Int,
    val confirmedCount: Int,
    val ticket: String = "",
    val rawMessage: String = "",
    val retried: Boolean = false,
)

data class DiscoveredBrokerCatalog(
    val broker: String,
    val platform: PlatformType,
    val server: String,
    val symbols: List<String> = emptyList(),
    val resolvedMappings: Map<String, String> = emptyMap(),
    val verifiedAliases: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0L,
)

data class TradeChannelSignal(
    val signalId: String,
    val platform: PlatformType,
    val symbol: String,
    val direction: TradeDirection,
    val lot: String,
    val stopLoss: String = "",
    val takeProfit: String = "",
    val comment: String = "BTECH-EA",
    val tradeCount: Int = 1,
) {
    fun toSignalRequest(): SimulatedSignalRequest {
        return SimulatedSignalRequest(
            symbol = symbol,
            direction = direction,
            lot = lot,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            comment = comment,
            tradeCount = tradeCount.coerceIn(1, 20),
            platform = platform,
            signalId = signalId,
        )
    }

    companion object {
        fun fromJson(rawJson: String): TradeChannelSignal? {
            return runCatching {
                val obj = org.json.JSONObject(rawJson)
                val symbol = obj.optString("symbol", "").trim()
                if (symbol.isEmpty()) return null
                val platformRaw = obj.optString("platform", "MT5").trim().uppercase()
                val platform = if (platformRaw == "MT4") PlatformType.MT4 else PlatformType.MT5
                val dirRaw = obj.optString("direction", "BUY").trim().uppercase()
                val direction = when (dirRaw) {
                    "SELL" -> TradeDirection.SELL
                    "BUY" -> TradeDirection.BUY
                    else -> return null
                }
                val lot = obj.optString("lot", "0.01").trim().ifEmpty { "0.01" }
                val lotVal = lot.toDoubleOrNull()
                if (lotVal == null || lotVal <= 0.0) return null
                TradeChannelSignal(
                    signalId = obj.optString("signal_id", "").trim(),
                    platform = platform,
                    symbol = symbol,
                    direction = direction,
                    lot = lot,
                    stopLoss = obj.optString("stop_loss", "").trim(),
                    takeProfit = obj.optString("take_profit", "").trim(),
                    comment = obj.optString("comment", "BTECH-EA").trim().ifEmpty { "BTECH-EA" },
                    tradeCount = obj.optInt("trade_count", 1).coerceIn(1, 20),
                )
            }.getOrNull()
        }
    }
}

object BrokerSymbolResolver {
    val KNOWN_SUFFIXES = listOf(
        ".m", ".s", ".c", ".ecn", ".pro",
        ".raw", ".r", ".micro", ".std",
        ".cent", ".i", "_i", "#",
    )

    fun normalise(value: String?): String = (value ?: "").trim().uppercase()

    fun cacheKey(broker: String, platform: PlatformType, server: String): String {
        val b = broker.trim().lowercase().replace("|", "_")
        val s = server.trim().lowercase().replace("|", "_")
        return "$b|${platform.name}|$s"
    }

    fun stripKnownSuffix(symbol: String): String {
        val value = normalise(symbol)
        if (value.isEmpty()) return ""
        for (suffix in KNOWN_SUFFIXES) {
            val upperSuffix = suffix.uppercase()
            if (value.endsWith(upperSuffix) && value.length > upperSuffix.length + 2) {
                return value.dropLast(upperSuffix.length)
            }
        }
        // Handle single trailing micro/cent suffix without trimming standard 6-letter FX pairs
        if (value.length >= 7 && (value.endsWith("M") || value.endsWith("C"))) {
            return value.dropLast(1)
        }
        return value
    }

    fun matchRank(
        actual: String,
        requested: String,
        verifiedAliases: Map<String, String> = emptyMap(),
    ): Int {
        val aTrim = actual.trim()
        val rTrim = requested.trim()
        if (aTrim.isEmpty() || rTrim.isEmpty()) return 0

        if (aTrim == rTrim) return 4
        if (aTrim.equals(rTrim, ignoreCase = true)) return 3

        val reqUpper = normalise(rTrim)
        val actUpper = normalise(aTrim)
        val aliasTarget = verifiedAliases.entries.firstOrNull { (k, _) ->
            normalise(k) == reqUpper
        }?.value
        if (!aliasTarget.isNullOrBlank() && normalise(aliasTarget) == actUpper) {
            return 2
        }

        return 0
    }

    /**
     * Resolves a requested symbol strictly against a broker+platform+server's discovered
     * instruments, following strict exact matching priority:
     * 1. Exact match (including exact broker suffix, e.g. XAUUSD.s != XAUUSD)
     * 2. Case-insensitive exact match
     * 3. Explicit broker-specific alias configured AND verified against discovered instruments
     * 4. Report not found — any suffix mismatch is treated as a different symbol
     */
    fun resolve(
        request: SymbolDiscoveryRequest,
        discoveredSymbols: List<String>,
        cachedMappings: Map<String, String> = emptyMap(),
        verifiedAliases: Map<String, String> = emptyMap(),
    ): SymbolDiscoveryResult {
        val requested = request.requestedSymbol.trim()
        if (requested.isEmpty()) {
            return SymbolDiscoveryResult(
                success = false,
                requestedSymbol = requested,
                actualBrokerSymbol = null,
                broker = request.broker,
                platform = request.platform,
                server = request.server,
                matchType = "INVALID_REQUEST",
                message = "No symbol was supplied",
                discoveredSymbols = discoveredSymbols,
            )
        }

        val cleanedDiscovered = discoveredSymbols
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // 1. Exact match against discovered instruments
        cleanedDiscovered.firstOrNull { it == requested }?.let { hit ->
            return SymbolDiscoveryResult(
                success = true,
                requestedSymbol = requested,
                actualBrokerSymbol = hit,
                broker = request.broker,
                platform = request.platform,
                server = request.server,
                matchType = "EXACT",
                message = "Matched exact broker symbol $hit on ${request.platform.label} (${request.server})",
                discoveredSymbols = cleanedDiscovered,
            )
        }

        // 2. Case-insensitive exact match
        cleanedDiscovered.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { hit ->
            return SymbolDiscoveryResult(
                success = true,
                requestedSymbol = requested,
                actualBrokerSymbol = hit,
                broker = request.broker,
                platform = request.platform,
                server = request.server,
                matchType = "CASE_INSENSITIVE",
                message = "Matched broker symbol $hit (case-insensitive) for $requested",
                discoveredSymbols = cleanedDiscovered,
            )
        }

        // 3. Previously discovered exact/case-exact mapping for THIS broker + platform + server
        val cachedHit = cachedMappings.entries.firstOrNull { (k, _) ->
            k.equals(requested, ignoreCase = true)
        }?.value?.takeIf { mapped ->
            mapped.equals(requested, ignoreCase = true) &&
                (cleanedDiscovered.isEmpty() || cleanedDiscovered.any { it.equals(mapped, ignoreCase = true) })
        }
        if (!cachedHit.isNullOrBlank()) {
            val canonical = cleanedDiscovered.firstOrNull { it.equals(cachedHit, ignoreCase = true) } ?: cachedHit
            return SymbolDiscoveryResult(
                success = true,
                requestedSymbol = requested,
                actualBrokerSymbol = canonical,
                broker = request.broker,
                platform = request.platform,
                server = request.server,
                matchType = "BROKER_CACHED",
                message = "Resolved via verified ${request.broker} ${request.platform.label} mapping: $canonical",
                discoveredSymbols = cleanedDiscovered,
            )
        }

        // 4. Explicit broker-specific alias configured AND verified in discovered symbols
        val aliasTarget = verifiedAliases.entries.firstOrNull { (alias, _) ->
            alias.equals(requested, ignoreCase = true)
        }?.value?.trim()
        if (!aliasTarget.isNullOrBlank()) {
            val verifiedInTerminal = cleanedDiscovered.firstOrNull { it.equals(aliasTarget, ignoreCase = true) }
            if (verifiedInTerminal != null) {
                return SymbolDiscoveryResult(
                    success = true,
                    requestedSymbol = requested,
                    actualBrokerSymbol = verifiedInTerminal,
                    broker = request.broker,
                    platform = request.platform,
                    server = request.server,
                    matchType = "VERIFIED_ALIAS",
                    message = "Resolved via verified broker alias $requested -> $verifiedInTerminal",
                    discoveredSymbols = cleanedDiscovered,
                )
            }
        }

        // 5. Strict exact match only — unmatched suffixes or different symbols return not found
        return SymbolDiscoveryResult(
            success = false,
            requestedSymbol = requested,
            actualBrokerSymbol = null,
            broker = request.broker,
            platform = request.platform,
            server = request.server,
            matchType = "NOT_FOUND",
            message = "error:Symbol $requested not found",
            discoveredSymbols = cleanedDiscovered,
        )
    }
}

data class ExecutionStepLog(
    val id: Long,
    val timestamp: String,
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false,
)

object BtechRoutes {
    const val HOME = "home"
    const val ADD_ROBOT = "add"
    const val SYMBOLS = "symbols"
    const val SYMBOL_CONFIG = "symbol_config"
    const val INFO = "info"
    const val META = "meta"
    const val BROKER_LOGIN = "login"
    const val SAVED_ACCOUNT = "saved"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
}

object BrokerCatalog {
    const val METAQUOTES_MT5_URL = "https://trade.metatrader5.com"
    const val METAQUOTES_MT4_URL = "https://metatraderweb.app/trade"
    const val JUSTMARKETS_MT5_URL = "https://live.justmarkets.com"
    const val JUSTMARKETS_MT4_WEB_URL = "https://metatraderweb.app/trade"

    val markets = listOf(
        // Gold (XAUUSD / GOLD) + JustMarkets & common broker suffixes
        MarketSymbol("XAUUSD", "Gold vs US Dollar (Standard / No suffix)"),
        MarketSymbol("XAUUSD.m", "Gold vs US Dollar · JustMarkets Standard (.m suffix)"),
        MarketSymbol("XAUUSD.s", "Gold vs US Dollar · JustMarkets Pro (.s suffix)"),
        MarketSymbol("XAUUSD.c", "Gold vs US Dollar · JustMarkets Standard Cent (.c suffix)"),
        MarketSymbol("XAUUSD.ecn", "Gold vs US Dollar · JustMarkets Raw Spread (.ecn suffix)"),
        MarketSymbol("XAUUSDm", "Gold vs US Dollar · Micro (m suffix)"),
        MarketSymbol("XAUUSDc", "Gold vs US Dollar · Cent (c suffix)"),
        MarketSymbol("XAUUSD.r", "Gold vs US Dollar · Raw (.r suffix)"),
        MarketSymbol("XAUUSD.pro", "Gold vs US Dollar · Pro (.pro suffix)"),
        MarketSymbol("XAUUSD.raw", "Gold vs US Dollar · Raw Spread (.raw suffix)"),
        MarketSymbol("GOLD", "Gold Spot · Alternative broker symbol"),
        MarketSymbol("GOLD.m", "Gold Spot · (.m suffix)"),
        MarketSymbol("GOLD.s", "Gold Spot · (.s suffix)"),

        // EURUSD + suffixes
        MarketSymbol("EURUSD", "Euro vs US Dollar (Standard)"),
        MarketSymbol("EURUSD.m", "Euro vs US Dollar · JustMarkets Standard (.m)"),
        MarketSymbol("EURUSD.s", "Euro vs US Dollar · JustMarkets Pro (.s)"),
        MarketSymbol("EURUSD.c", "Euro vs US Dollar · JustMarkets Cent (.c)"),
        MarketSymbol("EURUSD.ecn", "Euro vs US Dollar · JustMarkets Raw (.ecn)"),
        MarketSymbol("EURUSDm", "Euro vs US Dollar · (m suffix)"),
        MarketSymbol("EURUSD.r", "Euro vs US Dollar · (.r suffix)"),
        MarketSymbol("EURUSD.pro", "Euro vs US Dollar · (.pro suffix)"),
        MarketSymbol("EURUSDc", "Euro vs US Dollar · (c suffix)"),

        // GBPUSD + suffixes
        MarketSymbol("GBPUSD", "British Pound vs US Dollar (Standard)"),
        MarketSymbol("GBPUSD.m", "British Pound vs US Dollar · JustMarkets Standard (.m)"),
        MarketSymbol("GBPUSD.s", "British Pound vs US Dollar · JustMarkets Pro (.s)"),
        MarketSymbol("GBPUSD.c", "British Pound vs US Dollar · JustMarkets Cent (.c)"),
        MarketSymbol("GBPUSD.ecn", "British Pound vs US Dollar · JustMarkets Raw (.ecn)"),
        MarketSymbol("GBPUSDm", "British Pound vs US Dollar · (m suffix)"),
        MarketSymbol("GBPUSD.r", "British Pound vs US Dollar · (.r suffix)"),
        MarketSymbol("GBPUSD.pro", "British Pound vs US Dollar · (.pro suffix)"),

        // USDJPY / GBPJPY / EURJPY + suffixes
        MarketSymbol("USDJPY", "US Dollar vs Japanese Yen (Standard)"),
        MarketSymbol("USDJPY.m", "US Dollar vs Japanese Yen · (.m suffix)"),
        MarketSymbol("USDJPY.s", "US Dollar vs Japanese Yen · (.s suffix)"),
        MarketSymbol("USDJPY.c", "US Dollar vs Japanese Yen · (.c suffix)"),
        MarketSymbol("USDJPY.ecn", "US Dollar vs Japanese Yen · (.ecn suffix)"),
        MarketSymbol("USDJPYm", "US Dollar vs Japanese Yen · (m suffix)"),
        MarketSymbol("GBPJPY", "British Pound vs Japanese Yen (Standard)"),
        MarketSymbol("GBPJPY.m", "British Pound vs Japanese Yen · (.m suffix)"),
        MarketSymbol("GBPJPY.s", "British Pound vs Japanese Yen · (.s suffix)"),
        MarketSymbol("GBPJPY.c", "British Pound vs Japanese Yen · (.c suffix)"),
        MarketSymbol("GBPJPY.ecn", "British Pound vs Japanese Yen · (.ecn suffix)"),
        MarketSymbol("EURJPY", "Euro vs Japanese Yen (Standard)"),
        MarketSymbol("EURJPY.m", "Euro vs Japanese Yen · (.m suffix)"),
        MarketSymbol("EURJPY.s", "Euro vs Japanese Yen · (.s suffix)"),
        MarketSymbol("EURJPY.c", "Euro vs Japanese Yen · (.c suffix)"),
        MarketSymbol("EURJPY.ecn", "Euro vs Japanese Yen · (.ecn suffix)"),

        // Indices + suffixes
        MarketSymbol("US30", "Dow Jones Index (Standard)"),
        MarketSymbol("US30.m", "Dow Jones Index · (.m suffix)"),
        MarketSymbol("US30.s", "Dow Jones Index · (.s suffix)"),
        MarketSymbol("US30.c", "Dow Jones Index · (.c suffix)"),
        MarketSymbol("US30.ecn", "Dow Jones Index · (.ecn suffix)"),
        MarketSymbol("US30m", "Dow Jones Index · (m suffix)"),
        MarketSymbol("DE30", "Germany 30/40 Index (Standard)"),
        MarketSymbol("DE30.m", "Germany 30 Index · (.m suffix)"),
        MarketSymbol("DE30.s", "Germany 30 Index · (.s suffix)"),
        MarketSymbol("DE30m", "Germany 30 Index · (m suffix)"),
        MarketSymbol("DE40", "Germany 40 Index (Standard)"),
        MarketSymbol("DE40.m", "Germany 40 Index · (.m suffix)"),
        MarketSymbol("DE40.s", "Germany 40 Index · (.s suffix)"),
        MarketSymbol("DE40.ecn", "Germany 40 Index · (.ecn suffix)"),
        MarketSymbol("USTEC", "Nasdaq 100 Index (Standard)"),
        MarketSymbol("USTEC.m", "Nasdaq 100 Index · (.m suffix)"),
        MarketSymbol("USTEC.s", "Nasdaq 100 Index · (.s suffix)"),
        MarketSymbol("USTEC.ecn", "Nasdaq 100 Index · (.ecn suffix)"),
        MarketSymbol("USTECm", "Nasdaq 100 Index · (m suffix)"),
        MarketSymbol("NAS100", "Nasdaq 100 Index · NAS100"),
        MarketSymbol("NAS100.m", "Nasdaq 100 Index · (.m suffix)"),
        MarketSymbol("NAS100.s", "Nasdaq 100 Index · (.s suffix)"),
        MarketSymbol("NAS100.ecn", "Nasdaq 100 Index · (.ecn suffix)"),

        // Crypto + suffixes
        MarketSymbol("BTCUSD", "Bitcoin vs US Dollar (Standard)"),
        MarketSymbol("BTCUSD.m", "Bitcoin vs US Dollar · (.m suffix)"),
        MarketSymbol("BTCUSD.s", "Bitcoin vs US Dollar · (.s suffix)"),
        MarketSymbol("BTCUSD.c", "Bitcoin vs US Dollar · (.c suffix)"),
        MarketSymbol("BTCUSD.ecn", "Bitcoin vs US Dollar · (.ecn suffix)"),
        MarketSymbol("BTCUSDm", "Bitcoin vs US Dollar · (m suffix)"),
    )

    val brokers = listOf(
        BrokerInfo(
            id = "JustMarkets",
            logo = "JM",
            name = "JustMarkets",
            summary = "MT5: live.justmarkets.com · MT4: metatraderweb.app",
            mt5Servers = listOf(
                BrokerServer("jm-live", "JustMarkets-Live", "Live", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-live2", "JustMarkets-Live2", "Live", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-live3", "JustMarkets-Live3", "Live", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-live6", "JustMarkets-Live6", "Live", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-live7", "JustMarkets-Live7", "Live", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-demo", "JustMarkets-Demo", "Demo", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-demo2", "JustMarkets-Demo2", "Demo", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-demo3", "JustMarkets-Demo3", "Demo", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-demo6", "JustMarkets-Demo6", "Demo", JUSTMARKETS_MT5_URL, isUrlVerified = true),
                BrokerServer("jm-demo7", "JustMarkets-Demo7", "Demo", JUSTMARKETS_MT5_URL, isUrlVerified = true),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
        BrokerInfo(
            id = "MetaQuotes",
            logo = "5",
            name = "MetaQuotes",
            summary = "MetaQuotes-Demo · Official MetaQuotes web terminal",
            mt5Servers = listOf(
                BrokerServer("mq-demo", "MetaQuotes-Demo", "Demo", METAQUOTES_MT5_URL, isUrlVerified = true),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
        BrokerInfo(
            id = "Exness",
            logo = "ex",
            name = "Exness",
            summary = "Requires broker's own WebTrader URL",
            mt5Servers = listOf(
                BrokerServer("exness-mt5-trial", "Exness-MT5Trial", "Demo", "", isUrlVerified = false),
                BrokerServer("exness-mt5-real", "Exness-MT5Real", "Live", "", isUrlVerified = false),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
        BrokerInfo(
            id = "HF Markets",
            logo = "HF",
            name = "HF Markets",
            summary = "Requires broker's own WebTrader URL",
            mt5Servers = listOf(
                BrokerServer("hfm-demo", "HFMarketsSV-Demo", "Demo", "", isUrlVerified = false),
                BrokerServer("hfm-live", "HFMarketsSV-Live", "Live", "", isUrlVerified = false),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
        BrokerInfo(
            id = "JP Markets",
            logo = "JP",
            name = "JP Markets",
            summary = "Requires broker's own WebTrader URL",
            mt5Servers = listOf(
                BrokerServer("jp-demo", "JPMarkets-Demo", "Demo", "", isUrlVerified = false),
                BrokerServer("jp-live", "JPMarkets-Live", "Live", "", isUrlVerified = false),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
        BrokerInfo(
            id = "Weltrade",
            logo = "W",
            name = "Weltrade",
            summary = "Requires broker's own WebTrader URL",
            mt5Servers = listOf(
                BrokerServer("wt-demo", "Weltrade-Demo", "Demo", "", isUrlVerified = false),
                BrokerServer("wt-live", "Weltrade-Real", "Live", "", isUrlVerified = false),
            ),
            defaultMt4TerminalUrl = METAQUOTES_MT4_URL,
        ),
    )

    fun findBroker(name: String): BrokerInfo? = brokers.firstOrNull { it.name == name }

    fun isGenericTerminalUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.contains("trade.metatrader5.com") ||
            normalized.contains("metatraderweb.app") ||
            normalized.contains("trade.mql5.com")
    }

    fun isUrlPlatformMismatch(url: String, platform: PlatformType): Boolean {
        val normalized = url.trim().lowercase()
        if (normalized.isEmpty()) return false
        return when (platform) {
            PlatformType.MT4 ->
                normalized.contains("live.justmarkets.com") ||
                    normalized.contains("trade.metatrader5.com") ||
                    normalized.contains("version=5")
            PlatformType.MT5 ->
                normalized.contains("metatraderweb.app") ||
                    normalized.contains("version=4")
        }
    }

    fun requiresBrokerOwnUrlWarning(
        brokerName: String,
        url: String,
        platform: PlatformType = PlatformType.MT5,
    ): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return true
        if (isUrlPlatformMismatch(trimmed, platform)) return true
        val normalized = trimmed.lowercase()
        if (platform == PlatformType.MT4 && normalized.contains("metatraderweb.app")) return false
        if (brokerName == "JustMarkets" && platform == PlatformType.MT5 && normalized.contains("live.justmarkets.com")) return false
        return brokerName != "MetaQuotes" && isGenericTerminalUrl(trimmed)
    }

    /**
     * Applies RoboTrader's separate MT4 (/trade?version=4&login=&server=) and
     * MT5 (/terminal?login=&server=) route patterns on official MetaQuotes/MetaTrader hosts
     * while preserving broker-specific custom WebTrader URLs (e.g. https://live.justmarkets.com for MT5) untouched.
     */
    fun buildLaunchUrl(
        rawUrl: String,
        platform: PlatformType,
        login: String = "",
        server: String = "",
    ): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val lower = withScheme.lowercase()
        val encLogin = java.net.URLEncoder.encode(login.trim(), "UTF-8")
        val encServer = java.net.URLEncoder.encode(server.trim(), "UTF-8")

        return when {
            lower.contains("live.justmarkets.com") -> withScheme
            lower.contains("metatraderweb.app") && platform == PlatformType.MT4 -> {
                val base = withScheme.substringBefore("?").trimEnd('/')
                val pathUrl = if (base.lowercase().endsWith("/trade")) base else "$base/trade"
                val params = buildList {
                    add("version=4")
                    if (login.isNotBlank()) add("login=$encLogin")
                    if (server.isNotBlank()) add("server=$encServer")
                }
                "$pathUrl?${params.joinToString("&")}"
            }
            lower.contains("trade.metatrader5.com") && platform == PlatformType.MT5 -> {
                val base = withScheme.substringBefore("?").trimEnd('/')
                val pathUrl = if (base.lowercase().endsWith("/terminal")) base else "$base/terminal"
                val params = buildList {
                    if (login.isNotBlank()) add("login=$encLogin")
                    if (server.isNotBlank()) add("server=$encServer")
                }
                if (params.isEmpty()) pathUrl else "$pathUrl?${params.joinToString("&")}"
            }
            else -> withScheme
        }
    }

    fun sanitizeTerminalUrlForDisplay(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringBefore("#").substringBefore("?")
    }
}
