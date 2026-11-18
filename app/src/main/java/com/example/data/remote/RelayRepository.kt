package com.example.data.remote

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class RelayRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun devicesCol(uid: String) =
        db.collection("users").document(uid).collection("devices")

    private fun pairingsCol(uid: String) =
        db.collection("users").document(uid).collection("pairings")

    private fun relayCol(uid: String) =
        db.collection("users").document(uid).collection("relay_messages")

    suspend fun upsertDevicePresence(
        deviceId: String,
        deviceName: String,
        role: String,
        publicKey: String,
        isOnline: Boolean,
        batteryLevel: Int,
        simCarrier: String,
        pendingCount: Int
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = devicesCol(uid).document(deviceId)
        val existingSnap = try {
            docRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }

        val clampedBattery = batteryLevel.coerceIn(0, 100).toLong()
        val clampedPending = pendingCount.coerceIn(0, 100000).toLong()
        val safeName = deviceName.trim().ifEmpty { "Android Device" }.take(80)
        val safeCarrier = simCarrier.trim().take(80)

        try {
            if (existingSnap.exists()) {
                val updates = mapOf(
                    "deviceName" to safeName,
                    "role" to role,
                    "publicKey" to publicKey,
                    "isOnline" to isOnline,
                    "batteryLevel" to clampedBattery,
                    "simCarrier" to safeCarrier,
                    "pendingCount" to clampedPending,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "deviceName" to safeName,
                    "role" to role,
                    "publicKey" to publicKey,
                    "isOnline" to isOnline,
                    "batteryLevel" to clampedBattery,
                    "simCarrier" to safeCarrier,
                    "pendingCount" to clampedPending,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    fun observeDevices(): Flow<List<DeviceRecord>> = flow {
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        val col = devicesCol(uid)
        emitAll(
            col.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DeviceRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    suspend fun createPairingSession(
        primaryDeviceId: String,
        primaryDeviceName: String,
        primaryPublicKey: String
    ): Result<PairingSession> = runCatching {
        val uid = requireUserId()
        val pairingId = "pair_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val pairingCode = generatePairingCode()
        val docRef = pairingsCol(uid).document(pairingId)
        val payload = mapOf(
            "userId" to uid,
            "primaryDeviceId" to primaryDeviceId,
            "primaryDeviceName" to primaryDeviceName.trim().ifEmpty { "Primary Phone" }.take(80),
            "primaryPublicKey" to primaryPublicKey,
            "remoteDeviceId" to "",
            "remoteDeviceName" to "",
            "remotePublicKey" to "",
            "pairingCode" to pairingCode,
            "verificationSas" to "",
            "primaryConfirmed" to false,
            "remoteConfirmed" to false,
            "status" to PairingSession.STATUS_PENDING,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            val snap = docRef.get().await()
            snap.toObject(
                PairingSession::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: PairingSession(
                id = pairingId,
                userId = uid,
                primaryDeviceId = primaryDeviceId,
                primaryDeviceName = primaryDeviceName,
                primaryPublicKey = primaryPublicKey,
                pairingCode = pairingCode,
                status = PairingSession.STATUS_PENDING
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun findPairingByCode(pairingCode: String): Result<PairingSession?> = runCatching {
        val uid = requireUserId()
        val col = pairingsCol(uid)
        try {
            val snap = col.whereEqualTo("pairingCode", pairingCode.trim()).get().await()
            snap.documents.firstOrNull { doc ->
                val st = doc.getString("status")
                st == PairingSession.STATUS_PENDING || st == PairingSession.STATUS_AWAITING_CONFIRMATION
            }?.toObject(
                PairingSession::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, col.path)
            throw e
        }
    }

    suspend fun getPairingById(pairingId: String): Result<PairingSession?> = runCatching {
        val uid = requireUserId()
        val docRef = pairingsCol(uid).document(pairingId)
        try {
            docRef.get().await().toObject(
                PairingSession::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun joinPairingSession(
        pairingId: String,
        remoteDeviceId: String,
        remoteDeviceName: String,
        remotePublicKey: String,
        verificationSas: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = pairingsCol(uid).document(pairingId)
        try {
            docRef.update(
                mapOf(
                    "remoteDeviceId" to remoteDeviceId,
                    "remoteDeviceName" to remoteDeviceName.trim().ifEmpty { "Remote Phone" }.take(80),
                    "remotePublicKey" to remotePublicKey,
                    "verificationSas" to verificationSas,
                    "remoteConfirmed" to false,
                    "status" to PairingSession.STATUS_AWAITING_CONFIRMATION,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun confirmPairing(
        pairingId: String,
        isPrimary: Boolean,
        verificationSas: String
    ): Result<PairingSession?> = runCatching {
        val uid = requireUserId()
        val docRef = pairingsCol(uid).document(pairingId)
        try {
            db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val current = snap.toObject(PairingSession::class.java)
                    ?: error("Pairing session not found")
                if (current.status == PairingSession.STATUS_REVOKED) {
                    error("Pairing has been revoked")
                }
                val nextPrimaryConfirmed = current.primaryConfirmed || isPrimary
                val nextRemoteConfirmed = current.remoteConfirmed || !isPrimary
                val nextStatus = if (nextPrimaryConfirmed && nextRemoteConfirmed) {
                    PairingSession.STATUS_ACTIVE
                } else {
                    PairingSession.STATUS_AWAITING_CONFIRMATION
                }
                tx.update(
                    docRef,
                    mapOf(
                        "primaryConfirmed" to nextPrimaryConfirmed,
                        "remoteConfirmed" to nextRemoteConfirmed,
                        "verificationSas" to verificationSas,
                        "status" to nextStatus,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                current.copy(
                    primaryConfirmed = nextPrimaryConfirmed,
                    remoteConfirmed = nextRemoteConfirmed,
                    verificationSas = verificationSas,
                    status = nextStatus
                )
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun revokePairing(pairingId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = pairingsCol(uid).document(pairingId)
        try {
            val snap = docRef.get().await()
            if (snap.exists() && snap.getString("status") != PairingSession.STATUS_REVOKED) {
                docRef.update(
                    mapOf(
                        "status" to PairingSession.STATUS_REVOKED,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deletePairing(pairingId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = pairingsCol(uid).document(pairingId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    fun observePairings(): Flow<List<PairingSession>> = flow {
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        val col = pairingsCol(uid)
        emitAll(
            col.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PairingSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    suspend fun sendEncryptedRelayEnvelope(
        messageId: String,
        pairingId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        encryptedPayload: String,
        iv: String,
        direction: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = relayCol(uid).document(messageId)
        val payload = mapOf(
            "userId" to uid,
            "pairingId" to pairingId,
            "sourceDeviceId" to sourceDeviceId,
            "targetDeviceId" to targetDeviceId,
            "encryptedPayload" to encryptedPayload,
            "iv" to iv,
            "direction" to direction,
            "status" to RelayEnvelope.STATUS_PENDING,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    fun observeIncomingRelayForDevice(deviceId: String): Flow<List<RelayEnvelope>> = flow {
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        val query = relayCol(uid).whereEqualTo("targetDeviceId", deviceId)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RelayEnvelope::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, relayCol(uid).path)
                    }
                    throw error
                }
        )
    }

    /**
     * Automatically deletes temporary encrypted relay message from Firestore after
     * successful decryption and local persistence.
     */
    suspend fun acknowledgeAndDeleteRelayEnvelope(messageId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = relayCol(uid).document(messageId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    private fun generatePairingCode(): String {
        val rng = SecureRandom()
        val num = 100000 + rng.nextInt(900000)
        return num.toString()
    }
}
