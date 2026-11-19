const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const { Server } = require('socket.io');
const { io: Client } = require('socket.io-client');
const partyRoomService = require('../src/services/partyRoomService');
const initPartySocket = require('../src/services/partySocket');

let server;
let io;
let port;

test.before((t, done) => {
  server = http.createServer();
  io = new Server(server, { cors: { origin: '*' } });
  initPartySocket(io);
  server.listen(0, () => {
    port = server.address().port;
    done();
  });
});

test.after((t, done) => {
  io.close();
  server.close(done);
});

function createClient() {
  return new Client(`http://localhost:${port}`, {
    transports: ['websocket'],
    forceNew: true
  });
}

function waitForEvent(socket, event, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Timed out waiting for event "${event}" after ${timeoutMs}ms`));
    }, timeoutMs);
    socket.once(event, (data) => {
      clearTimeout(timer);
      resolve(data);
    });
  });
}

test('Together 2.0 - Room Creation with metadata and Public Discovery', async () => {
  const host = createClient();
  await waitForEvent(host, 'connect');

  const createPromise = waitForEvent(host, 'room:state');
  host.emit('room:create', {
    name: 'Synthwave Chill',
    description: 'Late night retro vibes',
    coverUrl: 'https://images.unsplash.com/photo-test',
    maxParticipants: 10,
    isPublic: true,
    userName: 'DJ Host',
    userId: 'user_host_1'
  });

  const state = await createPromise;
  assert.equal(state.name, 'Synthwave Chill');
  assert.equal(state.description, 'Late night retro vibes');
  assert.equal(state.maxParticipants, 10);
  assert.equal(state.isPublic, true);

  // Discover public rooms
  const discPromise = waitForEvent(host, 'rooms:public_list');
  host.emit('rooms:list_public', { query: 'Synthwave' });
  const discovery = await discPromise;
  assert.ok(discovery.rooms.length >= 1);
  const found = discovery.rooms.find(r => r.code === state.code);
  assert.ok(found);
  assert.equal(found.name, 'Synthwave Chill');
  assert.equal(found.description, 'Late night retro vibes');

  host.disconnect();
});

test('Together 2.0 - Host Transfer and Kick Member', async () => {
  const host = createClient();
  const guest = createClient();
  await Promise.all([waitForEvent(host, 'connect'), waitForEvent(guest, 'connect')]);

  const createPromise = waitForEvent(host, 'room:state');
  host.emit('room:create', { name: 'Mod Room', userName: 'Original Host', userId: 'host_orig' });
  const room = await createPromise;

  const guestJoinPromise = waitForEvent(guest, 'room:state');
  guest.emit('room:join', { code: room.code, userName: 'Member Two', userId: 'user_two' });
  await guestJoinPromise;

  // Transfer host to Member Two
  const transferPromise = waitForEvent(guest, 'room:host_transferred');
  host.emit('room:transfer_host', { code: room.code, userId: 'host_orig', targetUserId: 'user_two' });
  const transferEvent = await transferPromise;
  assert.equal(transferEvent.newHostId, 'user_two');

  // New host kicks previous host
  const kickPromise = waitForEvent(host, 'room:kicked');
  guest.emit('room:kick_member', { code: room.code, userId: 'user_two', targetUserId: 'host_orig' });
  const kickEvent = await kickPromise;
  assert.ok(kickEvent.reason.includes('removed'));

  host.disconnect();
  guest.disconnect();
});

test('Together 2.0 - Room Lock and Chat Mute enforcement', async () => {
  const host = createClient();
  const guest = createClient();
  await Promise.all([waitForEvent(host, 'connect'), waitForEvent(guest, 'connect')]);

  const createPromise = waitForEvent(host, 'room:state');
  host.emit('room:create', { name: 'Protected Room', userName: 'Host', userId: 'host_prot' });
  const room = await createPromise;

  // Guest joins
  const joinPromise = waitForEvent(guest, 'room:state');
  guest.emit('room:join', { code: room.code, userName: 'Guest', userId: 'guest_user' });
  await joinPromise;

  // Host mutes chat
  const mutePromise = waitForEvent(guest, 'room:chat_mute_changed');
  host.emit('room:set_chat_muted', { code: room.code, userId: 'host_prot', isChatMuted: true });
  const muteEvent = await mutePromise;
  assert.equal(muteEvent.isChatMuted, true);

  // Guest attempts to chat -> should receive chat:error
  const chatErrPromise = waitForEvent(guest, 'chat:error');
  guest.emit('chat:send', { code: room.code, userId: 'guest_user', text: 'Hello!' });
  const chatErr = await chatErrPromise;
  assert.ok(chatErr.error.includes('muted'));

  // Host locks room
  const lockPromise = waitForEvent(guest, 'room:lock_changed');
  host.emit('room:set_lock', { code: room.code, userId: 'host_prot', isLocked: true });
  const lockEvent = await lockPromise;
  assert.equal(lockEvent.isLocked, true);

  // Third user attempts to join locked room -> rejected
  const outsider = createClient();
  await waitForEvent(outsider, 'connect');
  const rejectPromise = waitForEvent(outsider, 'room:error');
  outsider.emit('room:join', { code: room.code, userName: 'Late User', userId: 'late_user' });
  const reject = await rejectPromise;
  assert.ok(reject.message.includes('locked'));

  host.disconnect();
  guest.disconnect();
  outsider.disconnect();
});

test('Together 2.0 - Song Request Submission and Host Review', async () => {
  const host = createClient();
  const guest = createClient();
  await Promise.all([waitForEvent(host, 'connect'), waitForEvent(guest, 'connect')]);

  const createPromise = waitForEvent(host, 'room:state');
  host.emit('room:create', { name: 'Request Room', userName: 'Host', userId: 'host_req' });
  const room = await createPromise;

  const joinPromise = waitForEvent(guest, 'room:state');
  guest.emit('room:join', { code: room.code, userName: 'Guest', userId: 'guest_req' });
  await joinPromise;

  // Guest submits song request
  const reqPromise = waitForEvent(host, 'song_request:update');
  guest.emit('song_request:submit', {
    code: room.code,
    userId: 'guest_req',
    userName: 'Guest',
    track: {
      id: 'trk_req_101',
      title: 'Midnight City',
      artist: 'M83',
      artwork: 'https://art.jpg',
      durationSeconds: 240
    }
  });
  const reqUpdate = await reqPromise;
  assert.equal(reqUpdate.request.track.title, 'Midnight City');
  assert.equal(reqUpdate.pendingRequests.length, 1);

  // Host reviews and approves request
  const reviewPromise = waitForEvent(guest, 'song_request:reviewed');
  const queuePromise = waitForEvent(guest, 'queue:update');
  host.emit('song_request:review', {
    code: room.code,
    userId: 'host_req',
    requestId: reqUpdate.request.requestId,
    approved: true
  });

  const reviewed = await reviewPromise;
  assert.equal(reviewed.approved, true);
  assert.equal(reviewed.track.title, 'Midnight City');

  const qUpdate = await queuePromise;
  assert.ok(qUpdate.sharedQueue.some(item => item.track.id === 'trk_req_101'));

  host.disconnect();
  guest.disconnect();
});

