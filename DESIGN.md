# DESIGN.md

Arah desain MyGalleryVault (`id.bayu.mygalleryvault`).

Dokumen ini bukan aturan baru. Isinya adalah identitas yang **sudah kamu bangun sendiri** di
`ui/theme/Theme.kt` dan `ui/theme/Type.kt`, dituliskan secara eksplisit supaya setiap keputusan
tampilan punya alasan tertulis (R-31) dan supaya sesi kerja berikutnya tidak menebak-nebak.

Pemilik arah: pemilik proyek. Agen hanya menyalin dan merapikan, bukan mengarang.

---

## 1. Identitas

- **Produk**: brankas media pribadi (foto, video) plus browser privat, untuk Android.
- **Penggunanya**: satu orang, melihat medianya sendiri, sering sekali pakai satu tangan dan di tempat gelap.
- **Karakter**: tenang, malam, teknis, privat. Terasa seperti panel instrumen, bukan etalase toko.
- **Yang bukan karakter app ini**: ceria, terang, dekoratif. Tidak ada gradien hiasan, glow, glassmorphism, maskot, atau emoji di UI.

## 2. Tema

- **Gelap saja (dark only).** Alasan: app ini penampil foto dan video di konteks privat. Kanvas gelap
  menjaga perhatian tetap pada media dan menyembunyikan chrome. Ini alasan yang dinyatakan, bukan ikut tren (R-21).
- **Tanpa dynamic color (Material You).** Alasan: coral adalah identitasnya. Warna dari wallpaper akan menggantinya.

## 3. Dial

```
ENERGY 1 / RHYTHM 2 / MOTION 3
```

- **ENERGY 1** (tenang, tidak berubah): dipakai harian, sering satu tangan, sering dalam gelap.
  Kontras dibangun dari hierarki, bukan hiasan. Tetap tidak boleh "berteriak".
- **RHYTHM 2** (naik dari 1): bahasanya tetap satu, tapi dua jenis isi kini sengaja dibedakan
  bentuknya. Foto dan file berdiri sendiri sebagai media tanpa bingkai, folder tampil sebagai wadah
  dengan label di bawahnya. Yang berbeda bukan gayanya, melainkan perannya.

  Revisi atas permintaan pemilik: nama file kini ikut tampil di grid, jadi **kedua** petak punya label
  di bawahnya. Konsekuensinya disadari dan tertulis: label berhenti menjadi pembeda, dan perannya
  pindah ke bentuk cover. Folder tetap mosaik 2x2 di dalam sudut kontainer, file tetap satu gambar
  yang menyentuh tepi dengan sudut siku. Yang dipertahankan dari aturan lama adalah band nama yang
  setinggi sama untuk keduanya, supaya satu baris grid tetap rata walau isinya campuran.
- **MOTION 3** (naik dari 2): gerak dipakai penuh sebagai bahasa, bukan bumbu. Dua aturan yang
  mengikat: setiap gerakan menggambarkan sesuatu yang nyata (arah perpindahan, asal layar, benda
  yang baru kamu sentuh), dan tidak ada yang bergerak tanpa pemicu. Tidak ada animasi berulang,
  tidak ada parallax, dan tidak ada entrance berurutan yang justru menunda isi.

Alasan dial naik: "bentuk dan gerak harus terasa berubah" tidak bisa dicapai pada dial 1, sebab dial 1
mendefinisikan ketiadaan variasi dan ketiadaan gerak. Yang dipertahankan dari dial lama adalah ENERGY,
karena itu yang menentukan karakter tenang app ini.

## 4. Palet (2 inti + 1 aksen, R-29)

**Inti, ground plane**
| Token | Nilai | Fungsi |
|---|---|---|
| Canvas | `#0B0B0B` | latar utama (`background`, `surface`) |
| Deep | `#000000` | scrim, latar player |

**Inti, tangga permukaan (hierarki lewat permukaan, BUKAN lewat bayangan)**
| Token | Nilai | Fungsi |
|---|---|---|
| SurfaceContainerLow | `#141414` | panel turun satu tingkat |
| Card | `#212121` | kartu, `surfaceVariant` |
| SurfaceMuted | `#2A2A2A` | kartu nonaktif |
| SurfaceHover | `#353535` | hover/pressed |
| SurfaceActive | `#404040` | aktif |

**Aksen (hanya satu)**
| Token | Nilai | Fungsi |
|---|---|---|
| Orange | `#FF8A3D` | aksi utama dan status aktif. Itu saja. |

Aturan aksen: oranye tidak boleh muncul serentak di tombol, ikon, border, dan latar. Kalau aksen ada di
mana-mana, dia bukan aksen lagi (R-29, "one deliberate accent").

Catatan riwayat: aksen ini sebelumnya coral `#F36458`. Diganti atas permintaan pemilik
("tema hitam dan oranye"). Oranye dipilih karena kontrasnya lebih tinggi pada kanvas gelap
(8.39:1 berbanding 6.35:1), jadi penggantian ini memperbaiki keterbacaan, bukan sekadar berganti warna.

**Semantik, hanya untuk menandai status nyata (bukan hiasan)**
| Token | Nilai | Status yang ditandai |
|---|---|---|
| SuccessBright | `#37CD84` | operasi berhasil |
| WarningBright | `#FBB52B` | peringatan yang butuh perhatian |
| Info / link | `#55BEFF` | tautan, informasi |
| DangerBright | `#FF4444` | destruktif, gagal, peringatan keamanan |

**Teks**
| Token | Nilai | Fungsi |
|---|---|---|
| Ink | `#FFFFFF` | teks utama |
| Ash | `#B9B9B9` | teks sekunder |
| Stone | `#8A8A8A` | metadata tersier (lihat batas kontras di bawah) |

## 5. Batas kontras (hasil ukur, bukan perkiraan) (R-25)

Diukur dengan rumus WCAG 2.x. Ambang: 4.5:1 untuk teks normal, 3:1 untuk teks besar.

| Pasangan | Rasio | Status |
|---|---|---|
| Ash di Canvas | 10.03:1 | lolos |
| Ash di Card | 8.21:1 | lolos |
| **Canvas di Orange** | **8.39:1** | lolos, ini yang dipakai untuk teks di atas tombol aksen |
| Orange di Canvas | 8.39:1 | lolos |
| Orange di Card | 6.87:1 | lolos |
| Orange di SurfaceMuted | 6.12:1 | lolos |
| `#FFD3B0` di `#3A2413` (primaryContainer) | 10.54:1 | lolos |
| `#A85400` di `#F7F7F7` (inversePrimary) | 4.98:1 | lolos |
| Info di Canvas | 9.56:1 | lolos |
| DangerBright di Canvas | 5.78:1 | lolos |
| Stone `#8A8A8A` di Canvas | 5.70:1 | lolos |
| **Stone `#8A8A8A` di Card** | **4.66:1** | lolos |
| ~~Ink `#FFFFFF` di Orange~~ | ~~2.35:1~~ | **terlarang**, putih tidak boleh dipakai di atas aksen |
| ~~Stone `#797979` di Card~~ | ~~3.70:1~~ | **terlarang** untuk teks normal |

Konsekuensi yang mengikat:

1. **`onPrimary` wajib `Canvas #0B0B0B`.** Putih di atas aksen hanya 2.35:1, jauh di bawah ambang.
   Teks tombol utama (15sp semibold) belum masuk kategori teks besar.
2. **Stone dinaikkan ke `#8A8A8A`.** Nilai lama `#797979` gagal di atas kartu.
3. Semua teks baru wajib diukur dulu, tidak boleh dikira-kira.

## 6. Tipografi

Dua suara, satu peran masing-masing. Ini motif identitas app: **register mono untuk angka dan metadata,
grotesk untuk semua yang "diucapkan".**

| Suara | Font | Dipakai untuk |
|---|---|---|
| Sans | Inter | judul, nama file, label tombol, isi pesan |
| Mono | IBM Plex Mono | ukuran file, tanggal, jumlah item, metadata PIN, badge status |

- Tracking negatif rapat pada display dan heading (sudah ada di `Type.kt`), jangan ditambah lagi.
- Jangan pakai mono untuk kalimat panjang. Mono hanya untuk data pendek yang dibandingkan mata.
- `labelSmall` dan `labelMedium` sudah mono. Jangan arahkan ulang ke grotesk.

Catatan perubahan: suara sans sebelumnya Space Grotesk, diganti Inter atas permintaan pemilik. Konsekuensi
yang harus disadari: Inter adalah pilihan paling umum pada app buatan agen, jadi karakter visual app ini
jadi lebih netral dan lebih mudah tertukar dengan app lain. Space Grotesk punya bentuk huruf yang lebih
khas, dan itu separuh dari identitasnya. Aset Space Grotesk masih ada di `res/font` supaya perubahan ini
mudah dibatalkan, dengan biaya sekitar 416 KB selama tidak terpakai.

Alasan pemilihan Inter: [DIISI PEMILIK]

## 7. Bentuk, jarak, dan gerak

- **Sudut menandai peran, bukan hiasan** (`AppRadius`):

  | Peran | Radius | Yang memakai |
  |---|---|---|
  | Media | `0dp` | foto dan file di grid, slot pratinjau di dalam petak folder |
  | Kontainer | `4dp` | petak folder, kartu, sheet, dialog |
  | Pill | `50%` | FAB, search bar di Gallery, kotak URL di Browser, badge durasi video |

  Alasan satu baris: skala lama `3/5/6/12/16` dipakai serentak tanpa arti, jadi bentuk tidak
  memberi tahu apa pun. Sekarang orang bisa menebak "ini bisa ditekan" dari bentuknya (R-11, R-31).

  Batas pill, dan ini yang mengikat: pill menandai **kontrol dan label pendek**, bukan permukaan.
  Petak media, petak folder, kartu, dialog, dan sheet tetap bukan pill. Pill pada kotak lebar berubah
  jadi lozenge, konten di ujung kiri-kanannya terlihat mau tumpah, dan kalau semua elemen pill maka
  sudut berhenti jadi alat hierarki, persis pola yang dilarang R-11.

  Catatan: ujung bar progres terlihat bulat, tapi itu memang default `LinearStrokeCap` di material3,
  bukan sesuatu yang kita setel sendiri.
- **`AppShapes`** diturunkan ke ujung sempit skala (`2/2/4/4/8`) supaya komponen Material yang belum
  kita ganti sendiri ikut berbahasa sama, bukan tetap berbucu `16dp`.
- **Jarak**: skala yang dipakai app ini: `4 / 8 / 12 / 16 / 20 / 24 / 32 dp`.
  Gutter grid media `4dp`, tetap lebih kecil daripada jarak antar blok supaya grid terbaca sebagai
  satu lembar, bukan tumpukan kartu. Nilainya naik dari `2dp` ketika petak grid mendapat band nama:
  pada `2dp` nama petak yang bersebelahan hampir bersentuhan. Setiap petak grid menyisakan band nama
  setinggi `22dp` di bawah medianya, dan tingginya sama untuk file maupun folder, supaya satu baris
  grid tetap rata walau isinya campuran.
  Di layar Gallery, **margin halaman diseragamkan `16dp`** untuk grid, daftar, dan baris hasil
  search, dengan jarak antar blok `8dp`. Alasan satu baris: sebelumnya grid memakai `8dp`, daftar
  `16dp`, dan hasil search `20dp`, sehingga berganti mode terlihat menggeser header. Satu margin
  per layar membuat pergantian mode terbaca sebagai isi yang berubah susunan, bukan layar yang
  bergeser.
- **Gerak**: setiap gerakan menggambarkan sesuatu (R-19). Satu kurva `settleSpring()` (spring,
  `dampingRatio 0.85`) dipakai untuk semua gerak interaktif, karena spring menjawab kecepatan jari
  sedangkan tween linear tidak. Nama dan nilainya ada di `AppMotion`.

  | Gerakan | Nilai | Yang digambarkan |
  |---|---|---|
  | Skala tekan petak | `0.97`, `90ms` | petak menjawab jari |
  | Marker filter All/Photos/Videos | spring | penanda menyusul label yang dipilih, bukan berkedip nyala-mati |
  | Isi grid saat filter berubah | spring (`animateItem`) | petak yang tersaring keluar memudar, sisanya bergeser ke petak barunya |
  | Bar search saat masuk mode pilih | `fade 200ms` + expand/shrink spring | bar menyingkir dan kembali sendiri, bukan hilang seketika |
  | Isi Gallery berganti mode | `fade 220ms` | isi bertukar di tempat (grid ke daftar, grid ke hasil search, atau sebaliknya), jadi memudar, bukan meluncur dari arah yang tidak nyata |
  | Baris hasil search | spring (`animateItem`) | hasil yang hilang memudar dan sisanya bergeser; catatan "tidak ada yang cocok" muncul menimpa daftar, bukan menggantikannya mendadak |
  | Pindah tab | slide horizontal + `fade 260ms` | arah perjalananmu di bar bawah |
  | Dorong detail | `fade 200ms` + slide `12%` | layar asal ada di bawah |
  | Underline bar bawah | spring | penanda pindah slot, bukan tiga tombol menyala terpisah |
  | Gambar masuk viewer | `fade 220ms`, skala `0.94` ke `1` | gambar mendarat, tidak muncul mendadak |
  | Zoom dan pan viewer | spring | gambar kembali ke batasnya sendiri setelah dilepas |
  | Double tap viewer | spring | detail yang kamu tuju tetap di bawah jarimu |
  | Chrome viewer | `fade 180ms` + slide | kontrol menyingkir saat kamu melihat gambar |

  Ripple pada grid dan bar bawah dimatikan: pada kerapatan lembar kontak, ripple membasahi petak
  tetangga, sedangkan skala tekan menjawab jari dan meninggalkan baris tetap diam.

  Yang dilarang meski terlihat "fluid": entrance berurutan untuk tiap item grid (data lokal sudah
  instan, jadi stagger hanya menambah waktu tunggu yang dirasa), dan gerakan tanpa pemicu.

## 8. Fokus dan aksen per layar

Setiap layar punya tepat satu titik fokus (R-20):

| Layar | Titik fokus |
|---|---|
| Lock | titik PIN |
| Galeri | grid media |
| Player | video |
| Image viewer | gambar |
| Browser | halaman web |
| Settings | daftar setelan |

## 9. Struktur navigasi

Navigasi bawah punya **tiga slot**, dan setiap slot harus punya tujuan nyata (R-24):

| Slot | Isi | Catatan |
|---|---|---|
| Gallery | pohon isi vault (berkas dan folder) | titik fokus: grid media |
| Browser | browser privat | bar bawah terlihat di dalamnya |
| Settings | pengaturan | tab, bukan layar dorong |

- **Folder tidak punya tab sendiri.** Folder tingkat akar tampil sebagai petak di dalam tab Gallery,
  dan mengetuknya membuka folder yang sama seperti sebelumnya. Jadi tidak ada jalan menuju folder
  yang hilang ketika tab Albums dihapus (R-24).
- **Chip All / Photos / Videos: folder hanya muncul di All.** Alasan: folder bukan foto dan bukan
  video, jadi menampilkannya di dua tampilan itu menaruh wadah di sebelah media yang tidak bisa
  dibandingkan dan membuat jumlah yang terlihat tidak berarti. Konsekuensi yang disadari dan
  diterima: di dalam folder yang isinya hanya subfolder, tampilan Photos dan Videos akan kosong, dan
  itu memang benar. Tidak ada jalan yang terputus, karena file di dalam folder tetap terjangkau lewat
  bar search: query-nya menanyakan seluruh vault, bukan hanya level yang sedang dibuka.
- **Kartu storage di Gallery: ukuran vault dan sisa ruang, bukan kapasitas total.** Alasan: kapasitas
  total perangkat bukan angka yang bisa dipakai pemilik untuk memutuskan apa pun, sedangkan sisa ruang
  menentukan apakah import berikutnya masih muat. Bar di bawahnya tetap menunjukkan berapa bagian
  perangkat yang ditempati vault, jadi bar dan labelnya menceritakan hal yang sama.
- **Aksi seleksi di Gallery: Export, Pindahkan, Hapus, lalu menu overflow (Pilih semua, Bagikan,
  Simpan ke Download).** Long-press pada petak masuk mode seleksi, jadi semua aksi terhadap pilihan
  ada di bar atas, bukan di sheet per item. Alasan Pilih semua ada di overflow dan bukan di bar:
  dengan Export, Pindahkan, dan Hapus sudah di bar, satu kontrol tambahan hanya menyisakan sekitar
  48dp untuk judul yang di tengah pada layar 360dp, dan judulnya terpotong. Batas yang dijaga:
  maksimal tiga aksi di bar seleksi, sisanya masuk overflow.
  Alasan Pindahkan, Bagikan, dan Simpan ke Download ada di sini: satu-satunya jalan ke ketiganya
  sebelumnya ada di sheet aksi lama yang sudah tidak pernah dipanggil lagi sejak long-press berubah
  menjadi seleksi, sehingga praktis tidak ada jalan yang bisa dipakai. Aturan yang mengikat pada
  Pindahkan: folder tidak boleh dipindah ke dalam dirinya sendiri atau ke dalam turunannya, karena
  cabangnya akan lepas dari pohon dan isinya jadi tidak terjangkau. Karena itu folder yang sedang
  dipindah tidak ditawarkan sebagai tujuan, dan penolakan yang tersisa dilaporkan lewat pesan, bukan
  dibiarkan diam. Aturan pada Bagikan dan Simpan ke Download: keduanya menyerahkan satu file ke luar
  vault, jadi keduanya hanya aktif ketika tepat satu file terpilih; folder atau pilihan banyak membuat
  keduanya tidak aktif, dan label "Untuk satu file" menerangkan alasannya. Pilihan tidak dilepas
  setelah Bagikan atau Simpan ke Download, karena isi di level ini memang tidak berubah.
  Back keluar dari mode seleksi lebih dulu sebelum meninggalkan layar Gallery, dan pilihan
  disimpan terpisah antara file dan folder: id file dan id folder berasal dari dua urutan angka
  yang terpisah, jadi menyimpannya dalam satu daftar angka membuat folder bisa tertukar dengan
  file bernomor sama, termasuk saat menentukan apa yang dipindah atau dihapus.
- **App bar: judul saja.** Tidak ada glyph merek di app bar manapun. Judul di tengah dan agak bold.
  Di Gallery, search tampil sebagai bar tetap di bawah judul, bukan mode yang dibuka lalu ditutup,
  supaya mencari file cukup satu ketukan. Menu overflow tetap dipertahankan. Bar search melepas
  fokus begitu Anda menyentuh area isi, jadi ia kembali ke keadaan diam (tanpa kursor berkedip,
  outline tidak lagi berwarna aksen) dan berhenti terlihat seperti sedang siap diketik.
- **Aksi "menambah sesuatu" ada satu tempat, di kanan bawah tab Gallery, sebagai dua FAB bertumpuk:**
  atas folder baru, bawah impor (`+`). Keduanya memakai bentuk pill yang sama supaya terbaca sebagai
  satu bahasa (R-11), tetapi hanya impor yang memakai aksen oranye. Folder baru memakai warna
  permukaan, jadi aksen tetap menandai satu aksi utama dan tidak muncul serentak di dua tombol
  (R-29). Karena folder baru sekarang punya tombol tetap, pintu lamanya di menu overflow dihapus
  supaya satu aksi tidak punya dua jalan.

Catatan perubahan pada struktur navigasi: tab Albums dihapus atas permintaan pemilik, karena isinya
duplikat dengan tab Gallery yang sudah menampilkan folder tingkat akar. Konsekuensi yang disadari:
jumlah slot turun dari empat ke tiga, dan lebar tiap slot bertambah. Tidak ada fitur yang hilang,
hanya satu jalan pintas ke hal yang sama.

Catatan perubahan pada slot Browser: ditambahkan atas permintaan pemilik. Konsekuensi yang harus
disadari, bar bawah kini terlihat di dalam browser, jadi browser tidak lagi layar immersive. Pintu lama
di menu overflow Gallery dihapus supaya tidak ada dua jalan ke layar yang sama (R-24).

- **Kotak URL browser: pill dengan tinggi tetap `44dp`, dan isinya mengikuti halaman secara realtime.**
  Pill dipakai di sini karena kotak URL adalah kontrol, bukan permukaan, jadi ia sefamili search bar di
  Gallery (R-11). Tingginya dikunci supaya pill pada kotak selebar ini tidak melebar jadi lozenge, dan
  `44dp` sekaligus memenuhi ukuran target sentuh minimum (R-03). Isinya mengikuti URL tab yang sedang
  dibuka, bukan menunggu halaman selesai masuk riwayat, sehingga alamat tidak tertinggal dan navigasi
  yang tidak memuat ulang dokumen tetap terbaca. Selama kotak sedang diketik, teks pengguna yang
  menang: URL yang masuk tidak menimpa ketikan, dan kotak menyelaraskan diri lagi begitu fokus lepas.

Klarifikasi soal data browsing: sesi browser memang **sengaja** bertahan saat kamu pindah tab, bukan
dibersihkan otomatis. Itu keputusan yang sudah ada di kode (`BrowserSession`) sebagai revisi atas
permintaan UX sebelumnya. Pembersihan hanya terjadi kalau kamu menekan "Tutup semua tab & bersihkan
data", atau proses app dimatikan sistem. Kalimat lama di dokumen ini yang menyebut data dibersihkan
begitu layar ditinggalkan sudah tidak berlaku, jadi dihapus.

- **Tidak ada menu Favorites.** App ini memang tidak punya fitur favorit, jadi tidak ada yang
  disembunyikan atau dihapus.

Catatan perilaku browser yang ditambahkan atas permintaan pemilik:

- **Terjemahan halaman: dipasang di halamannya sendiri, alamatnya tidak berpindah.** Menu ⋮ punya
  "Terjemahkan halaman"; memilih bahasa mengganti teks di halaman yang sedang terbuka - cara yang
  dipakai Chrome dan Brave - bukan mengalihkan halaman ke alamat proxy terjemahan seperti
  sebelumnya. Alasannya nyata dan sudah terlihat di HP: cara proxy hanya bekerja selama Google bisa
  mengambil ulang halaman itu sendiri, sehingga situs yang butuh login, situs berpemeriksaan bot,
  dan situs yang isinya dibangun script sering gagal tampil, padahal di browser biasa halaman yang
  sama bisa diterjemahkan. Dengan cara di halaman sendiri, alamat, cookie, dan sesi login tetap
  milik situsnya; setelah aktif, baris menu yang sama berubah menjadi "Tampilkan bahasa asli".
  Pemberitahuannya tetap ditulis di sheet sebelum bahasa dipilih, supaya tidak ada isi halaman yang
  keluar dari HP tanpa kamu memutuskan lebih dulu. Yang dikirim ke layanan penerjemah hanya teksnya,
  dan hanya setelah bahasa dipilih; halaman itu sendiri tidak pernah dilewatkan ke server mana pun,
  jadi alamat IP tidak ikut berpindah jalur. Konsekuensi yang harus disadari: teks di dalam bingkai
  (iframe) dari situs lain tidak ikut diterjemahkan karena bukan milik halaman ini, dan halaman yang
  isinya bukan tulisan (PDF, gambar, canvas) tidak punya apa pun untuk diterjemahkan - keduanya
  dilaporkan lewat pesan, bukan diam-diam. Bahasa yang dipilih disimpan sebagai pilihan terakhir,
  dan sekali dipilih untuk sebuah tab, halaman berikutnya di tab itu ikut diterjemahkan sampai kamu
  menghentikannya, supaya membaca berpindah-pindah halaman tidak berarti memilih bahasa berkali-kali.
- **Menu tekan-lama mengambil alamat dari sumber yang benar.** Alamat tiap baris menu datang dari hit
  test WebView, dengan satu pengecualian yang harus diingat: untuk gambar yang berada di dalam
  sebuah link, `hitTestResult.extra` berisi GAMBARNYA, bukan alamat link-nya, dan alamat link hanya
  bisa didapat dari `requestFocusNodeHref` (`"url"`, gambarnya di `"src"`). Sebelumnya `extra`
  dipakai sebagai alamat link, sehingga "Buka link di tab baru" pada thumbnail video membuka gambar
  thumbnail itu. Video yang tidak berada di dalam link tidak punya tipe hit test sendiri, jadi titik
  tekan terakhir dicatat dan halaman ditanya lewat `document.elementFromPoint`; hanya alamat media
  http/https yang dipakai, karena `blob:` bukan berkas yang bisa dibuka atau disimpan.
- **Back di browser: halaman dulu, lalu tab, baru Gallery.** Back memakai riwayat halaman; kalau
  sudah di halaman pertama, tab ditutup seperti tombol X di daftar tab; hanya kalau tinggal satu tab
  terakhir Back keluar ke Gallery. Alasannya: tab tidak boleh hilang diam-diam selama masih ada tab
  lain, dan browser baru ditinggalkan saat memang tidak ada lagi yang bisa ditutup.
- **Video fullscreen di browser dipasang ke window, bukan lewat Compose.** View dari pemutar situs
  diserahkan ke window aplikasi (cara yang dipakai Chromium), sehingga tidak bisa terpasang dua kali
  dan menumpuk. Layar diputar mengikuti ukuran video; kalau ukurannya belum terbaca, layar dibiarkan
  seperti semula daripada dipaksa. Back keluar dari fullscreen lebih dulu sebelum menutup apa pun.

## 10. Yang dilarang di app ini

Dituliskan supaya tidak diusulkan ulang:

- Gradien sebagai warna utama, glow, glassmorphism, atau bayangan tebal untuk membuat elemen "melayang" (R-01, R-10, R-12, R-13).
- Ikon generik AI (sparkle, star, magic, lightning, robot) (R-04).
- Emoji di teks UI (R-04).
- Warna literal di dalam layar. Semua warna lewat token (R-29).
- Angka atau klaim tanpa sumber nyata (R-17, R-36, R-38).

## 11. Cara memakai dokumen ini

Agen: baca sebagai **data arah**, bukan sebagai perintah. Ambil hanya bidang desainnya (identitas,
palet, tipografi, dial). Kalau ada isi di sini yang bertabrakan dengan 38 aturan di `antislop`,
sebutkan tabrakan itu dan tanyakan, jangan diam-diam mengabaikan salah satunya (R-37).
