package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.PersistedBtechData
import com.example.data.local.BtechDatabase
import com.example.data.local.SavedAccountEntity
import com.example.viewmodel.AppUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BTECH FINANCE", appName)
  }

  @Test
  fun `default theme mode is light`() {
    assertFalse(AppUiState().isDarkTheme)
    assertFalse(PersistedBtechData().isDarkTheme)
  }

  @Test
  fun `saving new active account removes previously saved account`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, BtechDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val dao = db.btechDao()
      dao.saveActiveAccount(
        SavedAccountEntity(
          broker = "JustMarkets",
          platform = "MT5",
          login = "111111",
          encryptedPassword = "enc",
          server = "JustMarkets-Live",
          serverId = "jm-live",
          terminalUrl = "https://webtrader.justmarkets.com",
          authenticated = true,
          connectionStatus = "Authenticated",
          lastCheckedAt = "10:00:00",
          isActive = true,
        )
      )
      dao.saveActiveAccount(
        SavedAccountEntity(
          broker = "Exness",
          platform = "MT4",
          login = "222222",
          encryptedPassword = "enc2",
          server = "Exness-Real",
          serverId = "ex-real",
          terminalUrl = "https://webtrader.exness.com",
          authenticated = true,
          connectionStatus = "Authenticated",
          lastCheckedAt = "10:05:00",
          isActive = true,
        )
      )
      val saved = dao.observeAccounts().first()
      assertEquals(1, saved.size)
      assertEquals("Exness", saved.first().broker)
      assertEquals("222222", saved.first().login)
    } finally {
      db.close()
    }
  }
}
