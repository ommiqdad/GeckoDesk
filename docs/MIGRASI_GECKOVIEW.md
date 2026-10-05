# Migrasi WebView -> GeckoView (mesin Firefox)

## Kenapa
Google menolak login dari Android WebView (`disallowed_useragent`). GeckoView adalah mesin
browser Firefox yang sah dan diizinkan Google untuk login. UA yang dipakai adalah UA desktop
ASLI milik Gecko (Firefox, Linux x86_64); UA Chrome palsu sudah dihapus.

## Yang berubah
- `MainActivity.kt` ditulis ulang untuk GeckoView (sesi per tab, delegate, prompt, unduhan).
- `GeckoProvider.kt` (baru): satu GeckoRuntime per proses + pengaturan Content Blocking/Safe Browsing.
- `Tab.kt`, `SessionStore.kt`: tab menyimpan `GeckoSession` dan `SessionState` (JSON) per tab.
- `AdBlocker.kt` dihapus: "Blokir iklan" kini memakai perlindungan pelacakan bawaan Gecko
  (kategori iklan, analitik, sosial). Daftar domain lama tidak dipakai lagi.
- Menu "Lebar halaman" dihapus: Gecko mengatur lebar viewport desktop sendiri.
- Unduhan: dialirkan langsung dari respons Gecko (sesi login ikut terbawa) ke folder Download.
- Dropdown `<select>`, alert/confirm/prompt JavaScript, dan upload berkas diimplementasikan
  lewat `PromptDelegate`.
- minSdk naik 24 -> 26 (syarat GeckoView). compileSdk 36, AGP 8.10.1, Gradle 8.11.1, Kotlin 2.1.0.
- R8 dimatikan di rilis sampai diuji penuh dengan GeckoView.

## Langkah pertama
1. Buka di Android Studio (JDK 17), biarkan Gradle sync. Repositori `maven.mozilla.org` harus bisa diakses.
2. Setelah sync berhasil, sematkan versi persis: lihat versi yang terselesaikan di
   `External Libraries` (`org.mozilla.geckoview:geckoview:157.0.xxxxxxxx`) lalu ganti
   `val geckoViewVersion = "157.+"` di `app/build.gradle.kts` dengan versi itu.
3. Uji: login Google, upload berkas, unduhan, video layar penuh, tab baru dari link, cari di halaman.

## Belum diuji / perlu dicek
- Kode belum dikompilasi dan dijalankan (lingkungan penulisan tidak punya akses ke Maven Mozilla).
  Nama API sudah dicocokkan dengan sumber GeckoView rilis 157, tetapi tetap mungkin ada galat kecil.
- Ukuran APK naik sekitar 60-80 MB.
- Belum ada: dialog izin kamera/mikrofon/lokasi (ditolak), pemilih tanggal/warna, autentikasi HTTP,
  passkey/kunci keamanan fisik.
- Penghitung "x/y" pada Cari di halaman memakai angka dari Gecko apa adanya.

## Nama dan ID aplikasi
Aplikasi ini bernama **GeckoDesk** dengan applicationId `com.desktopbrowser.geckodesk`, jadi
terpasang sebagai aplikasi terpisah dari DesktopBrowser lama (`com.desktopbrowser.app`).
Data (login, tab, bookmark) tidak dibagi di antara keduanya. Paket kode (`namespace`) sengaja
tidak diubah supaya tidak perlu memindahkan file sumber.
