const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const helmet = require('helmet');
const serverRoutes = require('./routes/servers');
const partyRoomService = require('./services/partyRoomService');
const initPartySocket = require('./services/partySocket');

const app = express();
const PORT = process.env.PORT || 8080;

// Security Middleware
app.use(helmet());
app.use(cors({
  origin: '*', // Configurable for mobile client access
  methods: ['GET', 'POST', 'PUT', 'DELETE'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Admin-Token']
}));

app.use(express.json({ limit: '100kb' }));

// Health Check
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'FlinsMusic Server Management API',
    timestamp: Date.now()
  });
});

// Together Listening 2.0 - Public Rooms Discovery
app.get('/api/rooms/public', (req, res) => {
  try {
    const query = req.query.q || req.query.query || '';
    const limit = parseInt(req.query.limit, 10) || 50;
    const rooms = partyRoomService.getPublicRooms({ query, limit });
    res.json({ success: true, rooms });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// API Routes
app.use('/api', serverRoutes);

// 404 Handler
app.use((req, res) => {
  res.status(404).json({ error: 'Endpoint not found' });
});

// Global Error Handler
app.use((err, req, res, next) => {
  console.error('Unhandled error:', err.message);
  res.status(500).json({ error: 'Internal server error' });
});

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

initPartySocket(io);

app.server = server;
app.io = io;

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`FlinsMusic Server Management & Together Party Backend running on port ${PORT}`);
  });
}

module.exports = app;

