"""Installe le code VPN natif dans le projet Android genere par Capacitor."""
import os, re, shutil

app = 'android/app'

# 1. Sources Java (plugin, service, MainActivity)
dst = app + '/src/main/java/com/faso/dark'
os.makedirs(dst, exist_ok=True)
for f in os.listdir('native/java/com/faso/dark'):
    shutil.copy('native/java/com/faso/dark/' + f, dst + '/' + f)

# 2. Moteur sing-box
os.makedirs(app + '/libs', exist_ok=True)
shutil.copy('libbox.aar', app + '/libs/libbox.aar')

# 3. minSdk 24 (requis par WireGuard)
v = open('android/variables.gradle').read()
v = re.sub(r'minSdkVersion\s*=\s*\d+', 'minSdkVersion = 24', v)
open('android/variables.gradle', 'w').write(v)

# 4. build.gradle : dependances, ABI, bibliotheques natives
g = open(app + '/build.gradle').read()
g = g.replace('dependencies {',
              "dependencies {\n    implementation fileTree(dir: 'libs', include: ['*.aar'])\n"
              "    implementation 'com.wireguard.android:tunnel:1.0.20230706'", 1)
g = g.replace('defaultConfig {', "defaultConfig {\n        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }", 1)
g = g.replace('android {', "android {\n    packaging { jniLibs { useLegacyPackaging = true } }", 1)
open(app + '/build.gradle', 'w').write(g)

# 5. Manifeste : permissions + service VPN
mp = app + '/src/main/AndroidManifest.xml'
m = open(mp).read()
perms = ''.join('    <uses-permission android:name="android.permission.%s" />\n' % p for p in
                ['ACCESS_NETWORK_STATE', 'FOREGROUND_SERVICE',
                 'FOREGROUND_SERVICE_SPECIAL_USE', 'POST_NOTIFICATIONS'])
m = m.replace('<application', perms + '\n    <application', 1)
svc = '''
        <service
            android:name=".FasoVpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vpn" />
        </service>
    </application>'''
m = m.replace('</application>', svc, 1)
open(mp, 'w').write(m)
print('Code natif installe')
