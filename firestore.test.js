const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function nowTs() {
  return new Date(Date.now() - 2000);
}

function validDevicePayload(uid) {
  const ts = nowTs();
  return {
    userId: uid,
    deviceName: "Pixel 9 Pro",
    role: "primary_sim",
    publicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
    isOnline: true,
    batteryLevel: 92,
    simCarrier: "T-Mobile",
    pendingCount: 0,
    lastSeenAt: ts,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validPairingPayload(uid, status = "pending") {
  const ts = nowTs();
  return {
    userId: uid,
    primaryDeviceId: "dev_primary_1",
    primaryDeviceName: "Pixel 9 Pro",
    primaryPublicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
    remoteDeviceId: status === "active" ? "dev_remote_1" : "",
    remoteDeviceName: status === "active" ? "Pixel Tablet" : "",
    remotePublicKey: status === "active" ? "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0987654321" : "",
    pairingCode: "482910",
    verificationSas: status === "active" ? "739-201" : "",
    primaryConfirmed: status === "active",
    remoteConfirmed: status === "active",
    status,
    createdAt: ts,
    updatedAt: ts,
  };
}

test("Unauthenticated user cannot read or write devices, pairings, or relay messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/devices`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/pairings`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/relay_messages`).get());
});

test("Cross-user access is strictly denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/devices/dev_primary_1`).set(validDevicePayload(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.doc(`users/${ALICE_UID}/devices/dev_primary_1`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/devices`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/devices/dev_bob`).set(validDevicePayload(ALICE_UID))
  );
});

test("Shadow update with ghost field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const badPayload = { ...validDevicePayload(ALICE_UID), ghostAdmin: true };
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/devices/dev_1`).set(badPayload));
});

test("Relay message creation requires an active pairing", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/pairings/pair_1`).set(validPairingPayload(ALICE_UID, "pending"))
  );

  const ts = nowTs();
  const relayPayload = {
    userId: ALICE_UID,
    pairingId: "pair_1",
    sourceDeviceId: "dev_primary_1",
    targetDeviceId: "dev_remote_1",
    encryptedPayload: "ZW5jcnlwdGVkX3BheWxvYWQ=",
    iv: "cmFuZG9tX2l2XzEy",
    direction: "inbound_sms",
    status: "pending",
    createdAt: ts,
    updatedAt: ts,
  };

  // Fails when pairing is still pending (never forward to unpaired devices)
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/relay_messages/msg_1`).set(relayPayload));

  // Succeeds when pairing is active
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/pairings/pair_2`).set(validPairingPayload(ALICE_UID, "active"))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/relay_messages/msg_2`).set({ ...relayPayload, pairingId: "pair_2" })
  );

  // Can delete temporary relay message after local delivery
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/relay_messages/msg_2`).delete());
});

test("Pairing lifecycle: join -> mutual confirm -> revoke locks terminal state", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const pairingRef = aliceDb.doc(`users/${ALICE_UID}/pairings/pair_flow`);
  await assertSucceeds(pairingRef.set(validPairingPayload(ALICE_UID, "pending")));

  // Remote joins
  await assertSucceeds(
    pairingRef.update({
      remoteDeviceId: "dev_remote_1",
      remoteDeviceName: "Pixel Tablet",
      remotePublicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0987654321",
      verificationSas: "739-201",
      remoteConfirmed: true,
      status: "awaiting_confirmation",
      updatedAt: nowTs(),
    })
  );

  // Primary confirms -> active
  await assertSucceeds(
    pairingRef.update({
      primaryConfirmed: true,
      status: "active",
      updatedAt: nowTs(),
    })
  );

  // Revoke -> revoked
  await assertSucceeds(
    pairingRef.update({
      status: "revoked",
      updatedAt: nowTs(),
    })
  );

  // Terminal state locking: cannot reactivate once revoked
  await assertFails(
    pairingRef.update({
      status: "active",
      updatedAt: nowTs(),
    })
  );
});
