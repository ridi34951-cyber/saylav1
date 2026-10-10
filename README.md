# GyroLock V26 — Track Rotation Fallback

Proyek Android sumber untuk build APK melalui GitHub Actions. Ini adalah kandidat perbaikan lanjutan setelah V25 tidak mengubah hasil di perangkat pengguna.

## Analisis dua video pembanding
- Video hewan yang berhasil: MPEG-4 Part 2, 718×1278, 30 FPS, tanpa metadata rotasi.
- Video kaligrafi yang gagal: H.264/AVC, encoded pixels 1920×1080, SAR 1:1, display rotation -90°.
- Karena file kedua menyimpan piksel landscape dengan metadata rotasi, jalur OpenGL harus mendapatkan nilai rotasi yang benar sebelum memilih dimensi encoder dan memetakan UV.

## Perubahan V26
- Selain nilai dari `MediaMetadataRetriever`, baca `rotation-degrees` dari `MediaExtractor` track format.
- Jika track format menyertakan rotasi non-zero, prioritaskan nilai itu, normalkan ke 0/90/180/270, lalu gunakan untuk pemetaan OpenGL dan pertukaran dimensi encoder.
- Output tetap dinormalisasi ke piksel dan tidak diberi rotation hint kedua.
- Mempertahankan gyro 240 titik, MediaCodec, target 30 FPS, audio remux, mirror/reflection blur fill, tap-to-focus, EV/AE lock jika didukung, zoom, dan torch.

## Batasan pengujian
V26 belum diuji pada OPPO A54. Perubahan ini menutup jalur yang sebelumnya hanya membaca rotasi dari `MediaMetadataRetriever`, tetapi keberhasilannya harus dibuktikan dengan build dan hasil video nyata. Uji video pendek dahulu dan periksa output dengan ffprobe.

## Build
Upload isi ZIP ke root repository, buka **Actions → Build GyroLock APK**, lalu ambil artifact `GyroLock-debug-apk`.
