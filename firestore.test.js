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
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date();
}

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

test("Unauthenticated user: denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users_public").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("conversations").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("calls").where("memberUids", "array-contains", ALICE_UID).get());
});

test("User profile PII isolation: owner can write/read, another user cannot read", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = nowTs();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      handle: "alice_ucn",
      darkMode: "dark",
      readReceiptsEnabled: true,
      voipCodecProfile: "opus_48k_vp9_hd",
      autoSyncEnabled: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());

  // Owner can update displayName and compressed avatarBase64; another user cannot
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Vance",
      avatarBase64: "/9j/4AAQSkZJRgABAQAAAQABAAD/",
      updatedAt: ts,
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hacked Name",
      updatedAt: ts,
    })
  );
  // Shadow update with ghost field must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: ts,
    })
  );
});

test("Public identity & handle directory: get allowed for peers, list restricted to owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = nowTs();

  await assertSucceeds(
    aliceDb.collection("users_public").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      handle: "alice_ucn",
      publicKeyBase64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
      keyFingerprint: "ABCD-1234-EF56-7890",
      isOnline: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(bobDb.collection("users_public").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users_public").get());
  await assertSucceeds(bobDb.collection("users_public").where("userId", "==", BOB_UID).get());
});

test("Conversations & E2EE messages: members can chat and send receipts; outsiders blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = nowTs();

  const convId = "conv_alice_bob_1";
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).set({
      type: "direct",
      title: "Alice & Bob",
      createdBy: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      wrappedKeys: { [ALICE_UID]: "wrappedKeyA", [BOB_UID]: "wrappedKeyB" },
      keyVersion: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Outsider Charlie cannot get or list
  await assertFails(charlieDb.collection("conversations").doc(convId).get());
  await assertFails(charlieDb.collection("conversations").get());

  // Member Bob can list with matching array-contains filter
  await assertSucceeds(
    bobDb.collection("conversations").where("memberUids", "array-contains", BOB_UID).get()
  );

  // Member Alice posts encrypted message
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").set({
      conversationId: convId,
      senderId: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      messageType: "text",
      ciphertext: "ZW5jcnlwdGVkX3BheWxvYWQ=",
      iv: "MTIzNDU2Nzg5MDEy",
      keyVersion: 1,
      clientMessageId: "client_1",
      clientTimestampMs: 1700000000000,
      createdAt: ts,
    })
  );

  // Messages are immutable (cannot update/delete)
  await assertFails(
    aliceDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      ciphertext: "tampered",
    })
  );

  // Outsider Charlie cannot post message to Alice & Bob's chat
  await assertFails(
    charlieDb.collection("conversations").doc(convId).collection("messages").doc("msg_2").set({
      conversationId: convId,
      senderId: CHARLIE_UID,
      senderName: "Charlie",
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      messageType: "text",
      ciphertext: "aW5qZWN0ZWQ=",
      iv: "MTIzNDU2Nzg5MDEy",
      keyVersion: 1,
      clientMessageId: "client_2",
      clientTimestampMs: 1700000001000,
      createdAt: ts,
    })
  );

  // Member Bob writes his own read receipt; cannot forge Alice's receipt
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("readReceipts").doc(BOB_UID).set({
      conversationId: convId,
      userId: BOB_UID,
      displayName: "Bob",
      memberUids: [ALICE_UID, BOB_UID],
      lastDeliveredAtMs: 1700000000500,
      lastReadAtMs: 1700000000900,
      readReceiptsEnabled: true,
      updatedAt: ts,
    })
  );

  await assertFails(
    bobDb.collection("conversations").doc(convId).collection("readReceipts").doc(ALICE_UID).set({
      conversationId: convId,
      userId: ALICE_UID,
      displayName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      lastDeliveredAtMs: 1700000000500,
      lastReadAtMs: 1700000000900,
      readReceiptsEnabled: true,
      updatedAt: ts,
    })
  );
});

test("VoIP Calls: members can create, join, update participant telemetry, and terminal ended state locks", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = nowTs();

  const convId = "conv_group_1";
  await aliceDb.collection("conversations").doc(convId).set({
    type: "group",
    title: "Ops Group",
    createdBy: ALICE_UID,
    memberUids: [ALICE_UID, BOB_UID],
    memberNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
    wrappedKeys: { [ALICE_UID]: "k1", [BOB_UID]: "k2" },
    keyVersion: 1,
    createdAt: ts,
    updatedAt: ts,
  });

  const callId = "call_1";
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).set({
      conversationId: convId,
      conversationTitle: "Ops Group",
      initiatorId: ALICE_UID,
      initiatorName: "Alice",
      callType: "video",
      isGroup: true,
      status: "ringing",
      memberUids: [ALICE_UID, BOB_UID],
      activeParticipantUids: [ALICE_UID],
      codecProfile: "opus_48k_vp9_hd",
      sasCode: "742-918",
      startedAt: ts,
      updatedAt: ts,
    })
  );

  // Outsider Charlie denied
  await assertFails(charlieDb.collection("calls").doc(callId).get());

  // Bob joins call & publishes VoIP participant telemetry
  await assertSucceeds(
    bobDb.collection("calls").doc(callId).update({
      status: "active",
      activeParticipantUids: [ALICE_UID, BOB_UID],
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    bobDb.collection("calls").doc(callId).collection("participants").doc(BOB_UID).set({
      callId: callId,
      userId: BOB_UID,
      displayName: "Bob",
      memberUids: [ALICE_UID, BOB_UID],
      audioEnabled: true,
      videoEnabled: true,
      speakerEnabled: false,
      networkQuality: "excellent",
      rttMs: 24,
      jitterMs: 3,
      bitrateKbps: 1450,
      packetLossTenthsPct: 1,
      iceState: "connected",
      sdpFingerprint: "sha-256 AA:BB:CC:DD",
      joinedAt: ts,
      updatedAt: ts,
    })
  );

  // End call -> terminal state locked
  await assertSucceeds(
    aliceDb.collection("calls").doc(callId).update({
      status: "ended",
      activeParticipantUids: [],
      endedAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    bobDb.collection("calls").doc(callId).update({
      status: "active",
      activeParticipantUids: [BOB_UID],
      updatedAt: ts,
    })
  );
});

test("Contact discovery index & private contacts: hash point-lookup allowed, scraping & spoofing denied", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = nowTs();
  const aliceEmailHash = "a".repeat(64);
  const bobPhoneHash = "b".repeat(64);

  // Alice can index her own SHA-256 contact hash
  await assertSucceeds(
    aliceDb.collection("contact_index").doc(aliceEmailHash).set({
      hashId: aliceEmailHash,
      userId: ALICE_UID,
      identifierType: "email",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Unauthenticated caller denied
  await assertFails(unauthDb.collection("contact_index").doc(aliceEmailHash).get());

  // Non-hex / malformed hash rejected
  await assertFails(
    bobDb.collection("contact_index").doc("not_a_sha256_hex").set({
      hashId: "not_a_sha256_hex",
      userId: BOB_UID,
      identifierType: "phone",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob cannot spoof Alice's UID or overwrite Alice's hash entry
  await assertFails(
    bobDb.collection("contact_index").doc(aliceEmailHash).set({
      hashId: aliceEmailHash,
      userId: BOB_UID,
      identifierType: "email",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    bobDb.collection("contact_index").doc(bobPhoneHash).set({
      hashId: bobPhoneHash,
      userId: ALICE_UID,
      identifierType: "phone",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob CAN point-lookup a specific known hash (exact get), but CANNOT list-scrape all hashes
  await assertSucceeds(bobDb.collection("contact_index").doc(aliceEmailHash).get());
  await assertFails(bobDb.collection("contact_index").get());
  // Owner can list only their own hashes
  await assertSucceeds(
    aliceDb.collection("contact_index").where("userId", "==", ALICE_UID).get()
  );

  // Bob saves discovered friend Alice into Bob's private contacts subcollection
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("contacts").doc(ALICE_UID).set({
      userId: BOB_UID,
      peerUserId: ALICE_UID,
      peerDisplayName: "Alice Vance",
      peerHandle: "alice_ucn",
      peerPublicKeyBase64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
      peerKeyFingerprint: "ABCD-1234-EF56-7890",
      verified: true,
      deviceContactName: "Alice Work",
      discoverySource: "device_contacts",
      matchedVia: "email",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice cannot read or write Bob's private contacts
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("contacts").doc(ALICE_UID).get()
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("contacts").get()
  );
});

