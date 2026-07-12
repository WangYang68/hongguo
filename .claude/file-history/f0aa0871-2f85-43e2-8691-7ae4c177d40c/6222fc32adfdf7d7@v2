# MagicPiano - API Integration Implementation Plan

## Context
The MagicPiano Android app currently only supports local MIDI playback via accessibility service. The user wants to integrate cloud API services (authentication, music list, VIP verification, profile management) while maintaining existing MIDI playback functionality. The API server is at `http://39.107.213.164:8090`.

## Architecture Overview

### New Files to Create
```
app/src/main/java/com/example/magicpinao/
├── core/
│   ├── network/
│   │   ├── ApiClient.kt          # OkHttp singleton + request helpers
│   │   ├── ApiModels.kt          # Request/Response data classes
│   │   └── ApiRepository.kt      # All API call functions (suspend)
│   ├── auth/
│   │   └── AuthManager.kt        # Token/user state via SharedPreferences
│   └── utils/
│       └── EmailSender.kt        # SMTP email verification code sender
├── ui/
│   └── screens/
│       ├── auth/
│       │   ├── LoginScreen.kt    # Beautiful login with gradient theme
│       │   └── RegisterScreen.kt # Register with email verification flow
│       └── profile/
│           └── ProfileScreen.kt  # User profile, avatar, VIP, logout
```

### Files to Modify
```
app/src/main/java/com/example/magicpinao/
├── MainActivity.kt               # Add login/register/profile routes + auth gate
├── ui/viewmodel/MainViewModel.kt # Add cloud music, auth state, profile functions
├── ui/screens/home/HomeScreen.kt # Add cloud music tab, VIP check before play
├── build.gradle.kts              # Add OkHttp + JavaMail dependencies
├── gradle/libs.versions.toml     # Add version entries
└── AndroidManifest.xml           # Add INTERNET permission
```

## Step-by-Step Implementation

### Step 1: Add Dependencies
**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`

Add to version catalog:
- `okhttp = "4.12.0"` → OkHttp BOM + client
- `javax-mail = "1.6.2"` → JavaMail for SMTP email sending

Add to app build.gradle.kts:
```
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.sun.mail:android-mail:1.6.7")
implementation("com.sun.mail:android-activation:1.6.7")
```

**Why OkHttp over Retrofit:** The project is simple enough that raw OkHttp + Gson is cleaner. Fewer annotations, less boilerplate. Existing pattern uses Gson already.

### Step 2: Add INTERNET Permission
**File:** `AndroidManifest.xml`

Add `<uses-permission android:name="android.permission.INTERNET" />` before the application tag.

### Step 3: Create Network Layer
**Files:** `core/network/ApiClient.kt`, `core/network/ApiModels.kt`, `core/network/ApiRepository.kt`

#### ApiClient.kt
- Singleton OkHttpClient with 10s connect/read/write timeout
- Helper `doGet(url, params?, token?)` → JSON string
- Helper `doPost(url, bodyJson, token?)` → JSON string  
- Helper `doPostMultipart(url, file, token?)` → JSON string
- All requests add `Client: midi_client` header automatically

#### ApiModels.kt
Data classes mirroring API responses:
- `LoginRequest(account, password)`, `LoginResponse(user_id, account, nickname, token, avatar, push_code, money, status, created_at, pushUser)`
- `RegisterRequest(account, nickname, password, code?)` 
- `ResetPwdRequest(account, password)`
- `MusicItem(id, name, name_en, name_ft, singer, path, category, created_at)`
- `MusicListResponse(total, data: List<MusicItem>)`
- `ApiResponse<T>(code, message, data)` — generic wrapper
- `UserInfo(id, account, nickname, avatar, push_code, money, status, created_at, pushUser)`
- `VipRecord(id, code, vip_day, expire_at, created_at, user_id)`

#### ApiRepository.kt
Suspend functions wrapping all API calls (using `withContext(Dispatchers.IO)`):
- `login(account, password): Result<LoginResponse>`
- `register(account, nickname, password, code?): Result<String>`
- `resetPassword(account, password): Result<String>`
- `logout(token): Result<String>`
- `checkVipExpire(token): Result<Boolean>`
- `getMusicList(token, page, size, search?): Result<MusicListResponse>`
- `addRecentPlay(token, musicId): Result<String>`
- `uploadAvatar(token, filePath): Result<String>`
- `getUserList(token, page, size): Result<...>`
- `activateVip(token, code): Result<String>`
- `getVipRecords(token, page, size): Result<...>`
- `collectMusic(token, musicId): Result<String>`
- `getCollectList(token, page, size): Result<...>`
- `getRecentPlayList(token, page, size): Result<...>`

### Step 4: Create Auth Manager
**File:** `core/auth/AuthManager.kt`

Singleton object using SharedPreferences:
- `token: String?` — current auth token
- `userInfo: LoginResponse?` — cached user info
- `isLoggedIn: Boolean`
- `saveLogin(response)` — persist token + user
- `clearLogin()` — remove on logout
- `updateAvatar(url)` — update cached avatar
- `updateVipStatus(status)` — update VIP status

### Step 5: Create Email Sender
**File:** `core/utils/EmailSender.kt`

Uses JavaMail SMTP to send verification codes:
- Sender: `magicpianoplayer@foxmail.com`
- SMTP host: `smtp.qq.com`, port 465 (SSL)
- Generates 6-digit random code (100000-999999)
- HTML email with styled verification code
- Runs on Dispatchers.IO (non-blocking)
- Returns the generated code for local verification

### Step 6: Create Auth Screens
**Files:** `ui/screens/auth/LoginScreen.kt`, `ui/screens/auth/RegisterScreen.kt`

#### LoginScreen
- Dark gradient background matching app theme
- App logo/title at top ("🎹 Magic Piano")
- Email input field with icon and validation
- Password field with visibility toggle
- "Login" button with loading state (gradient purple)
- Link to register screen at bottom
- On success: save auth state → navigate to home

#### RegisterScreen
- Same dark gradient theme
- Email field → on focus loss, triggers email verification code send
- Verification code field (6-digit, auto-focused after send)
- Nickname field (2-20 chars)
- Password field (≥6 chars, with strength indicator)
- Invite code field (optional)
- Register button with validation
- On success: navigate back to login with success message
- Progress indicator during email send

### Step 7: Update Home Screen
**File:** `ui/screens/home/HomeScreen.kt`

- Add bottom navigation tabs: "本地音乐" / "云音乐" / "我的"
- **本地音乐 tab:** Existing local MIDI file list
- **云音乐 tab:** Music list from API with pagination, search
- **我的 tab:** Navigate to profile screen
- Before playing cloud music: call VIP check API
  - If VIP valid OR within 10-min trial → play
  - If VIP expired → show dialog prompting VIP activation
- Top bar: user avatar (navigates to profile) + settings
- Playback controls: enhanced with cloud music info

### Step 8: Create Profile Screen
**File:** `ui/screens/profile/ProfileScreen.kt`

Beautiful profile page with sections:
- **Header:** Large avatar (clickable to change), nickname, account, VIP badge
- **VIP Status Card:** Current VIP status, days remaining, activate button
- **Activation History:** List of past VIP activations
- **Account Actions:**
  - Change avatar (select from gallery, upload to API)
  - Reset password (email verification + new password)
- **Logout Button:** Red, bottom of screen, confirmation dialog

### Step 9: Update Navigation
**File:** `MainActivity.kt`

New navigation graph:
```
login ──→ register ──→ login (back)
login ──→ home (on success)
home ──→ profile
home ──→ calibration
profile ──→ back to home
```

Auth gate: On app start, check `AuthManager.isLoggedIn`. If not logged in → login screen. If logged in → home screen.

Add INTERNET permission to manifest.

### Step 10: Update MainViewModel
**File:** `ui/viewmodel/MainViewModel.kt`

Add new state flows and functions:
- Cloud music list state
- Auth state (isLoggedIn, userInfo)
- Login/register/logout functions
- Cloud music list loading with pagination
- VIP check integration before play
- Avatar upload
- Password reset

### Step 11: Build & Test
```bash
cd e:/PythonProject/AutoPlayer_android/magicpinao
./gradlew assembleDebug
# Install on emulator: adb install app/build/outputs/apk/debug/app-debug.apk
```

## Verification

1. **Build:** `./gradlew assembleDebug` compiles without errors
2. **Login flow:** Launch app → see login screen → enter credentials → navigate to home
3. **Register flow:** Navigate to register → enter email (verification sent) → complete form → register → back to login
4. **Cloud music:** Home → 云音乐 tab → see music list from API → pagination works
5. **VIP check:** Click cloud music → VIP check API called → play if valid, prompt if expired
6. **Profile:** Navigate to 我的 → see user info → change avatar → activate VIP → logout

## Key Design Decisions

1. **OkHttp over Retrofit:** Simpler for this API surface, less boilerplate, matches project's Gson usage
2. **JavaMail for SMTP:** Required for sending verification emails from Android; runs on background threads
3. **SharedPreferences over DataStore:** Lighter weight for simple token/user storage
4. **Tab-based home:** Keeps local and cloud music in same screen with clean UX
5. **VIP check hybrid logic:** Check API response + client-side 10-min trial from registration time — matches the reference Python implementation
