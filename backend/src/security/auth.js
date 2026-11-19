/**
 * Authentication and Admin Authorization Middleware.
 * Enforces role-based access control using genuine Firebase ID token verification.
 * Only authenticated administrators with verified emails matching ADMIN_EMAILS
 * are granted access to administrative endpoints.
 */

const { verifyIdToken, EXPECTED_PROJECT_ID } = require('./firebaseAdmin');

/**
 * Returns the configured list of authorized admin emails.
 * @returns {string[]}
 */
function getAdminEmails() {
  return (process.env.ADMIN_EMAILS || 'admin@flinsmusic.com,gaurav@flinsmusic.com')
    .split(',')
    .map(e => e.trim().toLowerCase())
    .filter(Boolean);
}

/**
 * Middleware to require and verify Admin credentials.
 * Fails closed on any verification error or unauthorized identity.
 */
async function requireAdmin(req, res, next) {
  const authHeader = req.headers['authorization'] || req.headers['x-admin-token'];
  if (!authHeader) {
    return res.status(401).json({
      success: false,
      error: 'Authentication required. Missing Authorization header.'
    });
  }

  let token = authHeader;
  if (token.startsWith('Bearer ') || token.startsWith('bearer ')) {
    token = token.slice(7).trim();
  }

  if (!token) {
    return res.status(401).json({
      success: false,
      error: 'Invalid or empty Bearer token.'
    });
  }

  let decodedToken;
  try {
    decodedToken = await verifyIdToken(token);
  } catch (err) {
    const code = err.code || '';
    if (code === 'auth/id-token-expired') {
      return res.status(401).json({
        success: false,
        error: 'Authentication token has expired. Please sign in again.'
      });
    }
    if (code === 'auth/argument-error' || code === 'auth/invalid-id-token') {
      return res.status(401).json({
        success: false,
        error: 'Malformed or invalid authentication token.'
      });
    }
    return res.status(401).json({
      success: false,
      error: `Authentication token verification failed: ${err.message || 'Invalid token'}`
    });
  }

  if (!decodedToken || typeof decodedToken !== 'object') {
    return res.status(401).json({
      success: false,
      error: 'Malformed or invalid authentication token.'
    });
  }

  // 1. Audience / Project ID validation: MUST match flinsmusic-d4187
  if (decodedToken.aud !== EXPECTED_PROJECT_ID) {
    return res.status(401).json({
      success: false,
      error: `Token audience '${decodedToken.aud}' does not match expected project ID '${EXPECTED_PROJECT_ID}'.`
    });
  }

  // 2. Issuer validation: MUST match https://securetoken.google.com/<project-id>
  const expectedIssuer = `https://securetoken.google.com/${EXPECTED_PROJECT_ID}`;
  if (decodedToken.iss !== expectedIssuer) {
    return res.status(401).json({
      success: false,
      error: `Token issuer '${decodedToken.iss}' does not match expected issuer '${expectedIssuer}'.`
    });
  }

  // 3. Expiration validation: exp must be in the future
  if (decodedToken.exp && typeof decodedToken.exp === 'number') {
    const nowSecs = Math.floor(Date.now() / 1000);
    if (decodedToken.exp < nowSecs) {
      return res.status(401).json({
        success: false,
        error: 'Authentication token has expired. Please sign in again.'
      });
    }
  }

  // 4. Email verification requirement: MUST be true
  // Never trust an unverified email claim or unverified admin: true / role: admin
  if (decodedToken.email_verified !== true) {
    return res.status(403).json({
      success: false,
      error: 'Forbidden. Administrator email address must be verified.'
    });
  }

  const verifiedEmail = (decodedToken.email || '').toLowerCase().trim();
  if (!verifiedEmail) {
    return res.status(403).json({
      success: false,
      error: 'Forbidden. Token does not contain an email address.'
    });
  }

  // 5. Authorized email check: Must match ADMIN_EMAILS allowlist
  const adminEmails = getAdminEmails();
  const isAuthorizedAdmin = adminEmails.includes(verifiedEmail);

  if (!isAuthorizedAdmin) {
    return res.status(403).json({
      success: false,
      error: 'Forbidden. User is not authorized as an administrator.'
    });
  }

  // Attach verified user identity to request object
  req.user = {
    uid: decodedToken.uid || decodedToken.sub || 'admin_user',
    email: verifiedEmail,
    emailVerified: true,
    isAdmin: true
  };

  next();
}

module.exports = {
  requireAdmin,
  getAdminEmails,
  EXPECTED_PROJECT_ID
};
