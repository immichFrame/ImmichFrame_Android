<div align="center">
  <a href="https://github.com/immichFrame/ImmichFrame_Android">
    <img src="https://github.com/immichFrame/ImmichFrame_Desktop/blob/main/src-tauri/icons/icon.png" alt="Logo" width="200" height="200">
  </a>

  <h3 align="center">ImmichFrame Android</h3>

  <p align="center">
    Android client for <a href="https://github.com/immichFrame/ImmichFrame">ImmichFrame</a>.
    <br />
    <br />
    <a href="https://immichframe.dev">Documentation</a>
  <p>
</div>

## 📄 Documentation
You can find the documentation [here](https://immichframe.dev).

## Opening the app by link
ImmichFrame can be opened with the link `immichframe://open`. If it is already running, the existing instance is brought to the front.

From Home Assistant, using the Android TV Remote integration:
```yaml
action: remote.turn_on
target:
  entity_id: remote.living_room_tv
data:
  activity: immichframe://open
```

From a computer with ADB:
```
adb shell am start -a android.intent.action.VIEW -d immichframe://open
```

# Development
- [Android Studio](https://developer.android.com/studio/) 
