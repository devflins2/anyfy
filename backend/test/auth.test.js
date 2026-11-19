const test = require('node:test');
const assert = require('node:assert');
const { requireAdmin, EXPECTED_PROJECT_ID } = require('../src/security/auth');
const { setCustomVerifier, resetCustomVerifier } = require('../src/security/firebaseAdmin');

test.beforeEach(() => {
  resetCustomVerifier();
});

test.afterEach(() => {
  resetCustomVerifier();
});

test('Auth - Rejects Missing Authorization Header', async () => {
  const req = { headers: {} };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /Missing Authorization header/i);
});

test('Auth - Rejects Empty Bearer Token', async () => {
  const req = {
    headers: { authorization: 'Bearer   ' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /Invalid or empty Bearer token/i);
});

test('Auth - Rejects Forged Token (Signature Verification Failure)', async () => {
  setCustomVerifier(async (token) => {
    const err = new Error('Firebase ID token has invalid signature.');
    err.code = 'auth/invalid-id-token';
    throw err;
  });

  const req = {
    headers: { authorization: 'Bearer forged.tampered.token' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called for forged token');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /Malformed or invalid authentication token/i);
});

test('Auth - Rejects Expired Token', async () => {
  setCustomVerifier(async (token) => {
    const err = new Error('Firebase ID token has expired.');
    err.code = 'auth/id-token-expired';
    throw err;
  });

  const req = {
    headers: { authorization: 'Bearer expired.firebase.token' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called for expired token');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /expired/i);
});

test('Auth - Rejects Wrong Audience / Project ID', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'attacker_123',
    email: 'admin@flinsmusic.com',
    email_verified: true,
    aud: 'malicious-other-project', // Wrong audience!
    iss: `https://securetoken.google.com/malicious-other-project`,
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { authorization: 'Bearer valid.signature.wrong.aud' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called when aud does not match expected project ID');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /does not match expected project ID/i);
});

test('Auth - Rejects Wrong Issuer', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'attacker_123',
    email: 'admin@flinsmusic.com',
    email_verified: true,
    aud: EXPECTED_PROJECT_ID,
    iss: 'https://attacker-idp.example.com/fake', // Wrong issuer!
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { authorization: 'Bearer token.with.fake.issuer' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called when iss does not match expected issuer');
  });

  assert.strictEqual(statusCode, 401);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /does not match expected issuer/i);
});

test('Auth - Rejects Unverified Email Address', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'user_unverified_email',
    email: 'admin@flinsmusic.com',
    email_verified: false, // NOT verified!
    aud: EXPECTED_PROJECT_ID,
    iss: `https://securetoken.google.com/${EXPECTED_PROJECT_ID}`,
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { authorization: 'Bearer unverified.email.token' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called when email_verified is false');
  });

  assert.strictEqual(statusCode, 403);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /email address must be verified/i);
});

test('Auth - Never Trusts admin:true Claim if Email Is Not in ADMIN_EMAILS', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'unauthorized_user',
    email: 'intruder@randomdomain.com',
    email_verified: true,
    admin: true, // Attacker claims custom admin claim
    role: 'admin',
    aud: EXPECTED_PROJECT_ID,
    iss: `https://securetoken.google.com/${EXPECTED_PROJECT_ID}`,
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { authorization: 'Bearer token.with.fake.admin.claim' }
  };
  let statusCode = null;
  let responseData = null;
  const res = {
    status(code) {
      statusCode = code;
      return {
        json(data) { responseData = data; }
      };
    }
  };

  await requireAdmin(req, res, () => {
    assert.fail('next() should not be called when email is not in ADMIN_EMAILS');
  });

  assert.strictEqual(statusCode, 403);
  assert.strictEqual(responseData.success, false);
  assert.match(responseData.error, /not authorized as an administrator/i);
});

test('Auth - Authorizes Valid Firebase ID Token for Verified Admin', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'firebase_admin_uid_777',
    email: 'admin@flinsmusic.com',
    email_verified: true,
    aud: EXPECTED_PROJECT_ID,
    iss: `https://securetoken.google.com/${EXPECTED_PROJECT_ID}`,
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { authorization: 'Bearer valid.firebase.id.token' }
  };
  let nextCalled = false;
  const res = {
    status(code) {
      assert.fail(`res.status(${code}) should not be called for authorized admin`);
    }
  };

  await requireAdmin(req, res, () => {
    nextCalled = true;
  });

  assert.strictEqual(nextCalled, true);
  assert.strictEqual(req.user.uid, 'firebase_admin_uid_777');
  assert.strictEqual(req.user.email, 'admin@flinsmusic.com');
  assert.strictEqual(req.user.emailVerified, true);
  assert.strictEqual(req.user.isAdmin, true);
});

test('Auth - Authorizes Alternative X-Admin-Token Header', async () => {
  setCustomVerifier(async (token) => ({
    uid: 'firebase_admin_uid_888',
    email: 'gaurav@flinsmusic.com',
    email_verified: true,
    aud: EXPECTED_PROJECT_ID,
    iss: `https://securetoken.google.com/${EXPECTED_PROJECT_ID}`,
    exp: Math.floor(Date.now() / 1000) + 3600
  }));

  const req = {
    headers: { 'x-admin-token': 'valid.firebase.id.token' }
  };
  let nextCalled = false;
  const res = {
    status(code) {
      assert.fail(`res.status(${code}) should not be called for authorized admin`);
    }
  };

  await requireAdmin(req, res, () => {
    nextCalled = true;
  });

  assert.strictEqual(nextCalled, true);
  assert.strictEqual(req.user.email, 'gaurav@flinsmusic.com');
  assert.strictEqual(req.user.isAdmin, true);
});
