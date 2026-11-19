/**
 * FlinsMusic Socket.IO Party Socket Handler
 * Connects Socket.IO clients to the authoritative PartyRoomService
 */
const partyRoomService = require('./partyRoomService');

function initPartySocket(io) {
  io.on('connection', (socket) => {
    // 1. Room Creation
    const handleCreateRoom = (data, callback) => {
      try {
        const payload = data || {};
        const state = partyRoomService.createRoom({
          name: payload.name,
          description: payload.description,
          visibility: payload.visibility,
          coverUrl: payload.coverUrl,
          maxParticipants: payload.maxParticipants,
          isPublic: payload.isPublic !== undefined ? payload.isPublic : true,
          hostName: payload.hostName || payload.userName || 'Host',
          hostUserId: payload.userId || socket.id,
          socketId: socket.id,
          customCode: payload.code || payload.roomId
        });
        socket.join(state.code);
        socket.emit('room:state', state);
        socket.emit('room_state', state);
        if (typeof callback === 'function') callback({ success: true, room: state });
      } catch (err) {
        socket.emit('room:error', { message: err.message });
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };
    socket.on('room:create', handleCreateRoom);
    socket.on('create_room', handleCreateRoom);

    // 2. Room Joining
    const handleJoinRoom = (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;
        const userName = payload.userName || payload.participantName || 'Listener';
        const isHost = Boolean(payload.isHost);

        const result = partyRoomService.joinRoom({
          code,
          userId,
          userName,
          socketId: socket.id,
          isHost
        });

        if (result.error) {
          socket.emit('room:error', { message: result.error });
          socket.emit('error', { message: result.error });
          if (typeof callback === 'function') callback({ success: false, error: result.error });
          return;
        }

        const roomCode = result.room.code;
        socket.join(roomCode);
        // Authoritative state to joiner
        socket.emit('room:state', result.room);
        socket.emit('room_state', result.room);

        // Notify other room participants
        socket.to(roomCode).emit('room:member-joined', {
          userId,
          name: userName,
          participant: result.participant
        });
        socket.to(roomCode).emit('room:state', result.room);

        if (typeof callback === 'function') callback({ success: true, room: result.room, participant: result.participant });
      } catch (err) {
        socket.emit('room:error', { message: err.message });
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };
    socket.on('room:join', handleJoinRoom);
    socket.on('join_room', handleJoinRoom);

    // 3. Room Departure
    const handleLeaveRoom = (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.leaveRoom({ code, userId, socketId: socket.id });
        const roomCode = res?.code || code;
        if (roomCode) {
          socket.leave(roomCode);
        }
        if (res && roomCode) {
          io.in(roomCode).emit('room:member-left', { userId: res.userId || userId, remainingCount: res.remainingCount });
          io.in(roomCode).emit('room:state', res.room);
        }
        if (typeof callback === 'function') callback({ success: true });
      } catch (err) {
        console.error('Error in handleLeaveRoom:', err);
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };
    socket.on('room:leave', handleLeaveRoom);
    socket.on('leave_room', handleLeaveRoom);

    // 3b. Host Deliberate End Party For Everyone
    const handleEndRoom = (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.endRoom({ code, userId, socketId: socket.id });
        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        // Notify all members that the host has ended the party
        io.in(code).emit('room:ended', {
          code,
          reason: res.reason
        });

        // Make all sockets leave room
        if (io.in(code).socketsLeave) {
          io.in(code).socketsLeave(code);
        }

        if (typeof callback === 'function') callback({ success: true, roomCode: code });
      } catch (err) {
        console.error('Error in handleEndRoom:', err);
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };
    socket.on('room:end', handleEndRoom);
    socket.on('party:end', handleEndRoom);

    // 4. Playback Synchronization
    const handlePlaybackSync = (data) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.syncPlayback({
          code,
          userId,
          trackId: payload.trackId || payload.currentTrackId,
          position: payload.position !== undefined ? payload.position : payload.playbackPositionMs,
          isPlaying: payload.isPlaying !== undefined ? payload.isPlaying : payload.playing,
          action: payload.action || 'sync',
          timestamp: payload.timestamp,
          seq: payload.seq || payload.sequence,
          track: payload.track || (payload.title ? {
            id: payload.trackId,
            title: payload.title,
            artist: payload.artist,
            artwork: payload.artwork,
            durationSeconds: payload.durationSeconds
          } : null),
          socketId: socket.id
        });

        if (res.dropped || res.error) {
          if (res.error) socket.emit('playback:error', { error: res.error });
          return;
        }

        const now = Date.now();
        const eventData = {
          ...res.room,
          roomId: code,
          code: code,
          trackId: res.room.currentTrackId,
          currentTrackId: res.room.currentTrackId,
          position: res.room.playbackPositionMs,
          positionMs: res.room.playbackPositionMs,
          playbackPositionMs: res.room.playbackPositionMs,
          isPlaying: res.room.isPlaying,
          action: res.action,
          senderId: userId,
          userId: userId,
          serverTimestamp: now,
          timestamp: res.room.lastEventTimestamp,
          seq: res.room.sequenceNumber,
          sequenceNumber: res.room.sequenceNumber,
          eventId: payload.eventId || `ev_${now}`
        };

        // Broadcast to all other room members
        socket.to(code).emit('playback:update', eventData);
        socket.to(code).emit('playback_update', eventData);
        socket.to(code).emit('playback:sync', eventData);
        socket.to(code).emit('sync_playback', eventData);

        if (res.action === 'seek') {
          socket.to(code).emit('player:seek', eventData);
          socket.to(code).emit('playback:seek', eventData);
        } else if (res.room.isPlaying) {
          socket.to(code).emit('player:play', eventData);
          socket.to(code).emit('playback:play', eventData);
        } else {
          socket.to(code).emit('player:pause', eventData);
          socket.to(code).emit('playback:pause', eventData);
        }
      } catch (err) {
        console.error('Error in handlePlaybackSync:', err);
      }
    };

    socket.on('sync_playback', handlePlaybackSync);
    socket.on('playback_sync', handlePlaybackSync);
    socket.on('playback:sync', handlePlaybackSync);
    socket.on('playback:update', handlePlaybackSync);
    socket.on('player:play', handlePlaybackSync);
    socket.on('player:pause', handlePlaybackSync);
    socket.on('player:seek', handlePlaybackSync);

    // 5. Shared Queue Operations
    socket.on('queue:add', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.addToQueue({
          code,
          userId,
          track: payload.track,
          opId: payload.opId,
          addedByName: payload.addedByName || payload.userName
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('queue:update', {
          sharedQueue: res.room.sharedQueue,
          queueRevision: res.room.queueRevision,
          queueItem: res.queueItem
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    socket.on('queue:remove', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.removeFromQueue({
          code,
          userId,
          opId: payload.opId,
          trackIndex: payload.trackIndex
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('queue:update', {
          sharedQueue: res.room.sharedQueue,
          queueRevision: res.room.queueRevision
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    socket.on('queue:reorder', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.reorderQueue({
          code,
          userId,
          fromIndex: payload.fromIndex,
          toIndex: payload.toIndex
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('queue:update', {
          sharedQueue: res.room.sharedQueue,
          queueRevision: res.room.queueRevision
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 5b. Authoritative Track Change for Any Member
    const handleTrackChange = (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.changeTrack({
          code,
          userId,
          trackId: payload.trackId || (payload.track ? payload.track.id : null),
          opId: payload.opId,
          track: payload.track,
          position: payload.position !== undefined ? payload.position : payload.playbackPositionMs,
          socketId: socket.id
        });

        if (res.error) {
          socket.emit('track:error', { error: res.error });
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        const now = Date.now();
        const eventData = {
          ...res.room,
          roomId: code,
          code: code,
          trackId: res.room.currentTrackId,
          currentTrackId: res.room.currentTrackId,
          position: res.room.playbackPositionMs,
          positionMs: res.room.playbackPositionMs,
          playbackPositionMs: res.room.playbackPositionMs,
          isPlaying: res.room.isPlaying,
          action: 'track_change',
          senderId: userId,
          userId: userId,
          changedByUserId: res.changedByUserId,
          changedByUserName: res.changedByUserName,
          serverTimestamp: now,
          timestamp: res.room.lastEventTimestamp,
          seq: res.room.sequenceNumber,
          sequenceNumber: res.room.sequenceNumber,
          eventId: payload.eventId || `ev_${now}`
        };

        // Broadcast authoritative track-change to ALL room members (including sender)
        io.in(code).emit('track:change', eventData);
        io.in(code).emit('track_change', eventData);
        io.in(code).emit('playback:track_change', eventData);
        io.in(code).emit('playback:update', eventData);
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room, track: res.track });
      } catch (err) {
        console.error('Error in handleTrackChange:', err);
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };

    socket.on('track:change', handleTrackChange);
    socket.on('track_change', handleTrackChange);
    socket.on('playback:track_change', handleTrackChange);
    socket.on('queue:select', handleTrackChange);

    socket.on('queue:advance', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.advanceQueue({ code, userId, socketId: socket.id });
        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        const now = Date.now();
        const eventData = {
          ...res.room,
          roomId: code,
          code: code,
          trackId: res.room.currentTrackId,
          currentTrackId: res.room.currentTrackId,
          position: res.room.playbackPositionMs,
          positionMs: res.room.playbackPositionMs,
          playbackPositionMs: res.room.playbackPositionMs,
          isPlaying: res.room.isPlaying,
          action: 'track_change',
          senderId: userId,
          userId: userId,
          serverTimestamp: now,
          timestamp: res.room.lastEventTimestamp,
          seq: res.room.sequenceNumber,
          sequenceNumber: res.room.sequenceNumber,
          eventId: `ev_${now}`
        };

        io.in(code).emit('playback:track_change', eventData);
        io.in(code).emit('track_change', eventData);
        io.in(code).emit('playback:update', eventData);
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        console.error('Error advancing queue:', err);
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 6. Live Chat (Ephemeral relay - never stored in database or disk)
    socket.on('chat:send', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.sendChatMessage({
          code,
          userId,
          messageId: payload.messageId || payload.id,
          text: payload.text,
          sharedTrack: payload.sharedTrack,
          senderName: payload.senderName || payload.userName,
          senderAvatarColorHex: payload.senderAvatarColorHex,
          socketId: socket.id
        });

        if (res.error) {
          socket.emit('chat:error', { error: res.error });
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        if (res.duplicate) {
          if (typeof callback === 'function') callback({ success: true, duplicate: true });
          return;
        }

        // Relay to other connected room members
        socket.to(code).emit('chat:message', res.message);
        // Acknowledge delivery to sender
        if (typeof callback === 'function') {
          callback({ success: true, message: res.message });
        } else {
          socket.emit('chat:message', res.message);
        }
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 7. Live Reactions
    socket.on('reaction:send', (data) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.sendReaction({
          code,
          userId,
          reactionId: payload.reactionId || payload.id,
          type: payload.type,
          senderName: payload.senderName || payload.userName
        });

        if (res.success) {
          io.in(code).emit('reaction:event', res.reaction);
        }
      } catch (err) {
        console.error('Error sending reaction:', err);
      }
    });

    // 9. Member Presence & Status
    socket.on('player:status', (data) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;
        const status = payload.status; // ONLINE, BUFFERING, RECONNECTING, DISCONNECTED

        const updatedRoom = partyRoomService.updateMemberStatus({ code, userId, status });
        if (updatedRoom) {
          socket.to(code).emit('room:member-status', { userId, status });
        }
      } catch (err) {
        console.error('Error updating player status:', err);
      }
    });

    // 9b. Heartbeat: keep member presence alive and reset any disconnect grace timer
    socket.on('room:heartbeat', (data) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const room = partyRoomService.getRoom(code);
        if (!room) return;

        // Cancel any pending removal timer for this user
        if (room.disconnectTimers && room.disconnectTimers.has(userId)) {
          clearTimeout(room.disconnectTimers.get(userId));
          room.disconnectTimers.delete(userId);
        }

        // Update the participant's lastSeenMs and restore ONLINE status
        partyRoomService.updateMemberStatus({ code, userId, status: 'ONLINE' });

        // ACK to sender
        socket.emit('room:heartbeat:ack', { ok: true, serverTimestamp: Date.now() });
      } catch (err) {
        console.error('Error handling room:heartbeat:', err);
      }
    });

    // 9c. Room Discovery (List Public Rooms)
    const handleListPublicRooms = (data, callback) => {
      try {
        const payload = data || {};
        const rooms = partyRoomService.getPublicRooms({
          query: payload.query || payload.q || '',
          limit: payload.limit || 50
        });
        socket.emit('rooms:public_list', { rooms });
        socket.emit('room:discovery', { rooms });
        if (typeof callback === 'function') callback({ success: true, rooms });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    };
    socket.on('rooms:list_public', handleListPublicRooms);
    socket.on('room:discovery', handleListPublicRooms);
    socket.on('rooms:get_public', handleListPublicRooms);

    // 9d. Host Transfer
    socket.on('room:transfer_host', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;
        const targetUserId = payload.targetUserId;

        const res = partyRoomService.transferHost({
          code,
          userId,
          targetUserId,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('room:host_transferred', {
          code,
          newHostId: res.newHostId,
          newHostName: res.newHostName
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9e. Kick Participant
    socket.on('room:kick_member', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;
        const targetUserId = payload.targetUserId;

        const res = partyRoomService.kickMember({
          code,
          userId,
          targetUserId,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        if (res.targetSocketId) {
          const targetSock = io.sockets.sockets.get(res.targetSocketId);
          if (targetSock) {
            targetSock.emit('room:kicked', { code, reason: 'You have been removed from the room by the host.' });
            targetSock.leave(code);
          }
        }

        io.in(code).emit('room:member-left', { userId: targetUserId, kicked: true });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9f. Lock / Unlock Room
    socket.on('room:set_lock', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.setRoomLock({
          code,
          userId,
          isLocked: payload.isLocked,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('room:lock_changed', { code, isLocked: res.isLocked });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room, isLocked: res.isLocked });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9g. Mute / Unmute Room Chat
    socket.on('room:set_chat_muted', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.setChatMuted({
          code,
          userId,
          isChatMuted: payload.isChatMuted,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('room:chat_mute_changed', { code, isChatMuted: res.isChatMuted });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room, isChatMuted: res.isChatMuted });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9h. Delete Chat Message (Host Moderation)
    socket.on('chat:delete', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;
        const messageId = payload.messageId || payload.id;

        const res = partyRoomService.deleteChatMessage({
          code,
          userId,
          messageId,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('chat:deleted', { messageId });

        if (typeof callback === 'function') callback({ success: true, messageId });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9i. Song Requests (Participants Request Songs)
    socket.on('song_request:submit', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.submitSongRequest({
          code,
          userId,
          track: payload.track,
          requestedByName: payload.requestedByName || payload.userName
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('song_request:update', {
          request: res.request,
          pendingRequests: res.room.pendingSongRequests
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, request: res.request, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9j. Song Request Review (Host Approves / Rejects)
    socket.on('song_request:review', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.reviewSongRequest({
          code,
          userId,
          requestId: payload.requestId,
          approved: payload.approved,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('song_request:reviewed', {
          requestId: res.requestId,
          approved: res.approved,
          track: res.track,
          requestedByUserId: res.requestedByUserId
        });

        if (res.approved) {
          io.in(code).emit('queue:update', {
            sharedQueue: res.room.sharedQueue,
            queueRevision: res.room.queueRevision
          });
        }
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 9k. Playback Modes (Repeat / Shuffle)
    socket.on('playback:modes', (data, callback) => {
      try {
        const payload = data || {};
        const rawCode = payload.code || payload.roomId || '';
        const code = partyRoomService.normalizeRoomCode(rawCode);
        const userId = payload.userId || socket.id;

        const res = partyRoomService.setPlaybackModes({
          code,
          userId,
          repeatMode: payload.repeatMode,
          shuffleMode: payload.shuffleMode,
          socketId: socket.id
        });

        if (res.error) {
          if (typeof callback === 'function') callback({ success: false, error: res.error });
          return;
        }

        io.in(code).emit('playback:modes', {
          repeatMode: res.room.repeatMode,
          shuffleMode: res.room.shuffleMode
        });
        io.in(code).emit('room:state', res.room);

        if (typeof callback === 'function') callback({ success: true, room: res.room });
      } catch (err) {
        if (typeof callback === 'function') callback({ success: false, error: err.message });
      }
    });

    // 10. Disconnect
    socket.on('disconnect', () => {
      try {
        const info = partyRoomService.handleDisconnect(socket.id, ({ roomCode, userId, isHost, room, remainingCount }) => {
          // Grace period expired: member permanently removed from room
          io.in(roomCode).emit('room:member-left', { userId, remainingCount, permanent: true });
          io.in(roomCode).emit('room:state', room);

          if (isHost) {
            // Host never recovered after grace period -> end party session
            io.in(roomCode).emit('room:ended', {
              code: roomCode,
              reason: 'Host connection lost permanently. Party session ended.'
            });
            if (io.in(roomCode).socketsLeave) {
              io.in(roomCode).socketsLeave(roomCode);
            }
          }
        });

        if (info) {
          socket.to(info.roomCode).emit('room:member-status', {
            userId: info.userId,
            status: 'DISCONNECTED',
            isHost: info.isHost
          });
          socket.to(info.roomCode).emit('room:state', info.state);
        }
      } catch (err) {
        console.error('Error handling socket disconnect:', err);
      }
    });
  });
}

module.exports = initPartySocket;
