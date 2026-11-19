/**
 * FlinsMusic Authoritative Party Room Service
 * Manages room-scoped state for social listening party:
 * - Authoritative shared queue with idempotency and revision tracking
 * - Real-time live chat with rate limiting and text sanitization
 * - Transient live reactions (Heart, Fire, Laugh, Wow, Sad)
 * - Authoritative song voting with deterministic tie-breaking
 * - Member presence states (ONLINE, RECONNECTING, BUFFERING, DISCONNECTED)
 * - Host recovery handling
 */

class PartyRoomService {
  constructor() {
    this.rooms = new Map(); // roomCode -> RoomState
    this.socketToRoom = new Map(); // socketId -> { roomCode, userId }
    this.reactionTypes = ['HEART', 'FIRE', 'LAUGH', 'WOW', 'SAD'];
  }

  normalizeRoomCode(input) {
    if (!input) return '';
    let code = String(input).trim().toUpperCase();
    if (code.includes('CODE=')) {
      const match = code.match(/CODE=([^&]+)/);
      if (match) code = match[1];
    }
    code = code.replace(/\s+/g, '');
    if (/^\d{4,6}$/.test(code)) {
      code = `FM-${code}`;
    }
    return code;
  }

  generateRoomCode() {
    let code;
    do {
      const num = Math.floor(1000 + Math.random() * 9000);
      code = `FM-${num}`;
    } while (this.rooms.has(code));
    return code;
  }

  getRoom(code) {
    if (!code) return null;
    const normalized = this.normalizeRoomCode(code);
    return this.rooms.get(normalized) || null;
  }

  createRoom({ name, isPublic = true, hostName = 'Host', hostUserId, socketId, customCode = null, description = '', visibility = 'PUBLIC', coverUrl = null, maxParticipants = 20 }) {
    const code = customCode ? this.normalizeRoomCode(customCode) : this.generateRoomCode();
    const roomId = `room_${Date.now()}`;
    const myHostId = hostUserId || `host_${Date.now()}`;

    const newRoom = {
      id: roomId,
      code,
      name: name && name.trim() ? name.trim() : `${hostName}'s Room`,
      description: description ? String(description).slice(0, 200).trim() : '',
      visibility: visibility || (isPublic ? 'PUBLIC' : 'PRIVATE'),
      coverUrl: coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
      maxParticipants: Math.min(100, Math.max(2, Number(maxParticipants) || 20)),
      isLocked: false,
      isChatMuted: false,
      repeatMode: 'OFF',
      shuffleMode: false,
      pendingSongRequests: [],
      hostId: myHostId,
      hostName,
      isPublic: Boolean(isPublic),
      createdAt: Date.now(),
      participants: new Map([
        [
          myHostId,
          {
            id: myHostId,
            name: hostName,
            avatarColorHex: '#00F0FF',
            isHost: true,
            isListening: true,
            status: 'ONLINE',
            socketId,
            lastSeenMs: Date.now()
          }
        ]
      ]),
      currentTrackId: null,
      currentTrack: null,
      isPlaying: false,
      playbackPositionMs: 0,
      lastEventTimestamp: Date.now(),
      sequenceNumber: 1,
      sharedQueue: [], // Array<{ opId, track, addedByUserId, addedByUserName, addedAtMs }>
      queueRevision: 0,
      recentMessageIds: new Set(), // Volatile message deduplication set
      isHostRecovering: false,
      processedOpIds: new Map(), // opId -> timestampMs (bounded idempotency cache)
      rateLimits: new Map(), // userId -> { chat: [timestamps], reaction: [timestamps] }
      disconnectTimers: new Map() // userId -> timeoutId
    };

    this.rooms.set(code, newRoom);
    if (socketId) {
      this.socketToRoom.set(socketId, { roomCode: code, userId: myHostId });
    }

    return this.toAuthoritativeState(newRoom);
  }

  joinRoom({ code, userId, userName, socketId, isHost = false }) {
    const room = this.getRoom(code);
    if (!room) {
      return { error: 'Room not found. Check the code and try again.' };
    }

    const myUserId = (userId && String(userId).trim()) ? String(userId).trim() : (socketId || `user_${Date.now()}`);
    const myName = userName && userName.trim() ? userName.trim() : `Listener ${room.participants.size + 1}`;

    // Cancel pending disconnect grace timer if reconnecting
    if (room.disconnectTimers && room.disconnectTimers.has(myUserId)) {
      clearTimeout(room.disconnectTimers.get(myUserId));
      room.disconnectTimers.delete(myUserId);
    }

    // Host authority: Only the creator (or transferred host) whose ID matches room.hostId can be host
    const isActualHost = (myUserId === room.hostId);
    if (isActualHost) {
      room.isHostRecovering = false;
    } else {
      if (room.isLocked) {
        return { error: 'Room is currently locked by the host.' };
      }
      if (room.participants.size >= (room.maxParticipants || 20) && !room.participants.has(myUserId)) {
        return { error: 'Room has reached maximum participant capacity.' };
      }
    }

    // Deduplicate: Clean up any old participant entry with the same socketId
    for (const [pId, p] of room.participants.entries()) {
      if (pId !== myUserId && socketId && p.socketId === socketId) {
        if (room.disconnectTimers && room.disconnectTimers.has(pId)) {
          clearTimeout(room.disconnectTimers.get(pId));
          room.disconnectTimers.delete(pId);
        }
        room.participants.delete(pId);
      }
    }

    const existing = room.participants.get(myUserId);
    const palette = ['#8B5CF6', '#38BDF8', '#EC4899', '#10B981', '#F59E0B', '#6366F1'];
    const avatarColorHex = existing?.avatarColorHex || (isActualHost
      ? '#00F0FF'
      : palette[room.participants.size % palette.length]);

    // Clean up previous socket mapping if socket changed (reconnect)
    if (existing && existing.socketId && existing.socketId !== socketId) {
      this.socketToRoom.delete(existing.socketId);
    }

    room.participants.set(myUserId, {
      id: myUserId,
      userId: myUserId,
      name: myName,
      avatarColorHex,
      isHost: isActualHost,
      isListening: true,
      isConnected: true,
      status: 'ONLINE',
      socketId,
      lastSeenMs: Date.now()
    });

    if (socketId) {
      this.socketToRoom.set(socketId, { roomCode: room.code, userId: myUserId });
    }

    return { success: true, room: this.toAuthoritativeState(room), participant: room.participants.get(myUserId) };
  }

  leaveRoom({ code, userId, socketId }) {
    let targetCode = code ? this.normalizeRoomCode(code) : '';
    let targetUserId = userId;

    if (socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping) {
        if (!targetCode) targetCode = mapping.roomCode;
        if (!targetUserId) targetUserId = mapping.userId;
        this.socketToRoom.delete(socketId);
      }
    }

    let room = this.getRoom(targetCode);
    if (!room && (targetUserId || socketId)) {
      for (const r of this.rooms.values()) {
        if ((targetUserId && r.participants.has(targetUserId)) ||
            (socketId && Array.from(r.participants.values()).some(p => p.socketId === socketId))) {
          room = r;
          targetCode = r.code;
          break;
        }
      }
    }
    if (!room) return null;

    if (targetUserId) {
      if (room.disconnectTimers && room.disconnectTimers.has(targetUserId)) {
        clearTimeout(room.disconnectTimers.get(targetUserId));
        room.disconnectTimers.delete(targetUserId);
      }
      room.participants.delete(targetUserId);
    }

    if (socketId) {
      for (const [pId, p] of room.participants.entries()) {
        if (p.socketId === socketId) {
          if (room.disconnectTimers && room.disconnectTimers.has(pId)) {
            clearTimeout(room.disconnectTimers.get(pId));
            room.disconnectTimers.delete(pId);
          }
          room.participants.delete(pId);
        }
      }
    }

    const isHost = (targetUserId === room.hostId);
    if (isHost) {
      room.isHostRecovering = true;
    }

    // If room is completely empty, clean up after 60s
    if (room.participants.size === 0) {
      const cleanupTimer = setTimeout(() => {
        if (room.participants.size === 0) {
          this.rooms.delete(room.code);
        }
      }, 60000);
      if (cleanupTimer.unref) cleanupTimer.unref();
    }

    return { room: this.toAuthoritativeState(room), remainingCount: room.participants.size, isHost, code: room.code, userId: targetUserId };
  }

  endRoom({ code, userId, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) {
        isHost = true;
      }
    }

    if (!isHost) {
      return { error: 'Permission denied. Only the host can end the party for everyone.' };
    }

    // Cancel all disconnect timers
    if (room.disconnectTimers) {
      for (const timer of room.disconnectTimers.values()) {
        clearTimeout(timer);
      }
      room.disconnectTimers.clear();
    }

    // Remove all socket mappings for this room
    for (const [sId, mapping] of this.socketToRoom.entries()) {
      if (mapping.roomCode === room.code) {
        this.socketToRoom.delete(sId);
      }
    }

    // Authoritative room deletion
    this.rooms.delete(room.code);

    return {
      success: true,
      roomCode: room.code,
      reason: 'The host has ended the party session for everyone.'
    };
  }

  handleDisconnect(socketId, onGraceExpired) {
    const mapping = this.socketToRoom.get(socketId);
    if (!mapping) return null;

    this.socketToRoom.delete(socketId);
    const room = this.getRoom(mapping.roomCode);
    if (!room) return null;

    const participant = room.participants.get(mapping.userId);
    if (!participant) return null;

    // Stale socket protection: if user already reconnected on a new socket, do not mark disconnected
    if (participant.socketId && participant.socketId !== socketId) {
      return null;
    }

    participant.status = 'DISCONNECTED';
    participant.isConnected = false;
    participant.lastSeenMs = Date.now();

    const isHost = (participant.id === room.hostId);
    if (isHost) {
      room.isHostRecovering = true;
    }

    // Set a 30s grace period for reconnection
    const timer = setTimeout(() => {
      room.disconnectTimers.delete(participant.id);
      if (participant.status === 'DISCONNECTED') {
        room.participants.delete(participant.id);
        if (typeof onGraceExpired === 'function') {
          onGraceExpired({
            roomCode: room.code,
            userId: participant.id,
            isHost,
            room: this.toAuthoritativeState(room),
            remainingCount: room.participants.size
          });
        }
      }
    }, 30000);
    if (timer.unref) timer.unref();

    room.disconnectTimers.set(participant.id, timer);

    return {
      roomCode: room.code,
      userId: participant.id,
      isHost,
      state: this.toAuthoritativeState(room)
    };
  }

  updateMemberStatus({ code, userId, status }) {
    const room = this.getRoom(code);
    if (!room) return null;
    const p = room.participants.get(userId);
    if (!p) return null;

    const validStatuses = ['ONLINE', 'RECONNECTING', 'BUFFERING', 'DISCONNECTED'];
    if (validStatuses.includes(status)) {
      p.status = status;
      p.lastSeenMs = Date.now();
    }
    return this.toAuthoritativeState(room);
  }

  syncPlayback({ code, userId, trackId, position, isPlaying, action = 'sync', timestamp, seq, track, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    // Membership check: only connected room members can synchronize playback
    let member = room.participants.get(userId);
    if (!member && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.roomCode === room.code) {
        member = room.participants.get(mapping.userId);
      }
    }
    if (!member) {
      for (const p of room.participants.values()) {
        if (p.id === userId || (socketId && p.socketId === socketId)) {
          member = p;
          break;
        }
      }
    }
    if (!member) {
      return { error: 'Must be in the room to sync playback' };
    }

    // Host authority enforcement: Only the host controls room playback and current track.
    // Non-host members cannot emit conflicting sync events that overwrite the host's current track.
    // Host authority enforcement: Non-host members cannot overwrite room current track via sync
    const isHost = (member.id === room.hostId || Boolean(member.isHost));
    if (!isHost) {
      if (trackId && room.currentTrackId && trackId !== room.currentTrackId) {
        return { dropped: true, reason: 'Non-host cannot overwrite room current track' };
      }
    }

    const ts = timestamp && timestamp > 0 ? Number(timestamp) : Date.now();
    const sequence = seq && seq > 0 ? Number(seq) : (room.sequenceNumber + 1);

    // Reject out-of-order stale playback updates
    if (sequence <= room.sequenceNumber && ts <= room.lastEventTimestamp) {
      return { dropped: true, reason: 'Stale playback event' };
    }

    if (trackId) room.currentTrackId = trackId;
    if (position !== undefined && position !== null) room.playbackPositionMs = Math.max(0, Number(position));
    if (isPlaying !== undefined && isPlaying !== null) room.isPlaying = Boolean(isPlaying);

    if (track) {
      room.currentTrack = {
        id: track.id || trackId,
        title: track.title || 'Shared Track',
        artist: track.artist || 'Together Session',
        artwork: track.artwork || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
        durationSeconds: Number(track.durationSeconds) || 180
      };
    }

    room.lastEventTimestamp = ts;
    room.sequenceNumber = Math.max(room.sequenceNumber + 1, sequence);

    return { success: true, room: this.toAuthoritativeState(room), action };
  }

  // Authoritative Queue Management with Idempotency & Revision
  addToQueue({ code, userId, track, opId, addedByName }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };
    if (!track || !track.id) return { error: 'Invalid track data' };

    const operationId = opId || `op_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;

    // Idempotency: Reject duplicate submission from network retries
    if (room.processedOpIds.has(operationId)) {
      return { success: true, duplicate: true, room: this.toAuthoritativeState(room) };
    }
    room.processedOpIds.set(operationId, Date.now());
    this.cleanOpIds(room);

    const safeTrack = {
      id: String(track.id),
      title: String(track.title || 'Untitled Track'),
      artist: String(track.artist || 'Unknown Artist'),
      artwork: String(track.artwork || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'),
      durationSeconds: Number(track.durationSeconds) || 180
    };

    const sender = room.participants.get(userId);
    const queueItem = {
      opId: operationId,
      track: safeTrack,
      addedByUserId: userId || 'unknown',
      addedByUserName: addedByName || (sender ? sender.name : 'Participant'),
      addedAtMs: Date.now()
    };

    room.sharedQueue.push(queueItem);
    room.queueRevision += 1;

    // If room is currently empty and not playing, set this track as current
    if (!room.currentTrackId) {
      room.currentTrackId = safeTrack.id;
      room.currentTrack = safeTrack;
      room.isPlaying = true;
      room.playbackPositionMs = 0;
      room.lastEventTimestamp = Date.now();
      room.sequenceNumber += 1;
    }

    return { success: true, queueItem, room: this.toAuthoritativeState(room) };
  }

  removeFromQueue({ code, userId, opId, trackIndex }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let removeIdx = -1;
    if (opId) {
      removeIdx = room.sharedQueue.findIndex(item => item.opId === opId);
    } else if (typeof trackIndex === 'number' && trackIndex >= 0 && trackIndex < room.sharedQueue.length) {
      removeIdx = trackIndex;
    }

    if (removeIdx === -1) {
      return { error: 'Queue item not found' };
    }

    const item = room.sharedQueue[removeIdx];
    const isHost = (userId === room.hostId);
    const isOwner = (userId === item.addedByUserId);

    if (!isHost && !isOwner) {
      return { error: 'Permission denied. Only host or submitter can remove tracks.' };
    }

    room.sharedQueue.splice(removeIdx, 1);
    room.queueRevision += 1;

    return { success: true, room: this.toAuthoritativeState(room) };
  }

  reorderQueue({ code, userId, fromIndex, toIndex }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    // Host only operation
    if (userId !== room.hostId) {
      return { error: 'Permission denied. Only the host can reorder the queue.' };
    }

    if (fromIndex < 0 || fromIndex >= room.sharedQueue.length || toIndex < 0 || toIndex >= room.sharedQueue.length) {
      return { error: 'Invalid indices for queue reorder' };
    }

    const [moved] = room.sharedQueue.splice(fromIndex, 1);
    room.sharedQueue.splice(toIndex, 0, moved);
    room.queueRevision += 1;

    return { success: true, room: this.toAuthoritativeState(room) };
  }

  changeTrack({ code, userId, trackId, opId, track, position = 0, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    // Validate membership: user must be in room.participants or mapped by socket
    let member = room.participants.get(userId);
    if (!member && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.roomCode === room.code) {
        member = room.participants.get(mapping.userId);
      }
    }
    if (!member) {
      for (const p of room.participants.values()) {
        if (p.id === userId || (socketId && p.socketId === socketId)) {
          member = p;
          break;
        }
      }
    }
    if (!member) {
      return { error: 'Must be a member of the room to change tracks' };
    }

    // Look for track in shared queue if opId or trackId provided
    let queueIndex = -1;
    if (opId) {
      queueIndex = room.sharedQueue.findIndex(item => item.opId === opId);
    } else if (trackId) {
      queueIndex = room.sharedQueue.findIndex(item => item.track && item.track.id === trackId);
    }

    let selectedTrack = null;
    if (queueIndex !== -1) {
      const [removed] = room.sharedQueue.splice(queueIndex, 1);
      selectedTrack = removed.track;
      room.queueRevision += 1;
    } else if (track && track.id) {
      selectedTrack = {
        id: String(track.id),
        title: String(track.title || 'Shared Track'),
        artist: String(track.artist || 'Together Session'),
        artwork: String(track.artwork || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'),
        durationSeconds: Number(track.durationSeconds) || 180
      };
    } else if (trackId) {
      selectedTrack = {
        id: String(trackId),
        title: 'Shared Track',
        artist: 'Together Session',
        artwork: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
        durationSeconds: 180
      };
    } else {
      return { error: 'Invalid track or queue data' };
    }

    const startPos = Math.max(0, Number(position) || 0);
    room.currentTrackId = selectedTrack.id;
    room.currentTrack = selectedTrack;
    room.playbackPositionMs = startPos;
    room.isPlaying = true;
    room.lastEventTimestamp = Date.now();
    room.sequenceNumber += 1;

    return {
      success: true,
      room: this.toAuthoritativeState(room),
      track: selectedTrack,
      changedByUserId: member.id,
      changedByUserName: member.name
    };
  }

  advanceQueue({ code, userId, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    // Validate membership
    let member = room.participants.get(userId);
    if (!member && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.roomCode === room.code) {
        member = room.participants.get(mapping.userId);
      }
    }
    if (!member) {
      for (const p of room.participants.values()) {
        if (p.id === userId || (socketId && p.socketId === socketId)) {
          member = p;
          break;
        }
      }
    }
    if (!member) {
      return { error: 'Must be a member of the room to advance queue' };
    }

    if (room.sharedQueue.length === 0) {
      room.isPlaying = false;
      return { success: true, empty: true, room: this.toAuthoritativeState(room) };
    }

    const nextItem = room.sharedQueue.shift();
    room.queueRevision += 1;

    room.currentTrackId = nextItem.track.id;
    room.currentTrack = nextItem.track;
    room.playbackPositionMs = 0;
    room.isPlaying = true;
    room.lastEventTimestamp = Date.now();
    room.sequenceNumber += 1;

    return { success: true, nextItem, room: this.toAuthoritativeState(room) };
  }

  // Live Chat with Rate Limiting, Membership Verification, and Ephemeral Relay (no persistent storage)
  sendChatMessage({ code, userId, messageId, text, sharedTrack, senderName, senderAvatarColorHex, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    // Check membership: first by userId, then by socketId mapping or participant socketId
    let member = room.participants.get(userId);
    if (!member && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.roomCode === room.code) {
        member = room.participants.get(mapping.userId);
      }
    }
    if (!member) {
      for (const p of room.participants.values()) {
        if (p.id === userId || (socketId && p.socketId === socketId)) {
          member = p;
          break;
        }
      }
    }
    if (!member) return { error: 'Must be in the room to chat' };

    const actualSenderId = member.id;

    if (room.isChatMuted && actualSenderId !== room.hostId) {
      return { error: 'Chat has been muted by the host.' };
    }

    // Rate limiting: Max 5 messages per 3 seconds
    if (this.isRateLimited(room, actualSenderId, 'chat', 5, 3000)) {
      return { error: 'You are sending messages too fast. Please slow down.' };
    }

    const rawText = String(text || '').trim();
    if (!rawText && !sharedTrack) {
      return { error: 'Message cannot be empty' };
    }

    if (rawText.length > 500) {
      return { error: 'Message exceeds 500 characters' };
    }

    // Length limit: max 500 characters
    const sanitizedText = this.sanitizeText(rawText.slice(0, 500));
    const msgId = messageId || `msg_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;

    // In-memory volatile deduplication cache for recent message IDs (no database, no disk, no persistence)
    if (!room.recentMessageIds) {
      room.recentMessageIds = new Set();
    }
    if (room.recentMessageIds.has(msgId)) {
      return { success: true, duplicate: true };
    }
    room.recentMessageIds.add(msgId);
    if (room.recentMessageIds.size > 200) {
      const oldest = Array.from(room.recentMessageIds).slice(0, 50);
      oldest.forEach(id => room.recentMessageIds.delete(id));
    }

    const newMsg = {
      id: msgId,
      roomId: room.code,
      code: room.code,
      senderId: actualSenderId,
      senderName: senderName || member.name,
      senderAvatarColorHex: senderAvatarColorHex || member.avatarColorHex,
      text: sanitizedText,
      timestampMs: Date.now(),
      sharedTrack: sharedTrack ? {
        id: sharedTrack.id,
        title: sharedTrack.title,
        artist: sharedTrack.artist,
        artwork: sharedTrack.artwork,
        durationSeconds: sharedTrack.durationSeconds
      } : null
    };

    // Messages are strictly ephemeral - never saved to any database or disk
    return { success: true, message: newMsg };
  }

  // Live Reactions
  sendReaction({ code, userId, reactionId, type, senderName }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    const member = room.participants.get(userId);
    if (!member) return { error: 'Must be in room to react' };

    // Rate limiting: Max 5 reactions per 3 seconds
    if (this.isRateLimited(room, userId, 'reaction', 5, 3000)) {
      return { error: 'Too many reactions' };
    }

    const normalizedType = String(type || '').toUpperCase();
    if (!this.reactionTypes.includes(normalizedType)) {
      return { error: 'Invalid reaction type' };
    }

    const emojiMap = {
      HEART: '❤️',
      FIRE: '🔥',
      LAUGH: '😂',
      WOW: '😮',
      SAD: '😢'
    };

    const reactionEvent = {
      id: reactionId || `rx_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      senderId: userId,
      senderName: senderName || member.name,
      type: normalizedType,
      emoji: emojiMap[normalizedType],
      timestampMs: Date.now()
    };

    return { success: true, reaction: reactionEvent };
  }



  // Helpers
  isRateLimited(room, userId, actionType, maxCount, windowMs) {
    const now = Date.now();
    if (!room.rateLimits.has(userId)) {
      room.rateLimits.set(userId, { chat: [], reaction: [] });
    }
    const limits = room.rateLimits.get(userId);
    const history = limits[actionType] || [];
    const valid = history.filter(ts => now - ts < windowMs);
    if (valid.length >= maxCount) {
      limits[actionType] = valid;
      return true;
    }
    valid.push(now);
    limits[actionType] = valid;
    return false;
  }

  sanitizeText(text) {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  cleanOpIds(room) {
    if (room.processedOpIds.size > 200) {
      const now = Date.now();
      for (const [op, ts] of room.processedOpIds.entries()) {
        if (now - ts > 60000) {
          room.processedOpIds.delete(op);
        }
      }
    }
  }

  getPublicRooms({ query = '', limit = 50 } = {}) {
    const list = [];
    const q = (query || '').toLowerCase().trim();
    for (const room of this.rooms.values()) {
      if (room.isPublic && !room.isLocked) {
        if (q) {
          const matchName = (room.name || '').toLowerCase().includes(q);
          const matchDesc = (room.description || '').toLowerCase().includes(q);
          const matchHost = (room.hostName || '').toLowerCase().includes(q);
          const matchCode = (room.code || '').toLowerCase().includes(q);
          if (!matchName && !matchDesc && !matchHost && !matchCode) continue;
        }
        list.push({
          roomId: room.code,
          code: room.code,
          id: room.id,
          name: room.name,
          description: room.description || '',
          coverUrl: room.coverUrl || '',
          hostId: room.hostId,
          hostName: room.hostName,
          participantCount: room.participants.size,
          maxParticipants: room.maxParticipants || 20,
          currentTrack: room.currentTrack,
          isPlaying: room.isPlaying,
          isLocked: Boolean(room.isLocked),
          createdAt: room.createdAt
        });
      }
    }
    // Sort by active participant count descending
    list.sort((a, b) => b.participantCount - a.participantCount);
    return list.slice(0, Math.max(1, limit));
  }

  transferHost({ code, userId, targetUserId, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only current host can transfer host privileges.' };
    }

    const target = room.participants.get(targetUserId);
    if (!target) {
      return { error: 'Target participant not found in room.' };
    }

    const oldHost = room.participants.get(room.hostId);
    if (oldHost) oldHost.isHost = false;

    target.isHost = true;
    room.hostId = target.id;
    room.hostName = target.name;
    room.isHostRecovering = false;

    return {
      success: true,
      room: this.toAuthoritativeState(room),
      newHostId: target.id,
      newHostName: target.name
    };
  }

  kickMember({ code, userId, targetUserId, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can remove participants.' };
    }

    if (targetUserId === room.hostId) {
      return { error: 'Cannot kick room host.' };
    }

    const target = room.participants.get(targetUserId);
    if (!target) {
      return { error: 'Participant not found.' };
    }

    if (target.socketId) {
      this.socketToRoom.delete(target.socketId);
    }
    room.participants.delete(targetUserId);

    return {
      success: true,
      room: this.toAuthoritativeState(room),
      kickedUserId: targetUserId,
      targetSocketId: target.socketId
    };
  }

  setRoomLock({ code, userId, isLocked, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can lock or unlock the room.' };
    }

    room.isLocked = Boolean(isLocked);
    return { success: true, room: this.toAuthoritativeState(room), isLocked: room.isLocked };
  }

  setChatMuted({ code, userId, isChatMuted, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can mute or unmute chat.' };
    }

    room.isChatMuted = Boolean(isChatMuted);
    return { success: true, room: this.toAuthoritativeState(room), isChatMuted: room.isChatMuted };
  }

  deleteChatMessage({ code, userId, messageId, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can delete messages.' };
    }

    return { success: true, messageId };
  }

  submitSongRequest({ code, userId, track, requestedByName }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };
    if (!track || !track.id) return { error: 'Invalid track' };

    if (!room.pendingSongRequests) room.pendingSongRequests = [];

    // Duplicate check in pending requests or current queue
    const trackIdStr = String(track.id);
    const inPending = room.pendingSongRequests.some(r => String(r.track?.id) === trackIdStr);
    if (inPending) {
      return { error: 'This track is already in the pending request queue.' };
    }

    const member = room.participants.get(userId);
    const req = {
      requestId: `req_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      track: {
        id: trackIdStr,
        title: String(track.title || 'Requested Track'),
        artist: String(track.artist || 'Unknown Artist'),
        artwork: String(track.artwork || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80'),
        durationSeconds: Number(track.durationSeconds) || 180
      },
      requestedByUserId: userId || 'unknown',
      requestedByUserName: requestedByName || (member ? member.name : 'Listener'),
      timestampMs: Date.now()
    };

    room.pendingSongRequests.push(req);
    return { success: true, request: req, room: this.toAuthoritativeState(room) };
  }

  reviewSongRequest({ code, userId, requestId, approved, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can review song requests.' };
    }

    if (!room.pendingSongRequests) room.pendingSongRequests = [];
    const idx = room.pendingSongRequests.findIndex(r => r.requestId === requestId);
    if (idx === -1) {
      return { error: 'Song request not found' };
    }

    const [req] = room.pendingSongRequests.splice(idx, 1);

    if (approved) {
      this.addToQueue({
        code,
        userId: req.requestedByUserId,
        track: req.track,
        addedByName: req.requestedByUserName
      });
    }

    return {
      success: true,
      requestId,
      approved: Boolean(approved),
      track: req.track,
      requestedByUserId: req.requestedByUserId,
      room: this.toAuthoritativeState(room)
    };
  }

  setPlaybackModes({ code, userId, repeatMode, shuffleMode, socketId }) {
    const room = this.getRoom(code);
    if (!room) return { error: 'Room not found' };

    let isHost = (userId === room.hostId);
    if (!isHost && socketId) {
      const mapping = this.socketToRoom.get(socketId);
      if (mapping && mapping.userId === room.hostId) isHost = true;
    }
    if (!isHost) {
      return { error: 'Permission denied. Only host can change playback modes.' };
    }

    if (repeatMode !== undefined) {
      const valid = ['OFF', 'ALL', 'ONE'];
      if (valid.includes(repeatMode)) room.repeatMode = repeatMode;
    }
    if (shuffleMode !== undefined) {
      room.shuffleMode = Boolean(shuffleMode);
    }

    return { success: true, room: this.toAuthoritativeState(room) };
  }

  toAuthoritativeState(room) {
    return {
      roomId: room.code,
      code: room.code,
      id: room.id,
      name: room.name,
      description: room.description || '',
      visibility: room.visibility || (room.isPublic ? 'PUBLIC' : 'PRIVATE'),
      coverUrl: room.coverUrl || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
      maxParticipants: room.maxParticipants || 20,
      isLocked: Boolean(room.isLocked),
      isChatMuted: Boolean(room.isChatMuted),
      repeatMode: room.repeatMode || 'OFF',
      shuffleMode: Boolean(room.shuffleMode),
      hostId: room.hostId,
      hostName: room.hostName,
      isPublic: room.isPublic,
      isPlaying: room.isPlaying,
      currentTrackId: room.currentTrackId,
      playbackPositionMs: room.playbackPositionMs,
      currentTrack: room.currentTrack,
      lastEventTimestamp: room.lastEventTimestamp,
      sequenceNumber: room.sequenceNumber,
      isHostRecovering: room.isHostRecovering,
      participants: Array.from(room.participants.values()).map(p => ({
        id: p.id,
        userId: p.id,
        name: p.name,
        avatarColorHex: p.avatarColorHex,
        isHost: p.isHost,
        isListening: p.isListening,
        isConnected: p.isConnected !== false,
        status: p.status
      })),
      sharedQueue: room.sharedQueue.map(item => ({
        opId: item.opId,
        track: item.track,
        addedByUserId: item.addedByUserId,
        addedByUserName: item.addedByUserName,
        addedAtMs: item.addedAtMs
      })),
      pendingSongRequests: (room.pendingSongRequests || []).map(req => ({
        requestId: req.requestId,
        id: req.requestId,
        track: req.track,
        requestedByUserId: req.requestedByUserId,
        requestedByUserName: req.requestedByUserName,
        timestampMs: req.timestampMs
      })),
      queueRevision: room.queueRevision,
      chatMessages: [] // Ephemeral chat: no persistent or server-stored history
    };
  }
}

module.exports = new PartyRoomService();
