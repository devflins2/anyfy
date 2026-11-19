const dns = require('dns').promises;
const net = require('net');

/**
 * Checks whether an IPv4 address belongs to a private, loopback, or reserved range.
 * @param {string} ip
 * @returns {boolean}
 */
function isPrivateIpV4(ip) {
  const parts = ip.split('.').map(Number);
  if (parts.length !== 4 || parts.some(p => isNaN(p) || p < 0 || p > 255)) {
    return true; // Malformed IP is treated as unsafe
  }

  // 0.0.0.0/8 (Current network)
  if (parts[0] === 0) return true;

  // 10.0.0.0/8 (Private network)
  if (parts[0] === 10) return true;

  // 127.0.0.0/8 (Loopback)
  if (parts[0] === 127) return true;

  // 100.64.0.0/10 (Shared address space / Carrier-grade NAT)
  if (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127) return true;

  // 169.254.0.0/16 (Link-local / Cloud Metadata e.g. 169.254.169.254)
  if (parts[0] === 169 && parts[1] === 254) return true;

  // 172.16.0.0/12 (Private network)
  if (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return true;

  // 192.0.0.0/24 (IETF Protocol Assignments)
  if (parts[0] === 192 && parts[1] === 0 && parts[2] === 0) return true;

  // 192.0.2.0/24 (TEST-NET-1)
  if (parts[0] === 192 && parts[1] === 0 && parts[2] === 2) return true;

  // 192.168.0.0/16 (Private network)
  if (parts[0] === 192 && parts[1] === 168) return true;

  // 198.51.100.0/24 (TEST-NET-2)
  if (parts[0] === 198 && parts[1] === 51 && parts[2] === 100) return true;

  // 203.0.113.0/24 (TEST-NET-3)
  if (parts[0] === 203 && parts[1] === 0 && parts[2] === 113) return true;

  // 224.0.0.0/4 (Multicast)
  if (parts[0] >= 224 && parts[0] <= 239) return true;

  // 240.0.0.0/4 (Reserved for future use)
  if (parts[0] >= 240) return true;

  // 255.255.255.255 (Broadcast)
  if (parts[0] === 255 && parts[1] === 255 && parts[2] === 255 && parts[3] === 255) return true;

  return false;
}

/**
 * Checks whether an IPv6 address is private, loopback, or reserved.
 * @param {string} ip
 * @returns {boolean}
 */
function isPrivateIpV6(ip) {
  const normalized = ip.toLowerCase();
  // Loopback
  if (normalized === '::1' || normalized === '0:0:0:0:0:0:0:1') return true;
  // Unspecified
  if (normalized === '::' || normalized === '0:0:0:0:0:0:0:0') return true;
  // Unique local addresses (fc00::/7)
  if (normalized.startsWith('fc') || normalized.startsWith('fd')) return true;
  // Link-local addresses (fe80::/10)
  if (normalized.startsWith('fe8') || normalized.startsWith('fe9') || normalized.startsWith('fea') || normalized.startsWith('feb')) return true;
  // IPv4-mapped IPv6 (::ffff:127.0.0.1 etc.)
  if (normalized.includes('::ffff:')) {
    const v4Part = normalized.split('::ffff:')[1];
    if (net.isIPv4(v4Part)) {
      return isPrivateIpV4(v4Part);
    }
  }
  return false;
}

/**
 * Validates a Socket.IO server endpoint URL against SSRF and protocol misuse.
 * @param {string} rawUrl
 * @param {boolean} allowDevLocalhost Optional flag for local test environments
 * @returns {Promise<{ isValid: boolean, error?: string, normalizedUrl?: string }>}
 */
async function validateServerUrl(rawUrl, allowDevLocalhost = false) {
  if (!rawUrl || typeof rawUrl !== 'string') {
    return { isValid: false, error: 'Server URL cannot be empty' };
  }

  const trimmed = rawUrl.trim();
  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch (err) {
    return { isValid: false, error: 'Malformed URL format' };
  }

  // Enforce secure protocols
  const validProtocols = ['https:', 'wss:'];
  if (allowDevLocalhost) {
    validProtocols.push('http:', 'ws:');
  }

  if (!validProtocols.includes(parsed.protocol)) {
    return {
      isValid: false,
      error: `Insecure or unsupported protocol '${parsed.protocol}'. Production endpoints must use HTTPS or WSS.`
    };
  }

  const hostname = parsed.hostname.toLowerCase();

  // Block localhost and common loopback aliases in production
  if (!allowDevLocalhost) {
    const loopbackNames = ['localhost', 'local', 'broadcasthost', 'ip6-localhost', 'ip6-loopback'];
    if (loopbackNames.includes(hostname) || hostname.endsWith('.local') || hostname.endsWith('.internal')) {
      return { isValid: false, error: 'Localhost and internal hostnames are prohibited for production endpoints' };
    }
  }

  // Check if hostname is already a direct IP
  const ipType = net.isIP(hostname);
  if (ipType === 4) {
    if (!allowDevLocalhost && isPrivateIpV4(hostname)) {
      return { isValid: false, error: 'Target IP is in a private, loopback, or reserved address range (SSRF protection)' };
    }
  } else if (ipType === 6) {
    if (!allowDevLocalhost && isPrivateIpV6(hostname)) {
      return { isValid: false, error: 'Target IPv6 is in a private or loopback address range (SSRF protection)' };
    }
  } else {
    // Resolve DNS to verify the domain does not resolve to private/internal IPs (DNS rebinding / SSRF)
    try {
      const addresses = await dns.lookup(hostname, { all: true });
      for (const addr of addresses) {
        if (!allowDevLocalhost) {
          if (addr.family === 4 && isPrivateIpV4(addr.address)) {
            return {
              isValid: false,
              error: `Hostname '${hostname}' resolves to private address ${addr.address} (SSRF protection)`
            };
          }
          if (addr.family === 6 && isPrivateIpV6(addr.address)) {
            return {
              isValid: false,
              error: `Hostname '${hostname}' resolves to private IPv6 address ${addr.address} (SSRF protection)`
            };
          }
        }
      }
    } catch (dnsErr) {
      // If DNS lookup fails entirely
      return { isValid: false, error: `Could not resolve hostname '${hostname}': ${dnsErr.message}` };
    }
  }

  // Ensure path is standardized
  let normalized = parsed.origin + (parsed.pathname === '/' ? '/' : parsed.pathname);
  if (!normalized.endsWith('/')) {
    normalized += '/';
  }

  return { isValid: true, normalizedUrl: normalized };
}

module.exports = {
  isPrivateIpV4,
  isPrivateIpV6,
  validateServerUrl
};
