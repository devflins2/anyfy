const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '../../data');
const DATA_FILE = path.join(DATA_DIR, 'servers.json');

const DEFAULT_SERVERS = [
  {
    id: 'srv_render_prod',
    name: 'Render Production Cluster',
    url: 'https://socketformusic.onrender.com/',
    isEnabled: true,
    isActive: true,
    createdAt: 1700000000000,
    updatedAt: 1700000000000
  }
];

class ServerStore {
  constructor() {
    this.servers = [];
    this.init();
  }

  init() {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }
      if (fs.existsSync(DATA_FILE)) {
        const raw = fs.readFileSync(DATA_FILE, 'utf8');
        this.servers = JSON.parse(raw);
        if (!Array.isArray(this.servers) || this.servers.length === 0) {
          this.servers = [...DEFAULT_SERVERS];
          this.save();
        }
      } else {
        this.servers = [...DEFAULT_SERVERS];
        this.save();
      }
    } catch (err) {
      console.warn('Could not read servers.json, falling back to defaults:', err.message);
      this.servers = [...DEFAULT_SERVERS];
    }
  }

  save() {
    try {
      if (!fs.existsSync(DATA_DIR)) {
        fs.mkdirSync(DATA_DIR, { recursive: true });
      }
      const tmpFile = `${DATA_FILE}.tmp`;
      fs.writeFileSync(tmpFile, JSON.stringify(this.servers, null, 2), 'utf8');
      fs.renameSync(tmpFile, DATA_FILE);
    } catch (err) {
      console.error('Failed to save servers.json:', err.message);
    }
  }

  getAll() {
    return [...this.servers];
  }

  getActive() {
    const active = this.servers.find(s => s.isActive && s.isEnabled);
    return active || this.servers.find(s => s.isEnabled) || this.servers[0] || DEFAULT_SERVERS[0];
  }

  getPublicConfig() {
    const active = this.getActive();
    return {
      activeServer: {
        id: active.id,
        name: active.name,
        url: active.url
      },
      servers: this.servers
        .filter(s => s.isEnabled)
        .map(s => ({
          id: s.id,
          name: s.name,
          url: s.url,
          isActive: s.id === active.id
        }))
    };
  }

  getById(id) {
    return this.servers.find(s => s.id === id) || null;
  }

  addServer({ name, url, isEnabled = true, isActive = false }) {
    const newId = `srv_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
    const now = Date.now();

    if (isActive) {
      // Deactivate all others
      this.servers.forEach(s => { s.isActive = false; });
    }

    const newServer = {
      id: newId,
      name: name.trim(),
      url: url.trim(),
      isEnabled: Boolean(isEnabled),
      isActive: Boolean(isActive),
      createdAt: now,
      updatedAt: now
    };

    // If this is the only server, it must be active and enabled
    if (this.servers.length === 0) {
      newServer.isEnabled = true;
      newServer.isActive = true;
    }

    this.servers.push(newServer);
    this.save();
    return newServer;
  }

  updateServer(id, updates) {
    const index = this.servers.findIndex(s => s.id === id);
    if (index === -1) return null;

    const existing = this.servers[index];
    const now = Date.now();

    if (updates.name !== undefined) existing.name = updates.name.trim();
    if (updates.url !== undefined) existing.url = updates.url.trim();
    if (updates.isEnabled !== undefined) {
      existing.isEnabled = Boolean(updates.isEnabled);
      // Cannot disable the currently active server without another active server
      if (!existing.isEnabled && existing.isActive) {
        existing.isActive = false;
        const candidate = this.servers.find(s => s.id !== id && s.isEnabled);
        if (candidate) {
          candidate.isActive = true;
        }
      }
    }

    existing.updatedAt = now;
    this.save();
    return existing;
  }

  setActive(id) {
    const target = this.servers.find(s => s.id === id);
    if (!target) return { success: false, error: 'Server not found' };

    if (!target.isEnabled) {
      return { success: false, error: 'Cannot activate a disabled server. Enable it first.' };
    }

    this.servers.forEach(s => {
      s.isActive = (s.id === id);
    });

    this.save();
    return { success: true, activeServer: target };
  }

  deleteServer(id) {
    const target = this.servers.find(s => s.id === id);
    if (!target) return { success: false, error: 'Server not found' };

    if (target.isActive && this.servers.length > 1) {
      return { success: false, error: 'Cannot delete the active server. Set another server as active first.' };
    }

    if (this.servers.length <= 1) {
      return { success: false, error: 'Cannot delete the only configured server.' };
    }

    this.servers = this.servers.filter(s => s.id !== id);
    this.save();
    return { success: true };
  }
}

module.exports = new ServerStore();
