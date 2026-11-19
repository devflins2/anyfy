# FlinsMusic Admin & Socket.IO Management Backend

A secure Node.js/Express service providing dynamic Socket.IO cluster configuration and administration for the FlinsMusic Android app.

## Security Overview

The backend uses **Firebase Admin SDK** for authentication and authorization:
1. Every administrative request must provide a valid Firebase ID Token in the `Authorization: Bearer <token>` or `X-Admin-Token` header.
2. The Firebase Admin SDK cryptographically validates:
   - **Signature**: Verified using Google's public certificates.
   - **Audience & Project ID**: Must match `flinsmusic-d4187` (configured via `FIREBASE_PROJECT_ID`).
   - **Issuer**: Must match `https://securetoken.google.com/flinsmusic-d4187`.
   - **Expiration**: Token must not be expired.
3. **Email Verification**: Only tokens where `email_verified === true` are authorized.
4. **Allowlist Authorization**: The verified email must be present in the server-side `ADMIN_EMAILS` allowlist.
5. **SSRF Protection**: All submitted Socket.IO server URLs are validated against loopback, RFC 1918 private subnets, cloud metadata IPs (`169.254.169.254`), and non-HTTPS/WSS protocols.

---

## Environment Variables

| Variable | Required? | Default | Description |
|---|---|---|---|
| `NODE_ENV` | **Required** | `development` | Set to `production` in production environments to enforce strict HTTPS and SSRF rules. |
| `PORT` | Optional | `8080` | Port on which the Express server listens. |
| `FIREBASE_PROJECT_ID` | Optional | `flinsmusic-d4187` | The Firebase project ID matching `app/google-services.json`. |
| `ADMIN_EMAILS` | **Required** | `admin@flinsmusic.com,gaurav@flinsmusic.com` | Comma-separated list of authorized administrator email addresses. |
| `FIREBASE_SERVICE_ACCOUNT` | **Recommended in Production** | `null` | JSON string of the Firebase service account private key (set via host secret/env). |
| `GOOGLE_APPLICATION_CREDENTIALS` | Optional | `null` | File path to service account JSON (alternative for Google Cloud Run / Compute). |
| `DATA_FILE_PATH` | Optional | `data/servers.json` | Path to persistent JSON store for Socket.IO servers. |

---

## Service Account Setup (Firebase Console)

> [!CAUTION]
> **NEVER commit service account JSON files, private keys, or credentials to Git.**
> They are strictly ignored by `.gitignore`.

To generate a Firebase Admin service account key:
1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Select the **flinsmusic-d4187** project.
3. Click the gear icon (**Project Settings**) in the sidebar -> **Service accounts** tab.
4. Click **Generate new private key** and confirm.
5. In your deployment hosting platform (Render, Railway, Heroku, Cloud Run):
   - Add an environment variable named `FIREBASE_SERVICE_ACCOUNT`.
   - Paste the entire raw JSON string as the value.

---

## Deployment Steps

### Render / Railway / Cloud Run
1. Connect repository or deploy from the `backend/` directory.
2. Build Command: `npm ci --omit=dev`
3. Start Command: `npm start`
4. Set Environment Variables:
   ```env
   NODE_ENV=production
   PORT=8080
   FIREBASE_PROJECT_ID=flinsmusic-d4187
   ADMIN_EMAILS=your-admin-email@domain.com,developer@flinsmusic.com
   FIREBASE_SERVICE_ACCOUNT={"type":"service_account","project_id":"flinsmusic-d4187",...}
   ```
5. Attach a persistent disk mount to `/app/data` (or directory containing `data/servers.json`) to persist server configurations across deployments.

---

## Running Locally

```bash
cd backend
npm install
npm test
npm start
```
