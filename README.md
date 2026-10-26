# TexelCam 📸📐

**TexelCam** is a specialized Android camera application designed for surface photogrammetry, texture capture, and game-ready PBR material creation with guaranteed texel density consistency.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.23-purple.svg)

---

## 🎯 Key Features

1. **Real-Time Texel Density Calculation (px/m)**
   - Computes instantaneous surface texel density using camera intrinsics ($f, W_{\text{sensor}}$) and autofocus lens distance $D$:
     $$\text{TD} = \frac{W_{\text{image}}}{W_{\text{real}}} = \frac{W_{\text{image}} \cdot f}{D \cdot W_{\text{sensor}}}$$
2. **Projected 1-Meter Surface Reticle**
   - Renders a dynamic $1.0\text{ m} \times 1.0\text{ m}$ framing box on screen scaled to the real physical surface at the current distance.
3. **Gyroscopic Perpendicularity HUD (Bullseye Level)**
   - Artificial horizon with dual-ring alignment reticle.
   - Detects when the camera's optical axis is strictly perpendicular ($90^\circ \pm 0.8^\circ$) to the target surface.
   - Dual modes: **WALL** (vertical surfaces) and **FLOOR** (horizontal ground/table).
   - **Haptic Feedback**: Crisp vibration click when perfect perpendicularity is achieved.
4. **Step-Guidance Navigation**
   - Select your target texel density: `512`, `1024`, `2048`, or `4096 px/m`.
   - Real-time guidance prompt: `STEP CLOSER (-15 cm)`, `STEP BACK (+20 cm)`, or `PERFECT DISTANCE` in neon cyan/green.
5. **TextureRipper & PBR Metadata Sidecars**
   - Automatically writes `<filename>.combine.json` and `<filename>.texel.json` sidecar files alongside captured photos.
   - Contains camera intrinsics, physical field of view ($W_{\text{real}}, H_{\text{real}}$), pitch/roll tilt, and suggested 1m crop rectangles.
6. **One-Hand Thumb Ergonomics**
   - All controls (target pills, mode toggle, torch, fine-tune distance override, and 74dp shutter) are clustered in the lower thumb zone for single-handed capture against walls.

---

## 📐 Optical Math

- **Horizontal Field of View:**
  $$\theta_H = 2 \cdot \arctan\left(\frac{W_{\text{sensor}}}{2f}\right)$$
- **Physical Width at Distance $D$:**
  $$W_{\text{real}} = 2 \cdot D \cdot \tan\left(\frac{\theta_H}{2}\right) = D \cdot \frac{W_{\text{sensor}}}{f}$$
- **Required Distance for Target Density $T$:**
  $$D_{\text{target}} = \frac{W_{\text{image}} \cdot f}{T \cdot W_{\text{sensor}}}$$

---

## 🏗️ Project Architecture

```
TexelCam/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/texelcam/app/
│   │   │   ├── MainActivity.kt          # CameraX lifecycle, UI binding & capture
│   │   │   ├── TexelCalculator.kt       # Optical formulas & step guidance
│   │   │   ├── OrientationTracker.kt    # Sensor fusion (pitch/roll/haptics)
│   │   │   ├── TexelHudView.kt          # Canvas HUD (bullseye, 1m reticle)
│   │   │   └── PbrMetadataWriter.kt     # TextureRipper .combine.json sidecar
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/{colors,strings,styles}.xml
│   │       └── drawable/
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Building & Installing

### Prerequisites
- JDK 17+ or 21
- Android SDK (API 34, Build-Tools 34.0.0)

### Build APK
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License

MIT License. Open for photogrammetry, VFX, and game development workflows.
