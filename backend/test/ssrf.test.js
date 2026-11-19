const test = require('node:test');
const assert = require('node:assert');
const dns = require('dns').promises;
const { validateServerUrl, isPrivateIpV4 } = require('../src/security/ssrf');

// Mock DNS lookup so unit tests run deterministically offline
dns.lookup = async (hostname) => {
  if (hostname === 'socketformusic.onrender.com') {
    return [{ address: '104.21.58.12', family: 4 }];
  }
  const err = new Error(`Could not resolve hostname '${hostname}': getaddrinfo ENOTFOUND ${hostname}`);
  err.code = 'ENOTFOUND';
  throw err;
};

test('SSRF Protection - Blocks Private IPv4 Ranges', () => {
  assert.strictEqual(isPrivateIpV4('127.0.0.1'), true, 'Loopback must be blocked');
  assert.strictEqual(isPrivateIpV4('10.0.0.5'), true, '10.0.0.0/8 must be blocked');
  assert.strictEqual(isPrivateIpV4('172.16.0.1'), true, '172.16.0.0/12 must be blocked');
  assert.strictEqual(isPrivateIpV4('192.168.1.100'), true, '192.168.0.0/16 must be blocked');
  assert.strictEqual(isPrivateIpV4('169.254.169.254'), true, 'AWS metadata must be blocked');
  assert.strictEqual(isPrivateIpV4('8.8.8.8'), false, 'Public IP must be allowed');
  assert.strictEqual(isPrivateIpV4('1.1.1.1'), false, 'Public IP must be allowed');
});

test('SSRF Protection - Rejects Insecure Protocols in Production', async () => {
  const fileResult = await validateServerUrl('file:///etc/passwd', false);
  assert.strictEqual(fileResult.isValid, false);
  assert.match(fileResult.error, /Insecure or unsupported protocol/);

  const ftpResult = await validateServerUrl('ftp://ftp.example.com', false);
  assert.strictEqual(ftpResult.isValid, false);

  const httpResult = await validateServerUrl('http://insecure.example.com', false);
  assert.strictEqual(httpResult.isValid, false);
});

test('SSRF Protection - Blocks Localhost and Internal Hostnames', async () => {
  const localhostResult = await validateServerUrl('https://localhost:8080', false);
  assert.strictEqual(localhostResult.isValid, false);
  assert.match(localhostResult.error, /Localhost and internal hostnames/);

  const directLoopback = await validateServerUrl('https://127.0.0.1:443', false);
  assert.strictEqual(directLoopback.isValid, false);

  const internalDomain = await validateServerUrl('https://service.internal', false);
  assert.strictEqual(internalDomain.isValid, false);
});

test('SSRF Protection - Accepts Valid Public HTTPS Endpoint', async () => {
  const result = await validateServerUrl('https://socketformusic.onrender.com', false);
  assert.strictEqual(result.isValid, true);
  assert.strictEqual(result.normalizedUrl, 'https://socketformusic.onrender.com/');
});
