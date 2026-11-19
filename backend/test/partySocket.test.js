const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const { io: Client } = require('socket.io-client');
const app = require('../src/server');
const partyRoomService = require('../src/services/partyRoomService');

let server;
let serverPort;

const clientSockets = [];
function createClientSocket() {
  const s = Client(`http://localhost:${serverPort}`, {
    transports: ['websocket'],
    forceNew: true,
    reconnection: false
  });
  clientSockets.push(s);
  return s;
}

function waitFor(socket, event, predicate = null, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    if (event === 'connect' && socket.connected) {
      return resolve();
    }

    const timer = setTimeout(() => {
      socket.off(event, listener);
      reject(new Error(`Timed out waiting for event "${event}" after ${timeoutMs}ms`));
    }, timeoutMs);

    const listener = (data) => {
      if (!predicate || predicate(data)) {
        clearTimeout(timer);
        socket.off(event, listener);
        resolve(data);
      }
    };

    socket.on(event, listener);
  });
}

test.before(async () => {
  server = app.server;
  await new Promise((resolve) => {
    server.listen(0, () => {
      serverPort = server.address().port;
      resolve();
    });
  });
});

test.after(async () => {
  clientSockets.forEach(s => {
    try { s.close(); } catch (_) {}
  });
  if (app.io) {
    app.io.close();
  }
  if (server) {
    await new Promise(r => server.close(r));
  }
});



// ============================================================================
// 1. SHARED QUEUE REGRESSION TESTS
// ============================================================================

test('Shared Queue - Add track, idempotency, remove and reorder', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    // 1. Host creates room
    const statePromise = waitFor(hostSocket, 'room:state');
    hostSocket.emit('room:create', {
      name: 'Queue Test Room',
      hostName: 'AliceHost',
      userId: 'usr_host_1',
      code: 'FM-Q100'
    });
    const createdState = await statePromise;
    assert.strictEqual(createdState.code, 'FM-Q100');

    // 2. Listener joins room
    const listenerJoinedPromise = waitFor(listenerSocket, 'room:state');
    listenerSocket.emit('room:join', {
      code: 'FM-Q100',
      userId: 'usr_listener_1',
      userName: 'BobListener'
    });
    await listenerJoinedPromise;

    // 3. Listener adds Track 1
    const track1 = {
      id: 'trk_1',
      title: 'Song Alpha',
      artist: 'Artist A',
      artwork: 'https://example.com/1.jpg',
      durationSeconds: 200
    };
    const queueUpdatePromise = waitFor(hostSocket, 'queue:update');
    listenerSocket.emit('queue:add', {
      code: 'FM-Q100',
      userId: 'usr_listener_1',
      userName: 'BobListener',
      track: track1,
      opId: 'op_add_1'
    });

    const update1 = await queueUpdatePromise;
    assert.strictEqual(update1.sharedQueue.length, 1);
    assert.strictEqual(update1.sharedQueue[0].track.title, 'Song Alpha');
    assert.strictEqual(update1.sharedQueue[0].addedByUserName, 'BobListener');

    // 4. Duplicate submission with same opId is rejected idempotently
    listenerSocket.emit('queue:add', {
      code: 'FM-Q100',
      userId: 'usr_listener_1',
      userName: 'BobListener',
      track: track1,
      opId: 'op_add_1' // same opId
    });
    await new Promise(r => setTimeout(r, 100));
    // Queue length must still be 1!
    assert.strictEqual(update1.sharedQueue.length, 1);

    // 5. Host adds Track 2 and Track 3
    const track2 = { id: 'trk_2', title: 'Song Beta', artist: 'Artist B', durationSeconds: 180 };
    const track3 = { id: 'trk_3', title: 'Song Gamma', artist: 'Artist C', durationSeconds: 220 };

    const updatePromise2 = waitFor(listenerSocket, 'queue:update');
    hostSocket.emit('queue:add', { code: 'FM-Q100', userId: 'usr_host_1', track: track2, opId: 'op_add_2' });
    await updatePromise2;

    const updatePromise3 = waitFor(listenerSocket, 'queue:update');
    hostSocket.emit('queue:add', { code: 'FM-Q100', userId: 'usr_host_1', track: track3, opId: 'op_add_3' });
    const update3 = await updatePromise3;
    assert.strictEqual(update3.sharedQueue.length, 3);
    assert.strictEqual(update3.sharedQueue[1].track.id, 'trk_2');
    assert.strictEqual(update3.sharedQueue[2].track.id, 'trk_3');

    // 6. Host reorders: moves track 3 to index 0
    const reorderPromise = waitFor(listenerSocket, 'queue:update');
    hostSocket.emit('queue:reorder', {
      code: 'FM-Q100',
      userId: 'usr_host_1',
      fromIndex: 2,
      toIndex: 0
    });
    const reordered = await reorderPromise;
    assert.strictEqual(reordered.sharedQueue[0].track.id, 'trk_3');

    // 7. Remove track: non-owner and non-host cannot remove
    const forbiddenCallback = await new Promise(res => {
      listenerSocket.emit('queue:remove', {
        code: 'FM-Q100',
        userId: 'usr_listener_1',
        opId: 'op_add_3' // added by host
      }, res);
    });
    assert.strictEqual(forbiddenCallback.success, false);
    assert.match(forbiddenCallback.error, /Permission denied/i);

    // Host can remove track
    const removePromise = waitFor(listenerSocket, 'queue:update');
    hostSocket.emit('queue:remove', {
      code: 'FM-Q100',
      userId: 'usr_host_1',
      opId: 'op_add_3'
    });
    const removed = await removePromise;
    assert.strictEqual(removed.sharedQueue.length, 2);
    assert.ok(removed.sharedQueue.every(i => i.opId !== 'op_add_3'));

  } finally {
    hostSocket.close();
    listenerSocket.close();
  }
});

// ============================================================================
// 2. LIVE CHAT & REACTIONS REGRESSION TESTS
// ============================================================================

test('Chat & Reactions - Message delivery, track sharing, rate limiting, and reaction events', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    const roomCode = 'FM-CHAT1';
    hostSocket.emit('room:create', { name: 'Chat Room', hostName: 'Host1', userId: 'h1', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    listenerSocket.emit('room:join', { code: roomCode, userId: 'l1', userName: 'Listener1' });
    await waitFor(listenerSocket, 'room:state');

    // 1. Text Message Delivery to other members
    const chatReceivePromise = waitFor(hostSocket, 'chat:message');
    listenerSocket.emit('chat:send', {
      code: roomCode,
      userId: 'l1',
      senderName: 'Listener1',
      text: 'Hello party people! 🎉',
      messageId: 'msg_101'
    });
    const receivedChat = await chatReceivePromise;
    assert.strictEqual(receivedChat.id, 'msg_101');
    assert.strictEqual(receivedChat.text, 'Hello party people! 🎉');
    assert.strictEqual(receivedChat.senderName, 'Listener1');

    // 2. Track Sharing in Chat
    const shareTrackPromise = waitFor(listenerSocket, 'chat:message', d => d.id === 'msg_102');
    hostSocket.emit('chat:send', {
      code: roomCode,
      userId: 'h1',
      senderName: 'Host1',
      text: 'Check out this banger!',
      sharedTrack: {
        id: 'trk_jam_1',
        title: 'Midnight Echoes',
        artist: 'Luna Ray',
        artwork: 'https://example.com/luna.jpg',
        durationSeconds: 240
      },
      messageId: 'msg_102'
    });
    const trackMsg = await shareTrackPromise;
    assert.strictEqual(trackMsg.sharedTrack.title, 'Midnight Echoes');
    assert.strictEqual(trackMsg.sharedTrack.artist, 'Luna Ray');

    // 3. Reaction Event Delivery
    const reactionPromise = waitFor(hostSocket, 'reaction:event');
    listenerSocket.emit('reaction:send', {
      code: roomCode,
      userId: 'l1',
      senderName: 'Listener1',
      type: 'FIRE'
    });
    const reaction = await reactionPromise;
    assert.strictEqual(reaction.type, 'FIRE');
    assert.strictEqual(reaction.emoji, '🔥');
    assert.strictEqual(reaction.senderName, 'Listener1');

    // 4. Rate Limiting on Chat (Sending > 5 messages in quick succession)
    let rateLimited = false;
    for (let i = 0; i < 7; i++) {
      const res = await new Promise(res => {
        listenerSocket.emit('chat:send', {
          code: roomCode,
          userId: 'l1',
          text: `Spam test ${i}`,
          messageId: `msg_spam_${i}`
        }, res);
      });
      if (res && res.error && res.error.includes('too fast')) {
        rateLimited = true;
        break;
      }
    }
    assert.strictEqual(rateLimited, true, 'User should be rate limited after exceeding threshold');

  } finally {
    hostSocket.close();
    listenerSocket.close();
  }
});

// ============================================================================
// 3. CHAT REGRESSION & VOTING REMOVAL TESTS
// ============================================================================

test('Chat - Room isolation, membership enforcement, message length limit, and error reporting', async () => {
  const roomASocket1 = createClientSocket();
  const roomASocket2 = createClientSocket();
  const roomBSocket = createClientSocket();

  await waitFor(roomASocket1, 'connect');
  await waitFor(roomASocket2, 'connect');
  await waitFor(roomBSocket, 'connect');

  try {
    const roomA = 'FM-ROOMA';
    const roomB = 'FM-ROOMB';

    // Room A setup
    roomASocket1.emit('room:create', { name: 'Room A', hostName: 'HostA', userId: 'usr_host_a', code: roomA });
    await waitFor(roomASocket1, 'room:state');

    roomASocket2.emit('room:join', { code: roomA, userId: 'usr_member_a', userName: 'MemberA' });
    await waitFor(roomASocket2, 'room:state');

    // Room B setup
    roomBSocket.emit('room:create', { name: 'Room B', hostName: 'HostB', userId: 'usr_host_b', code: roomB });
    await waitFor(roomBSocket, 'room:state');

    // 1. Two connected members in Room A exchange chat messages
    const a2ReceivePromise = waitFor(roomASocket2, 'chat:message');
    let bReceivedMessage = false;
    roomBSocket.on('chat:message', () => { bReceivedMessage = true; });

    const sendRes = await new Promise(resolve => {
      roomASocket1.emit('chat:send', {
        code: roomA,
        userId: 'usr_host_a',
        senderName: 'HostA',
        text: 'Hello Room A members!',
        messageId: 'msg_a_1'
      }, resolve);
    });

    assert.strictEqual(sendRes.success, true);
    const msg = await a2ReceivePromise;
    assert.strictEqual(msg.id, 'msg_a_1');
    assert.strictEqual(msg.text, 'Hello Room A members!');
    assert.strictEqual(msg.senderName, 'HostA');
    assert.strictEqual(msg.roomId, roomA);
    assert.strictEqual(msg.code, roomA);

    // 2. Room Isolation: Member in Room B did NOT receive Room A message
    assert.strictEqual(bReceivedMessage, false, 'Message must not leak across different rooms');

    // 3. Unauthorized sender (not in room) is rejected with clear error
    const nonMemberSocket = createClientSocket();
    await waitFor(nonMemberSocket, 'connect');
    try {
      const unauthorizedRes = await new Promise(resolve => {
        nonMemberSocket.emit('chat:send', {
          code: roomA,
          userId: 'usr_intruder',
          text: 'I am not in this room',
          messageId: 'msg_unauth'
        }, resolve);
      });
      assert.strictEqual(unauthorizedRes.success, false);
      assert.ok(unauthorizedRes.error.includes('Must be in the room to chat'));
    } finally {
      nonMemberSocket.close();
    }

    // 4. Message length limit (500 chars)
    const longText = 'A'.repeat(501);
    const longMsgRes = await new Promise(resolve => {
      roomASocket1.emit('chat:send', {
        code: roomA,
        userId: 'usr_host_a',
        text: longText,
        messageId: 'msg_too_long'
      }, resolve);
    });
    assert.strictEqual(longMsgRes.success, false);
    assert.ok(longMsgRes.error.includes('500 characters'));

  } finally {
    roomASocket1.close();
    roomASocket2.close();
    roomBSocket.close();
  }
});

test('Voting Removed - Voting events ignored, queue and auto-advancement preserved', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    const roomCode = 'FM-NOVOTE';
    hostSocket.emit('room:create', { name: 'Party Room', hostName: 'HostUser', userId: 'h_nv', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    listenerSocket.emit('room:join', { code: roomCode, userId: 'l_nv', userName: 'ListenerUser' });
    await waitFor(listenerSocket, 'room:state');

    // Add songs to shared queue
    const t1 = { id: 'nv_trk_1', title: 'Track One', artist: 'Artist One', durationSeconds: 180 };
    const t2 = { id: 'nv_trk_2', title: 'Track Two', artist: 'Artist Two', durationSeconds: 200 };

    await new Promise(r => hostSocket.emit('queue:add', { code: roomCode, userId: 'h_nv', track: t1, opId: 'op_nv_1' }, r));
    await new Promise(r => hostSocket.emit('queue:add', { code: roomCode, userId: 'h_nv', track: t2, opId: 'op_nv_2' }, r));

    // 1. Voting events are no longer registered/emitted
    let voteStartedTriggered = false;
    listenerSocket.on('vote:started', () => { voteStartedTriggered = true; });
    hostSocket.emit('vote:start', { code: roomCode, userId: 'h_nv', durationSeconds: 30 });
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(voteStartedTriggered, false, 'vote:started should not fire as voting is removed');

    // 2. Shared queue automatic track progression works normally via queue:advance
    const queueUpdatePromise = waitFor(listenerSocket, 'playback:update');
    hostSocket.emit('queue:advance', { code: roomCode, userId: 'h_nv' });
    const playbackUpdate = await queueUpdatePromise;
    assert.strictEqual(playbackUpdate.currentTrackId, 'nv_trk_1');
    assert.strictEqual(playbackUpdate.isPlaying, true);

  } finally {
    hostSocket.close();
    listenerSocket.close();
  }
});

// 4. INVITATIONS & ROOM JOINING REGRESSION TESTS
// ============================================================================

test('Invitations - Joining invalid room produces proper error message', async () => {
  const socket = createClientSocket();
  await waitFor(socket, 'connect');

  try {
    const errorPromise = waitFor(socket, 'room:error');
    socket.emit('room:join', {
      code: 'FM-DOESNOTEXIST',
      userId: 'usr_rand',
      userName: 'Ghost'
    });
    const err = await errorPromise;
    assert.strictEqual(err.message, 'Room not found. Check the code and try again.');
  } finally {
    socket.close();
  }
});

// ============================================================================
// 5. PLAYBACK SYNCHRONIZATION & RECOVERY REGRESSION TESTS
// ============================================================================

test('Playback Sync - Multi-device playback, seek, late join, stale event drop, and host recovery', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    const roomCode = 'FM-SYNC1';
    hostSocket.emit('room:create', { name: 'Playback Room', hostName: 'HostSync', userId: 'h_sync', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    listenerSocket.emit('room:join', { code: roomCode, userId: 'l_sync', userName: 'ListenerSync' });
    await waitFor(listenerSocket, 'room:state');

    // 1. Host Plays Track A
    const playPromise = waitFor(listenerSocket, 'playback:update');
    hostSocket.emit('sync_playback', {
      code: roomCode,
      userId: 'h_sync',
      trackId: 'trk_alpha',
      position: 10000,
      isPlaying: true,
      action: 'play',
      timestamp: 5000,
      seq: 2,
      track: { id: 'trk_alpha', title: 'Alpha', artist: 'Artist A', durationSeconds: 200 }
    });
    const playUpdate = await playPromise;
    assert.strictEqual(playUpdate.currentTrackId, 'trk_alpha');
    assert.strictEqual(playUpdate.playbackPositionMs, 10000);
    assert.strictEqual(playUpdate.isPlaying, true);

    // 2. Host Seeks
    const seekPromise = waitFor(listenerSocket, 'player:seek');
    hostSocket.emit('player:seek', {
      code: roomCode,
      userId: 'h_sync',
      trackId: 'trk_alpha',
      position: 45000,
      action: 'seek',
      timestamp: 6000,
      seq: 3
    });
    const seekUpdate = await seekPromise;
    assert.strictEqual(seekUpdate.playbackPositionMs, 45000);

    // 3. Late joiner connects and immediately receives authoritative state
    const lateJoiner = createClientSocket();
    await waitFor(lateJoiner, 'connect');
    const lateStatePromise = waitFor(lateJoiner, 'room:state');
    lateJoiner.emit('room:join', { code: roomCode, userId: 'late_usr', userName: 'LateJoiner' });
    const lateState = await lateStatePromise;
    assert.strictEqual(lateState.currentTrackId, 'trk_alpha');
    assert.strictEqual(lateState.playbackPositionMs, 45000);
    assert.strictEqual(lateState.isPlaying, true);
    lateJoiner.close();

    // 4. Stale event rejection: Out-of-order event with older timestamp must not alter state
    hostSocket.emit('sync_playback', {
      code: roomCode,
      userId: 'h_sync',
      trackId: 'trk_alpha',
      position: 5000,
      isPlaying: false,
      timestamp: 1000, // older than 6000!
      seq: 1
    });
    await new Promise(r => setTimeout(r, 100));

    // Verify room state retains pos 45000 and isPlaying true
    const checkStatePromise = new Promise(res => {
      listenerSocket.emit('room:join', { code: roomCode, userId: 'l_sync', userName: 'ListenerSync' }, res);
    });
    const verified = await checkStatePromise;
    assert.strictEqual(verified.room.playbackPositionMs, 45000);
    assert.strictEqual(verified.room.isPlaying, true);

    // 5. Host disconnect: room marked as recovering, preserves track and position
    const hostStatusPromise = waitFor(listenerSocket, 'room:member-status');
    hostSocket.close();
    const hostStatus = await hostStatusPromise;
    assert.strictEqual(hostStatus.userId, 'h_sync');
    assert.strictEqual(hostStatus.status, 'DISCONNECTED');
    assert.strictEqual(hostStatus.isHost, true);

  } finally {
    listenerSocket.close();
  }
});

// ============================================================================
// 6. TOGETHER SOCIAL PARTY TARGETED FIX REGRESSION TESTS
// ============================================================================

test('Seeking forward and backward synchronizes all room members with full event contract', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    const roomCode = 'FM-SEEK1';
    hostSocket.emit('room:create', { name: 'Seek Room', hostName: 'HostSeeker', userId: 'usr_h_seek', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    listenerSocket.emit('room:join', { code: roomCode, userId: 'usr_l_seek', userName: 'ListenerSeeker' });
    await waitFor(listenerSocket, 'room:state');

    // Start playing
    const playPromise = waitFor(listenerSocket, 'playback:update');
    hostSocket.emit('sync_playback', {
      code: roomCode,
      userId: 'usr_h_seek',
      trackId: 'track_seek_1',
      position: 10000,
      isPlaying: true,
      action: 'play',
      timestamp: Date.now(),
      seq: 2,
      track: { id: 'track_seek_1', title: 'Seeker Song', artist: 'Sync Artists', durationSeconds: 300 }
    });
    await playPromise;

    // 1. Host seeks FORWARD from 10s to 85s
    const forwardPromise = waitFor(listenerSocket, 'player:seek');
    hostSocket.emit('player:seek', {
      code: roomCode,
      userId: 'usr_h_seek',
      trackId: 'track_seek_1',
      position: 85000,
      action: 'seek',
      timestamp: Date.now(),
      seq: 3
    });
    const forwardEvent = await forwardPromise;
    assert.strictEqual(forwardEvent.roomId, roomCode);
    assert.strictEqual(forwardEvent.trackId, 'track_seek_1');
    assert.strictEqual(forwardEvent.positionMs, 85000);
    assert.strictEqual(forwardEvent.senderId, 'usr_h_seek');
    assert.ok(forwardEvent.serverTimestamp > 0);
    assert.ok(forwardEvent.seq >= 3);

    // 2. Non-host listener seeks BACKWARD from 85s to 22s
    const backwardPromise = waitFor(hostSocket, 'player:seek');
    listenerSocket.emit('player:seek', {
      code: roomCode,
      userId: 'usr_l_seek',
      trackId: 'track_seek_1',
      position: 22000,
      action: 'seek',
      timestamp: Date.now() + 10,
      seq: forwardEvent.seq + 1
    });
    const backwardEvent = await backwardPromise;
    assert.strictEqual(backwardEvent.roomId, roomCode);
    assert.strictEqual(backwardEvent.trackId, 'track_seek_1');
    assert.strictEqual(backwardEvent.positionMs, 22000);
    assert.strictEqual(backwardEvent.senderId, 'usr_l_seek');
    assert.ok(backwardEvent.serverTimestamp > 0);
    assert.ok(backwardEvent.seq > forwardEvent.seq);

    // Verify room state is updated to 22000ms
    const roomState = partyRoomService.getRoom(roomCode);
    assert.strictEqual(roomState.playbackPositionMs, 22000);
    assert.strictEqual(roomState.currentTrackId, 'track_seek_1');

  } finally {
    hostSocket.close();
    listenerSocket.close();
  }
});

test('Any authorized room member can select a song and change shared current track', async () => {
  const hostSocket = createClientSocket();
  const memberSocket = createClientSocket();
  const outsiderSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(memberSocket, 'connect');
  await waitFor(outsiderSocket, 'connect');

  try {
    const roomCode = 'FM-CTRL1';
    hostSocket.emit('room:create', { name: 'Control Room', hostName: 'HostUser', userId: 'usr_host_ctrl', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    memberSocket.emit('room:join', { code: roomCode, userId: 'usr_member_ctrl', userName: 'MemberUser' });
    await waitFor(memberSocket, 'room:state');

    // Add track to shared queue
    const queueTrack = { id: 'queue_track_1', title: 'Queue Song', artist: 'Queue Artist', durationSeconds: 240 };
    await new Promise(res => {
      memberSocket.emit('queue:add', { code: roomCode, userId: 'usr_member_ctrl', track: queueTrack, opId: 'op_ctrl_1' }, res);
    });

    // 1. Non-host member changes track by selecting from queue
    const hostTrackPromise = waitFor(hostSocket, 'playback:track_change');
    const memberTrackPromise = waitFor(memberSocket, 'playback:track_change');

    memberSocket.emit('track:change', {
      code: roomCode,
      userId: 'usr_member_ctrl',
      trackId: 'queue_track_1',
      opId: 'op_ctrl_1',
      position: 0
    });

    const hostChange = await hostTrackPromise;
    const memberChange = await memberTrackPromise;

    // Both received authoritative track change event
    assert.strictEqual(hostChange.currentTrackId, 'queue_track_1');
    assert.strictEqual(memberChange.currentTrackId, 'queue_track_1');
    assert.strictEqual(hostChange.changedByUserId, 'usr_member_ctrl');
    assert.strictEqual(hostChange.isPlaying, true);
    assert.strictEqual(hostChange.playbackPositionMs, 0);
    assert.ok(hostChange.sequenceNumber > 1);

    // Track was removed from sharedQueue with queueRevision incremented
    const updatedRoom = partyRoomService.getRoom(roomCode);
    assert.strictEqual(updatedRoom.sharedQueue.length, 0);
    assert.ok(updatedRoom.queueRevision >= 2);

    // 2. Unauthorized outsider cannot change track or sync playback
    const unauthorizedTrackRes = await new Promise(res => {
      outsiderSocket.emit('track:change', {
        code: roomCode,
        userId: 'usr_outsider',
        trackId: 'some_other_track',
        position: 0
      }, res);
    });
    assert.strictEqual(unauthorizedTrackRes.success, false);
    assert.ok(unauthorizedTrackRes.error.includes('Must be a member'));

    const unauthorizedSyncRes = await new Promise(res => {
      outsiderSocket.emit('sync_playback', {
        code: roomCode,
        userId: 'usr_outsider',
        trackId: 'some_other_track',
        position: 10000
      });
      outsiderSocket.once('playback:error', err => res(err));
      setTimeout(() => res({ timedOut: true }), 200);
    });
    assert.ok(unauthorizedSyncRes.error && unauthorizedSyncRes.error.includes('Must be in the room'));

  } finally {
    hostSocket.close();
    memberSocket.close();
    outsiderSocket.close();
  }
});

test('Ephemeral Chat - Complete relay flow, no persistent storage, deduplication, and zero reconnect history', async () => {
  const aliceSocket = createClientSocket();
  const bobSocket = createClientSocket();

  await waitFor(aliceSocket, 'connect');
  await waitFor(bobSocket, 'connect');

  try {
    const roomCode = 'FM-EPH1';
    aliceSocket.emit('room:create', { name: 'Ephemeral Room', hostName: 'Alice', userId: 'usr_alice', code: roomCode });
    await waitFor(aliceSocket, 'room:state');

    bobSocket.emit('room:join', { code: roomCode, userId: 'usr_bob', userName: 'Bob' });
    await waitFor(bobSocket, 'room:state');

    // 1. Alice sends message to Bob
    const bobReceivePromise = waitFor(bobSocket, 'chat:message');
    const aliceAck = await new Promise(res => {
      aliceSocket.emit('chat:send', {
        code: roomCode,
        userId: 'usr_alice',
        senderName: 'Alice',
        text: 'Hey Bob! Ephemeral vibes only 🎧',
        messageId: 'msg_eph_1'
      }, res);
    });

    // Sender gets delivery acknowledgement
    assert.strictEqual(aliceAck.success, true);
    assert.strictEqual(aliceAck.message.id, 'msg_eph_1');

    // Recipient receives relayed message
    const bobReceived = await bobReceivePromise;
    assert.strictEqual(bobReceived.id, 'msg_eph_1');
    assert.strictEqual(bobReceived.text, 'Hey Bob! Ephemeral vibes only 🎧');
    assert.strictEqual(bobReceived.senderId, 'usr_alice');

    // 2. Bob replies to Alice
    const aliceReceivePromise = waitFor(aliceSocket, 'chat:message');
    const bobAck = await new Promise(res => {
      bobSocket.emit('chat:send', {
        code: roomCode,
        userId: 'usr_bob',
        senderName: 'Bob',
        text: 'Got it Alice! Nothing saved to disk 🚀',
        messageId: 'msg_eph_2'
      }, res);
    });

    assert.strictEqual(bobAck.success, true);
    const aliceReceived = await aliceReceivePromise;
    assert.strictEqual(aliceReceived.id, 'msg_eph_2');
    assert.strictEqual(aliceReceived.text, 'Got it Alice! Nothing saved to disk 🚀');

    // 3. Duplicate message ID deduplication
    let duplicateRelayed = false;
    bobSocket.on('chat:message', (d) => {
      if (d.id === 'msg_eph_1') duplicateRelayed = true;
    });
    const dupRes = await new Promise(res => {
      aliceSocket.emit('chat:send', {
        code: roomCode,
        userId: 'usr_alice',
        text: 'Duplicate try',
        messageId: 'msg_eph_1'
      }, res);
    });
    assert.strictEqual(dupRes.duplicate, true);
    await new Promise(r => setTimeout(r, 100));
    assert.strictEqual(duplicateRelayed, false, 'Duplicate message ID must not be relayed');

    // 4. Verification that NO chat messages are stored in room state
    const roomState = partyRoomService.getRoom(roomCode);
    assert.strictEqual(roomState.chatMessages, undefined, 'room.chatMessages must not exist in memory room state');

    // 5. Reconnecting member or late joiner receives ZERO chat history
    const reconnectSocket = createClientSocket();
    await waitFor(reconnectSocket, 'connect');
    const reconnectStatePromise = waitFor(reconnectSocket, 'room:state');
    reconnectSocket.emit('room:join', { code: roomCode, userId: 'usr_charlie', userName: 'Charlie' });
    const reconnectState = await reconnectStatePromise;

    assert.deepStrictEqual(reconnectState.chatMessages, [], 'room:state chatMessages must be strictly empty');
    reconnectSocket.close();

  } finally {
    aliceSocket.close();
    bobSocket.close();
  }
});

test('Host End Party for Everyone - broadcasts room:ended and clears room state', async () => {
  const hostSocket = createClientSocket();
  const listenerSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(listenerSocket, 'connect');

  try {
    const roomCode = 'FM-END01';
    hostSocket.emit('room:create', {
      name: 'End Party Test Room',
      hostName: 'HostAlice',
      userId: 'usr_end_host',
      code: roomCode
    });
    await waitFor(hostSocket, 'room:state');

    listenerSocket.emit('room:join', {
      code: roomCode,
      userId: 'usr_end_listener',
      userName: 'ListenerBob'
    });
    await waitFor(listenerSocket, 'room:state');

    // Listener and Host should both receive room:ended
    const listenerEndedPromise = waitFor(listenerSocket, 'room:ended');
    const hostEndedPromise = waitFor(hostSocket, 'room:ended');

    hostSocket.emit('room:end', {
      code: roomCode,
      userId: 'usr_end_host'
    });

    const listenerEnded = await listenerEndedPromise;
    const hostEnded = await hostEndedPromise;

    assert.strictEqual(listenerEnded.code, roomCode);
    assert.strictEqual(hostEnded.code, roomCode);
    assert.ok(listenerEnded.reason.toLowerCase().includes('host'));

    // Server-side room must be completely cleaned up
    const roomState = partyRoomService.getRoom(roomCode);
    assert.ok(!roomState, 'Room should be deleted after host ends party');
  } finally {
    hostSocket.close();
    listenerSocket.close();
  }
});

test('Room Code Normalization & Structured ACK on join - handles raw digits and invalid rooms', async () => {
  const hostSocket = createClientSocket();
  const memberSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(memberSocket, 'connect');

  try {
    // 1. Host creates with 4-digit code '9876' -> normalized to 'FM-9876'
    hostSocket.emit('room:create', {
      name: 'Normalization Room',
      hostName: 'NormHost',
      userId: 'usr_norm_host',
      code: '9876'
    });
    const hostState = await waitFor(hostSocket, 'room:state');
    assert.strictEqual(hostState.code, 'FM-9876');

    // 2. Member joins using lowercase 'fm-9876' and ACK callback
    const joinAck = await new Promise((res) => {
      memberSocket.emit('room:join', {
        code: 'fm-9876',
        userId: 'usr_norm_member',
        userName: 'NormMember'
      }, res);
    });

    assert.strictEqual(joinAck.success, true);
    assert.strictEqual(joinAck.room.code, 'FM-9876');
    assert.strictEqual(joinAck.participant.userId, 'usr_norm_member');
    assert.strictEqual(joinAck.participant.name, 'NormMember');
    assert.strictEqual(joinAck.room.participants.length, 2);

    // 3. Joining non-existent room returns structured error ACK
    const failAck = await new Promise((res) => {
      memberSocket.emit('room:join', {
        code: '9999',
        userId: 'usr_norm_member_2',
        userName: 'FailMember'
      }, res);
    });

    assert.strictEqual(failAck.success, false);
    assert.ok(failAck.error.includes('Room not found'));
  } finally {
    hostSocket.close();
    memberSocket.close();
  }
});

test('Live Presence & Stale Socket Protection - reconnecting participant maintains state and ignores stale socket disconnect', async () => {
  const hostSocket = createClientSocket();
  const memberSocket1 = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(memberSocket1, 'connect');

  try {
    const roomCode = 'FM-STALE1';
    hostSocket.emit('room:create', {
      name: 'Stale Socket Room',
      hostName: 'HostAlice',
      userId: 'usr_stale_host',
      code: roomCode
    });
    await waitFor(hostSocket, 'room:state');

    // Member joins on socket 1
    memberSocket1.emit('room:join', {
      code: roomCode,
      userId: 'usr_stale_member',
      userName: 'MemberBob'
    });
    await waitFor(memberSocket1, 'room:state');

    // Member now opens a new socket 2 (reconnect) before socket 1 disconnect event arrives
    const memberSocket2 = createClientSocket();
    await waitFor(memberSocket2, 'connect');

    memberSocket2.emit('room:join', {
      code: roomCode,
      userId: 'usr_stale_member',
      userName: 'MemberBob'
    });
    const stateOnSocket2 = await waitFor(memberSocket2, 'room:state');
    assert.strictEqual(stateOnSocket2.participants.length, 2, 'Should not create duplicate participant entries');

    // Now socket 1 disconnects (e.g. TCP timeout / background socket close)
    memberSocket1.close();
    await new Promise(r => setTimeout(r, 200));

    // The room should STILL have MemberBob connected on socket 2!
    const roomAfterStaleDisconnect = partyRoomService.getRoom(roomCode);
    const bob = roomAfterStaleDisconnect.participants.get('usr_stale_member');
    assert.strictEqual(bob.isConnected, true, 'Member should remain connected on socket 2');
    assert.strictEqual(bob.socketId, memberSocket2.id, 'Member socketId should point to socket 2');

    memberSocket2.close();
  } finally {
    hostSocket.close();
    memberSocket1.close();
  }
});

test('Participant join -> leave -> rejoin same room preserves exact single participant slot without duplicates', async () => {
  const hostSocket = createClientSocket();
  const participantSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(participantSocket, 'connect');

  try {
    const roomCode = 'FM-REJOIN1';
    hostSocket.emit('room:create', { name: 'Rejoin Test Room', hostName: 'HostAlice', userId: 'usr_host_rejoin', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    // 1. Participant joins room
    participantSocket.emit('room:join', {
      code: roomCode,
      userId: 'usr_participant_rejoin',
      userName: 'ParticipantBob'
    });
    const stateAfterJoin = await waitFor(participantSocket, 'room:state');
    assert.strictEqual(stateAfterJoin.participants.length, 2, 'Host + participant');
    assert.strictEqual(stateAfterJoin.participants.filter(p => p.id === 'usr_participant_rejoin').length, 1);

    // 2. Participant leaves room
    const leavePromise = waitFor(hostSocket, 'room:member-left');
    participantSocket.emit('room:leave', {
      code: roomCode,
      userId: 'usr_participant_rejoin'
    });
    await leavePromise;

    const roomAfterLeave = partyRoomService.getRoom(roomCode);
    assert.strictEqual(roomAfterLeave.participants.size, 1, 'Only host remaining');
    assert.strictEqual(roomAfterLeave.participants.has('usr_participant_rejoin'), false);

    // 3. Participant rejoins the SAME room with the same stable userId
    participantSocket.emit('room:join', {
      code: roomCode,
      userId: 'usr_participant_rejoin',
      userName: 'ParticipantBob'
    });
    const stateAfterRejoin = await waitFor(participantSocket, 'room:state');

    // CRITICAL: Exactly 2 participants, exactly 1 slot for ParticipantBob
    assert.strictEqual(stateAfterRejoin.participants.length, 2, 'Must have exactly 2 participants');
    const bobSlots = stateAfterRejoin.participants.filter(p => p.id === 'usr_participant_rejoin');
    assert.strictEqual(bobSlots.length, 1, 'ParticipantBob must have exactly ONE participant slot');
    assert.strictEqual(bobSlots[0].status, 'ONLINE');
    assert.strictEqual(bobSlots[0].isHost, false);

  } finally {
    hostSocket.close();
    participantSocket.close();
  }
});

test('Non-host member cannot overwrite host currentTrackId via sync_playback', async () => {
  const hostSocket = createClientSocket();
  const participantSocket = createClientSocket();

  await waitFor(hostSocket, 'connect');
  await waitFor(participantSocket, 'connect');

  try {
    const roomCode = 'FM-OVERWRITE1';
    hostSocket.emit('room:create', { name: 'Host Authority Room', hostName: 'HostAlice', userId: 'usr_host_auth', code: roomCode });
    await waitFor(hostSocket, 'room:state');

    participantSocket.emit('room:join', {
      code: roomCode,
      userId: 'usr_participant_auth',
      userName: 'ParticipantBob'
    });
    await waitFor(participantSocket, 'room:state');

    // Host starts playing Track A
    hostSocket.emit('sync_playback', {
      code: roomCode,
      userId: 'usr_host_auth',
      trackId: 'yt_host_song_A',
      position: 5000,
      isPlaying: true,
      action: 'play',
      timestamp: Date.now(),
      seq: 2,
      track: { id: 'yt_host_song_A', title: 'Host Track A', artist: 'Artist A', durationSeconds: 200 }
    });
    await waitFor(participantSocket, 'playback:update');

    const roomBefore = partyRoomService.getRoom(roomCode);
    assert.strictEqual(roomBefore.currentTrackId, 'yt_host_song_A');

    // Participant attempts to overwrite host's currentTrackId with Track B via sync_playback
    participantSocket.emit('sync_playback', {
      code: roomCode,
      userId: 'usr_participant_auth',
      trackId: 'yt_rogue_song_B',
      position: 0,
      isPlaying: true,
      action: 'play',
      timestamp: Date.now(),
      seq: 10,
      track: { id: 'yt_rogue_song_B', title: 'Rogue Track B', artist: 'Artist B', durationSeconds: 180 }
    });

    // Wait a brief tick to ensure any event would have processed
    await new Promise(r => setTimeout(r, 200));

    // CRITICAL: Room currentTrackId must STILL be the host's track A!
    const roomAfter = partyRoomService.getRoom(roomCode);
    assert.strictEqual(roomAfter.currentTrackId, 'yt_host_song_A', 'Host current track must not be overwritten by participant sync');

  } finally {
    hostSocket.close();
    participantSocket.close();
  }
});


