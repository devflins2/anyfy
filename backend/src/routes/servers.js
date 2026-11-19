const express = require('express');
const router = express.Router();
const serverStore = require('../services/serverStore');
const { requireAdmin } = require('../security/auth');
const { validateServerUrl } = require('../security/ssrf');
const https = require('https');
const http = require('http');

/**
 * Public Endpoint: Returns active server configuration and enabled list.
 * Used by Android client apps to dynamically resolve the Socket.IO cluster.
 * Zero secrets or tokens exposed.
 */
router.get('/servers', (req, res) => {
  try {
    const config = serverStore.getPublicConfig();
    res.json({
      success: true,
      data: config
    });
  } catch (err) {
    res.status(500).json({ success: false, error: 'Failed to retrieve server configuration' });
  }
});

/**
 * Admin Endpoint: List all servers (enabled and disabled) with full metadata.
 */
router.get('/admin/servers', requireAdmin, (req, res) => {
  try {
    const servers = serverStore.getAll();
    res.json({
      success: true,
      data: servers
    });
  } catch (err) {
    res.status(500).json({ success: false, error: 'Failed to list servers' });
  }
});

/**
 * Admin Endpoint: Add a new Socket.IO server endpoint.
 */
router.post('/admin/servers', requireAdmin, async (req, res) => {
  const { name, url, isEnabled, isActive } = req.body;

  if (!name || typeof name !== 'string' || name.trim().length === 0) {
    return res.status(400).json({ success: false, error: 'Server name is required' });
  }

  const validation = await validateServerUrl(url, process.env.NODE_ENV !== 'production');
  if (!validation.isValid) {
    return res.status(400).json({ success: false, error: validation.error });
  }

  try {
    const newServer = serverStore.addServer({
      name: name.trim(),
      url: validation.normalizedUrl,
      isEnabled: isEnabled !== undefined ? Boolean(isEnabled) : true,
      isActive: Boolean(isActive)
    });

    res.status(201).json({
      success: true,
      data: newServer
    });
  } catch (err) {
    res.status(500).json({ success: false, error: 'Failed to add server' });
  }
});

/**
 * Admin Endpoint: Edit an existing server.
 */
router.put('/admin/servers/:id', requireAdmin, async (req, res) => {
  const { id } = req.params;
  const { name, url, isEnabled } = req.body;

  const existing = serverStore.getById(id);
  if (!existing) {
    return res.status(404).json({ success: false, error: 'Server not found' });
  }

  const updates = {};
  if (name !== undefined) {
    if (typeof name !== 'string' || name.trim().length === 0) {
      return res.status(400).json({ success: false, error: 'Server name cannot be blank' });
    }
    updates.name = name.trim();
  }

  if (url !== undefined) {
    const validation = await validateServerUrl(url, process.env.NODE_ENV !== 'production');
    if (!validation.isValid) {
      return res.status(400).json({ success: false, error: validation.error });
    }
    updates.url = validation.normalizedUrl;
  }

  if (isEnabled !== undefined) {
    updates.isEnabled = Boolean(isEnabled);
  }

  try {
    const updated = serverStore.updateServer(id, updates);
    res.json({
      success: true,
      data: updated
    });
  } catch (err) {
    res.status(500).json({ success: false, error: 'Failed to update server' });
  }
});

/**
 * Admin Endpoint: Activate a server.
 */
router.post('/admin/servers/:id/activate', requireAdmin, (req, res) => {
  const { id } = req.params;
  const result = serverStore.setActive(id);
  if (!result.success) {
    return res.status(400).json({ success: false, error: result.error });
  }

  res.json({
    success: true,
    data: result.activeServer
  });
});

/**
 * Admin Endpoint: Delete a server.
 */
router.delete('/admin/servers/:id', requireAdmin, (req, res) => {
  const { id } = req.params;
  const result = serverStore.deleteServer(id);
  if (!result.success) {
    return res.status(400).json({ success: false, error: result.error });
  }

  res.json({
    success: true,
    message: 'Server successfully deleted'
  });
});

/**
 * Admin Endpoint: Test Connection to a Socket.IO server.
 * Never claims reachable unless real probe succeeds.
 * Probes HTTPS or Socket.IO handshake with timeout and SSRF validation.
 */
router.post('/admin/servers/test', requireAdmin, async (req, res) => {
  const { url } = req.body;
  if (!url) {
    return res.status(400).json({ success: false, error: 'URL is required to test' });
  }

  const validation = await validateServerUrl(url, process.env.NODE_ENV !== 'production');
  if (!validation.isValid) {
    return res.status(400).json({ success: false, error: validation.error });
  }

  const targetUrl = validation.normalizedUrl;
  const startTime = Date.now();

  try {
    const probeResult = await performConnectionProbe(targetUrl, 5000);
    const latencyMs = Date.now() - startTime;
    res.json({
      success: true,
      data: {
        status: probeResult.connected ? 'Connected' : 'Unreachable',
        latencyMs,
        statusCode: probeResult.statusCode,
        message: probeResult.message
      }
    });
  } catch (err) {
    const latencyMs = Date.now() - startTime;
    res.json({
      success: true,
      data: {
        status: 'Unreachable',
        latencyMs,
        message: err.message || 'Connection timed out or failed'
      }
    });
  }
});

/**
 * Helper to probe an HTTP/HTTPS endpoint safely with timeout.
 */
function performConnectionProbe(endpointUrl, timeoutMs) {
  return new Promise((resolve, reject) => {
    let clientModule = https;
    let urlObj;
    try {
      urlObj = new URL(endpointUrl);
      if (urlObj.protocol === 'http:' || urlObj.protocol === 'ws:') {
        clientModule = http;
      }
    } catch (e) {
      return reject(new Error('Invalid URL'));
    }

    const options = {
      method: 'GET',
      hostname: urlObj.hostname,
      port: urlObj.port || (clientModule === https ? 443 : 80),
      path: urlObj.pathname || '/',
      timeout: timeoutMs,
      headers: {
        'User-Agent': 'FlinsMusic-Server-Probe/1.0',
        'Accept': '*/*'
      }
    };

    const req = clientModule.request(options, (response) => {
      // 2xx, 3xx, or 404 (server responded) proves server is reachable and active
      const reachable = response.statusCode >= 200 && response.statusCode < 500;
      resolve({
        connected: reachable,
        statusCode: response.statusCode,
        message: `HTTP response ${response.statusCode}`
      });
    });

    req.on('timeout', () => {
      req.destroy();
      reject(new Error('Connection timed out after ' + timeoutMs + 'ms'));
    });

    req.on('error', (err) => {
      reject(err);
    });

    req.end();
  });
}

module.exports = router;
