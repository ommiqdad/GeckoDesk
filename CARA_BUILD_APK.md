# Cara mendapatkan APK (pemakaian pribadi)

1. Buat repo baru di GitHub (boleh private), upload seluruh isi folder ini
   (termasuk folder `.github`).
2. Buka tab **Actions** > **Build APK** > **Run workflow**.
3. Tunggu sekitar 5-10 menit sampai hijau, lalu buka run tersebut.
4. Unduh artifact **GeckoDesk-debug-apk** (zip), ekstrak, ambil `app-debug.apk`.
5. Kirim ke HP, buka file-nya, izinkan "Install dari sumber tidak dikenal", lalu install.

Catatan: APK debug sudah ditandatangani otomatis dengan kunci debug, jadi bisa
langsung diinstal. Tidak cocok untuk Play Store.
