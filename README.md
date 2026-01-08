# RestoRank Bridge - Android Auto-Print App

An Android bridge app that automatically prints kitchen tickets when new orders arrive via WiFi thermal printers.

## Features

- **Auto-Print** - Background service polls server and prints new orders automatically
- **WiFi/LAN Only** - Uses ESC/POS over TCP (IP:9100) - no Bluetooth
- **Multi-Printer Support** - Add multiple printers (kitchen, bar, etc.)
- **Test Connection** - Verify printer connectivity before saving
- **Test Print** - Send test receipt to verify printer works
- **Boot Auto-Start** - Service starts automatically when phone boots
- **WebView Dashboard** - View RestoRank staff dashboard in the app

## How It Works

1. **Background Service** runs continuously
2. **Polls your server** every 5 seconds for new orders
3. **Auto-prints** to all enabled printers when new order arrives
4. **Notification** shows the service is running

## Setup

### Step 1: Build the APK

**Option A: Codemagic (Recommended)**
1. Push code to GitHub
2. Connect repo to [Codemagic](https://codemagic.io)
3. Download APK from build artifacts

**Option B: Android Studio**
1. Open project in Android Studio
2. Run `./gradlew assembleDebug`
3. Find APK at `app/build/outputs/apk/debug/`

### Step 2: Install on Android Device

1. Transfer APK to your Android phone
2. Enable "Install from unknown sources"
3. Tap APK to install

### Step 3: Configure Printers

1. Open the app
2. Tap **printer icon** (top right)
3. Tap **+** button to add a printer
4. Enter:
   - **Name**: e.g., "Kitchen Printer"
   - **IP Address**: e.g., "192.168.1.100"
   - **Port**: 9100 (default)
5. Tap **Test Connection** to verify
6. Tap **Add** to save

### Step 4: Enable Auto-Print

1. Toggle **Auto-Print Orders** ON
2. The background service will start
3. You'll see a notification "Monitoring for new orders..."

## Requirements

- **Android 7.0+** (API 24)
- **WiFi network** same as thermal printers
- **Thermal printer** with network capability (ESC/POS compatible)

## Supported Printers

Most ESC/POS compatible thermal printers:
- Epson TM series
- Star Micronics
- Bixolon
- Xprinter
- SUNMI built-in printers
- Generic 58mm/80mm thermal printers

## Network Requirements

- Phone and printer must be on **same WiFi/LAN**
- Printer must have **static IP address**
- Port **9100** must be accessible

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection failed | Check IP address and that printer is on same network |
| Test print works but auto-print doesn't | Check auto-print toggle is ON |
| Service stops after a while | Disable battery optimization for the app |
| No notification | Grant notification permission in app settings |

## Project Structure

```
android-bridge/
├── app/src/main/
│   ├── java/ai/restorank/bridge/
│   │   ├── MainActivity.kt          # WebView + status
│   │   ├── PrinterManager.kt        # Printer CRUD + settings
│   │   ├── PrintService.kt          # Background polling + printing
│   │   ├── PrinterSettingsActivity.kt # Printer config UI
│   │   └── BootReceiver.kt          # Auto-start on boot
│   └── res/
│       ├── layout/                   # UI layouts
│       └── drawable/                 # Icons
├── codemagic.yaml                    # CI/CD config
└── README.md
```

## API Integration

The app uses these endpoints:

**Get Unprinted Orders:**
```
GET /api/restaurants/{restaurantId}/orders/pending-print
```

**Mark Order as Printed:**
```
POST /api/orders/{orderId}/printed
```

**Sync Printers from Web App:**
```
GET /api/restaurants/{restaurantId}/printers
```

Configuration is in `PrinterManager.kt`:
- `DEFAULT_RESTAURANT_ID` - Your restaurant ID
- `DEFAULT_SERVER_URL` - Your RestoRank server URL

## License

MIT License - RestoRank.ai
