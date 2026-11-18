package com.example

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.local.SmsMessageEntity
import com.example.data.remote.DecryptedOutboundCommand
import com.example.data.remote.DecryptedSmsPayload
import com.example.domain.OtpExtractor
import com.example.ui.pairing.PairingQrPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreSyncUnitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun otpExtractor_extractsCommonOtpFormatsAndIgnoresYears() {
        assertThat(OtpExtractor.extractOtp("Your Chase verification code is 492817. Do not share."))
            .isEqualTo("492817")
        assertThat(OtpExtractor.extractOtp("G-839201 is your Google verification code."))
            .isEqualTo("839201")
        assertThat(OtpExtractor.extractOtp("Use OTP 123-456 to confirm login."))
            .isEqualTo("123456")
        assertThat(OtpExtractor.extractOtp("Happy New Year 2026! Dinner at 7pm?"))
            .isNull()
    }

    @Test
    fun ecdhAndAesGcm_twoDevicesDeriveIdenticalKeyAndEncryptDecrypt() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val phoneA = kpg.generateKeyPair()
        val phoneB = kpg.generateKeyPair()
        val pairingId = "pair_test_001"

        val keyA = CryptoManager.deriveEcdhAesKey(phoneA.private, phoneB.public, pairingId)
        val keyB = CryptoManager.deriveEcdhAesKey(phoneB.private, phoneA.public, pairingId)
        assertThat(keyA.encoded).isEqualTo(keyB.encoded)

        val cryptoA = CryptoManager(context)
        val pubA = Base64.encodeToString(phoneA.public.encoded, Base64.NO_WRAP)
        val pubB = Base64.encodeToString(phoneB.public.encoded, Base64.NO_WRAP)
        val sasAB = cryptoA.computeVerificationSas(pubA, pubB, pairingId)
        val sasBA = cryptoA.computeVerificationSas(pubB, pubA, pairingId)
        assertThat(sasAB).isEqualTo(sasBA)

        val smsPayload = DecryptedSmsPayload(
            messageId = "msg_101",
            senderAddress = "CHASE",
            senderName = "Chase Bank",
            body = "Your one-time security code is 918273.",
            originalTimestamp = 1763236000000L,
            simSlot = 0,
            subscriptionId = 1,
            simLabel = "SIM 1 • T-Mobile",
            isIncoming = true
        )
        val envelope = CryptoManager.encryptRaw(keyA.encoded, smsPayload.toJson())
        val decryptedJson = CryptoManager.decryptRaw(keyB.encoded, envelope.ciphertextBase64, envelope.ivBase64)
        val restored = DecryptedSmsPayload.fromJson(decryptedJson)
        assertThat(restored).isEqualTo(smsPayload)

        // Tampered ciphertext fails GCM authentication
        val wrongKey = ByteArray(32) { 7 }
        assertThrows(Exception::class.java) {
            CryptoManager.decryptRaw(wrongKey, envelope.ciphertextBase64, envelope.ivBase64)
        }
    }

    @Test
    fun pairingQrPayload_andOutboundCommand_roundTripCorrectly() {
        val qr = PairingQrPayload(
            pairingId = "pair_abc123",
            pairingCode = "654321",
            primaryDeviceId = "dev_primary",
            primaryDeviceName = "Pixel 9 Pro",
            primaryPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234"
        )
        val parsed = PairingQrPayload.parse(qr.toUriString())
        assertThat(parsed).isEqualTo(qr)

        val cmd = DecryptedOutboundCommand(
            commandId = "cmd_99",
            recipientAddress = "+14155550199",
            body = "Running 5 mins late!",
            requestedAt = 1763236123000L,
            subscriptionId = 2
        )
        assertThat(DecryptedOutboundCommand.fromJson(cmd.toJson())).isEqualTo(cmd)
    }

    @Test
    fun roomSmsDao_insertsSearchesAndMarksRead() = runBlocking {
        val dao = db.smsDao()
        val msg = SmsMessageEntity(
            id = "m_1",
            threadAddress = "+14155550100",
            senderName = "Bank Alert",
            body = "Code 774411 to verify transfer",
            timestamp = 1763236200000L,
            isIncoming = true,
            isRead = false,
            origin = SmsMessageEntity.ORIGIN_REMOTE_SYNC,
            syncStatus = SmsMessageEntity.SYNC_DELIVERED,
            detectedOtp = "774411"
        )
        dao.insertMessage(msg)

        val unreadBefore = dao.observeUnreadCount().first()
        assertThat(unreadBefore).isEqualTo(1)

        val searchResults = dao.searchMessages("774411").first()
        assertThat(searchResults).hasSize(1)

        dao.markThreadAsRead("+14155550100")
        val unreadAfter = dao.observeUnreadCount().first()
        assertThat(unreadAfter).isEqualTo(0)
    }
}
