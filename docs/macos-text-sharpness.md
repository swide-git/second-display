# Sharp text from macOS on an Android display

Why text can look soft when a Mac extends or mirrors to Wormhole Display over AirPlay, and what to do about it. The examples use a 2000×1200 tablet; the same reasoning applies to any panel.

---

## 1. Causes

| Factor | Mechanism | Visual impact |
|---|---|---|
| **Mirroring instead of extending** | macOS mirrors the built-in screen (e.g. 3024×1964) and scales it by a non-integer factor to fit the receiver. | Every 1-pixel stroke and border is interpolated before encoding. |
| **1× rendering** | macOS treats the AirPlay display as a non-Retina canvas and dropped subpixel antialiasing years ago; grayscale smoothing only. | Thin strokes get a soft gray halo on a 1× panel. |
| **Lossy video with 4:2:0 chroma** | AirPlay sends H.264 or HEVC with chroma at half resolution in each direction. | Colored text smears; high-contrast edges show ringing. |
| **Odd resolutions** | A panel size macOS does not recognise (e.g. 2000×1200) may be rendered at a different scale and resampled before encoding. | Extra softness on top of the above. |
| **Small physical size** | 12-14 px fonts on a 10-11″ tablet are tiny. | Strokes are one physical pixel wide and do not survive compression well. |

---

## 2. Fixes, from quickest to best

### Step 1: Extend, do not mirror
In **System Settings → Displays**, select the Wormhole display and set **Use as → Extended display**. This removes the fractional downscale of the built-in screen.

### Step 2: Zoom the apps you read in
In the browser, Slack, VS Code and similar, press **Cmd +** to 110-125 %. Strokes two or three pixels wide survive compression far better than one-pixel strokes.

### Step 3: Pick a resolution macOS knows
Open the receiver's **Preferred Resolution** (dashboard, or long press during a session) and choose a standard size: **1920 × 1200** (16:10, closest to most tablets) or **1920 × 1080** (16:9). Reconnect afterwards. macOS renders these sizes with its own standard modes. A different aspect is letterboxed, not stretched.

Alternatively keep **Native** and, in **System Settings → Displays**, turn on *Show all resolutions* and choose a scaled mode with the panel's aspect.

### Step 4: Retina (HiDPI) text with BetterDisplay (best quality)

1. Install [BetterDisplay](https://github.com/waydabber/BetterDisplay) (the free version is enough):
   ```bash
   brew install --cask betterdisplay
   ```
2. In its menu bar icon select the Wormhole display and enable **HiDPI**.
3. Pick a HiDPI mode around half the advertised size, e.g. **1000 × 600 HiDPI** for a 2000×1200 panel.

macOS then renders at 2× with full vector glyph sharpness and sends the result at the advertised size.

### Step 5: Font smoothing
```bash
# Sharpest, pixel-aligned edges:
defaults -currentHost write -g AppleFontSmoothing -int 0
# Light smoothing:
defaults -currentHost write -g AppleFontSmoothing -int 1
# Back to the macOS default:
defaults -currentHost delete -g AppleFontSmoothing
```
Restart the apps (or log out and back in) to apply.

### Step 6: Colors that compress well
- Avoid pure white on pure black; it rings the most.
- Soft dark themes (background `#1E1E1E` or `#24292E`, text `#E1E4E8`) show fewer artefacts.
- Dark text on a light background is carried mostly by luma, which keeps full resolution, and is usually the sharpest.

### Step 7: Bandwidth
Compression artefacts grow when the link is short of bandwidth. On 2.4 GHz Wi-Fi the encoder lowers quality to keep up; a 5 GHz network, or a lower **Preferred Resolution**, gives it room for sharper frames.
