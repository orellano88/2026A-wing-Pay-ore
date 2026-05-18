[app]
title = WingPay Enterprise Sync
package.name = wingpay
package.domain = com.inversioneswing
source.dir = .
source.include_exts = py,png,jpg,kv,atlas
version = 66.0-STARK-B
requirements = python3,kivy,plyer,android,requests,certifi
orientation = portrait
osx.python_version = 3
osx.kivy_version = 1.9.1
fullscreen = 0
android.api = 34
android.minapi = 21
android.sdk = 34
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a
android.allow_backup = True



# --- CONFIGURACION FINAL WING ---
android.permissions = INTERNET, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS, VIBRATE, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
android.keystore = wingpay.keystore
android.keystore_password = 123456
android.keyalias = wing_alias
android.keyalias_password = 123456
