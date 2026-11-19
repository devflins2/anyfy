const { initializeApp, cert, applicationDefault, getApps } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

const EXPECTED_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || 'flinsmusic-d4187';

let authInstance = null;
let customVerifier = null;

/**
 * Initializes and returns the Firebase Auth instance.
 * Supports:
 * 1. FIREBASE_SERVICE_ACCOUNT (JSON string in environment)
 * 2. GOOGLE_APPLICATION_CREDENTIALS (file path to service account key)
 * 3. Default initialization with projectId (verifies signatures using Google public JWKS)
 */
function getFirebaseAuth() {
  if (authInstance) return authInstance;

  const existingApps = getApps();
  let app;
  if (existingApps.length > 0) {
    app = existingApps[0];
  } else if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      app = initializeApp({
        credential: cert(sa),
        projectId: sa.project_id || EXPECTED_PROJECT_ID
      });
    } catch (err) {
      console.error('[FirebaseAdmin] Failed to parse FIREBASE_SERVICE_ACCOUNT environment variable:', err.message);
      throw new Error('Invalid FIREBASE_SERVICE_ACCOUNT configuration');
    }
  } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    app = initializeApp({
      credential: applicationDefault(),
      projectId: EXPECTED_PROJECT_ID
    });
  } else {
    app = initializeApp({
      projectId: EXPECTED_PROJECT_ID
    });
  }

  authInstance = getAuth(app);
  return authInstance;
}

/**
 * Verifies a Firebase ID token.
 * Validates cryptographic signature, expiry, project ID/audience, and issuer.
 *
 * @param {string} idToken
 * @param {boolean} [checkRevoked=false]
 * @returns {Promise<import('firebase-admin/auth').DecodedIdToken>}
 */
async function verifyIdToken(idToken, checkRevoked = false) {
  if (customVerifier) {
    return customVerifier(idToken, checkRevoked);
  }
  const auth = getFirebaseAuth();
  return auth.verifyIdToken(idToken, checkRevoked);
}

/**
 * Injects a custom token verifier for testing.
 * @param {Function|null} verifier
 */
function setCustomVerifier(verifier) {
  customVerifier = verifier;
}

function resetCustomVerifier() {
  customVerifier = null;
}

module.exports = {
  EXPECTED_PROJECT_ID,
  getFirebaseAuth,
  verifyIdToken,
  setCustomVerifier,
  resetCustomVerifier
};
