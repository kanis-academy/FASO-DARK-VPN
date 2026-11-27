# Faso DARK : version réelle (sans démo)

Même interface, mêmes fonctions. Ce qui était simulé est maintenant réel :

- **Connexion VPN** : vrai tunnel Android (VpnService). VMess, VLESS (Reality), Trojan, Shadowsocks, SOCKS, HTTP et JSON perso passent par sing-box ; WireGuard par la bibliothèque officielle.
- **Autorisation VPN** : la vraie fenêtre Android.
- **IP du téléphone** : lue en vrai (api.ipify.org). L'IP ne change que si le réseau change (mode avion, nouveau réseau) : la recherche réessaie toutes les 4 s, 20 fois au maximum.
- **Test des hôtes** : vraie résolution DNS + connexion TCP (port 443, puis 80), avec le temps de réponse.
- **Compteurs de données** : vrais (aujourd'hui, semaine, mois, total), gardés sur le téléphone.
- **Verrous d'export** : expiration, durée de vie du fichier, limite de données, blocage WiFi, blocage appareil rooté, appareil autorisé (ID visible dans À propos) : tous appliqués.
- **Profils, listes d'IP et d'hôtes** : enregistrés sur le téléphone. Plus de profil d'exemple.
- **Chiffrement .fasodark** : la clé « démo » est remplacée par une vraie clé.

## Nouveaux champs de profil
Clé publique + Short ID (Reality), Flow (VLESS), empreinte TLS, méthode (Shadowsocks), clé publique + adresse locale (WireGuard).
JSON perso : une config sing-box complète (avec `outbounds`) ou un seul outbound.

## Compiler sur GitHub
```bash
git init
git add .
git commit -m "Faso DARK reel"
git branch -M main
git remote add origin https://github.com/kanis-academy/FASO-DARK-VPN.git
git push -u origin main --force
```
Onglet **Actions → Build APK** (20 à 30 min, le moteur sing-box est compilé à chaque fois). Télécharge l'artefact **faso-dark-apk**.
Utilise `git push` (pas le glisser-déposer) pour que `.github` et `native` soient envoyés.
Si une étape est rouge, envoie les 30 dernières lignes de son journal.

## Contenu
- `index.html` : l'interface
- `native/` : plugin VPN, service VPN (Java) + `install.py`
- `.github/workflows/build.yml` : compilation (libbox.aar, Capacitor, Gradle)
