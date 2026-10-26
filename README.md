# NetControl – pare-feu Wi-Fi / données mobiles par application (Android, sans root)

## Compiler
1. Ouvrir ce dossier dans **Android Studio** (File > Open).
2. Laisser Gradle se synchroniser (il télécharge Gradle 8.9 et le plugin Android).
3. Brancher le téléphone (débogage USB activé) puis ▶ Run,
   ou Build > Build APK(s) pour obtenir `app/build/outputs/apk/debug/app-debug.apk`.

## Utilisation
- Pour chaque application : touchez **Wi-Fi** ou **Données** pour bloquer (rouge ✕) / autoriser (vert ✓).
- Activez l'interrupteur en haut ; Android demande l'autorisation VPN la première fois.
- Le blocage s'adapte automatiquement quand vous passez du Wi-Fi aux données mobiles.

## Fonctionnement
Android n'autorise pas une app normale à couper le réseau d'une autre app. NetControl utilise donc
un **VPN local** (rien ne sort du téléphone) : seules les apps bloquées pour le réseau actuel
sont envoyées dans ce tunnel, et leurs paquets y sont jetés. Les autres apps ne sont pas touchées.

## Limites
- Un seul VPN peut être actif : NetControl ne peut pas tourner en même temps qu'un autre VPN.
- Activer « VPN permanent » + « Bloquer les connexions sans VPN » dans Réglages > Réseau > VPN
  évite les fuites pendant un changement de réseau.
- Certains téléphones (Xiaomi, Huawei, Oppo…) tuent les services en arrière-plan :
  désactivez l'optimisation de batterie pour NetControl.
