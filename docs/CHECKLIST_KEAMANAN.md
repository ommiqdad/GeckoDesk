# Checklist agar APK tidak ditandai Google / antivirus

1. Tanda tangani APK rilis dengan SATU keystore yang konsisten:
   keytool -genkeypair -v -keystore release.jks -alias geckodesk -keyalg RSA -keysize 4096 -validity 10000
   Salin keystore.properties.example menjadi keystore.properties lalu isi. Build: ./gradlew assembleRelease
2. Jangan bagikan APK debug.
3. Unggah ke Google Play (minimal Internal/Closed testing) agar dikenal Play Protect.
4. Uji di virustotal.com; untuk false positive, kirim laporan ke vendor terkait.
5. Hosting unduhan di domain HTTPS milik sendiri dan daftarkan di Google Search Console.
6. Publikasikan docs/PRIVACY_POLICY.md di URL publik.
7. targetSdk masih 34. Play Store menuntut target terbaru; menaikkannya memaksa layout
   edge-to-edge (Android 15+), jadi perlu penyesuaian insets sebelum dinaikkan.
8. Tidak ada cara "memaksa" status aman; reputasi dibangun dari tanda tangan konsisten,
   perilaku wajar, dan waktu.
