package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.remote.PairingSession
import com.example.data.remote.RelayEnvelope
import com.example.data.remote.RelayRepository
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class RelayRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun upsertDeviceAndObserveDevices_authenticatedOwner_succeeds() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = RelayRepository(firestore, auth)
        val deviceId = "dev_${UUID.randomUUID().toString().take(8)}"

        val res = withTimeout(TIMEOUT_MS) {
            repo.upsertDevicePresence(
                deviceId = deviceId,
                deviceName = "Pixel 9 Pro",
                role = "primary_sim",
                publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
                isOnline = true,
                batteryLevel = 88,
                simCarrier = "T-Mobile",
                pendingCount = 0
            )
        }
        assertThat(res.isSuccess).isTrue()

        val devices = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeDevices().first { list -> list.any { it.id == deviceId } }
        }
        assertThat(devices.first { it.id == deviceId }.deviceName).isEqualTo("Pixel 9 Pro")
    }

    @Test
    fun pairingAndEncryptedRelayLifecycle_succeedsAndDeletesAfterDelivery() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = RelayRepository(firestore, auth)
        val primaryId = "dev_p_${UUID.randomUUID().toString().take(8)}"
        val remoteId = "dev_r_${UUID.randomUUID().toString().take(8)}"

        val session = withTimeout(TIMEOUT_MS) {
            repo.createPairingSession(
                primaryDeviceId = primaryId,
                primaryDeviceName = "Phone A",
                primaryPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPrimaryKey"
            ).getOrThrow()
        }
        assertThat(session.status).isEqualTo(PairingSession.STATUS_PENDING)

        // Relay before active pairing must fail with PERMISSION_DENIED
        val msgIdBlocked = "msg_${UUID.randomUUID().toString().take(8)}"
        val blockedResult = withTimeout(TIMEOUT_MS) {
            repo.sendEncryptedRelayEnvelope(
                messageId = msgIdBlocked,
                pairingId = session.id,
                sourceDeviceId = primaryId,
                targetDeviceId = remoteId,
                encryptedPayload = "ZW5jcnlwdGVk",
                iv = "cmFuZG9tX2l2",
                direction = RelayEnvelope.DIRECTION_INBOUND_SMS
            )
        }
        assertThat(blockedResult.isFailure).isTrue()
        assertThat(blockedResult.exceptionOrNull()?.unwrapFirestoreException()?.code)
            .isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        // Remote joins
        withTimeout(TIMEOUT_MS) {
            repo.joinPairingSession(
                pairingId = session.id,
                remoteDeviceId = remoteId,
                remoteDeviceName = "Phone B",
                remotePublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERemoteKey",
                verificationSas = "482-910"
            ).getOrThrow()
        }

        // Both confirm -> active
        withTimeout(TIMEOUT_MS) {
            repo.confirmPairing(session.id, isPrimary = false, verificationSas = "482-910").getOrThrow()
            val active = repo.confirmPairing(session.id, isPrimary = true, verificationSas = "482-910").getOrThrow()
            assertThat(active?.status).isEqualTo(PairingSession.STATUS_ACTIVE)
        }

        // Now encrypted relay succeeds
        val msgId = "msg_${UUID.randomUUID().toString().take(8)}"
        withTimeout(TIMEOUT_MS) {
            repo.sendEncryptedRelayEnvelope(
                messageId = msgId,
                pairingId = session.id,
                sourceDeviceId = primaryId,
                targetDeviceId = remoteId,
                encryptedPayload = "ZW5jcnlwdGVkX3BheWxvYWQ=",
                iv = "cmFuZG9tX2l2",
                direction = RelayEnvelope.DIRECTION_INBOUND_SMS
            ).getOrThrow()
        }

        val incoming = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeIncomingRelayForDevice(remoteId).first { list -> list.any { it.id == msgId } }
        }
        assertThat(incoming.map { it.id }).contains(msgId)

        // Acknowledge & delete from server after local persistence
        withTimeout(TIMEOUT_MS) {
            repo.acknowledgeAndDeleteRelayEnvelope(msgId).getOrThrow()
        }
    }

    @Test
    fun observeDevices_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = RelayRepository(firestore, auth)

        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                withTimeout(FLOW_TIMEOUT_MS) { repo.observeDevices().first() }
            }
        }
        val firestoreEx = checkNotNull(exception.unwrapFirestoreException()) {
            "Expected FirebaseFirestoreException, got $exception"
        }
        assertThat(firestoreEx.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_sync@test.com"
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
