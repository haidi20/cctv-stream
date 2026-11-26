# main
## tombol aktif monitoring atau tidak
- tombol aktif atau tidak untuk terhubung ke semua cctv atau tidak, jika tidak maka list cctv yg sdh terhubung jadi terhidden.
jika aktif muncul lagi list cctvnya
- tombol per cctv bisa juga bisa di aktifkan monitoring atau tidak
- tombol re connect per cctv jika cctv tersebut tidak terhubung ke app
- tombol hapus cctv, tapi bisa kita tambah lagi di fitur scanning cctv
- ketika pertama kali connect cctv, perlu di siapkan form untuk memasukan password untuk bisaa koneksi ke cctvnya dan itu berlaku pertama kali atau 1x saja
- di analisa ketika user klik salah satu cctv lalu di dalamnya di ata kotak cctv streamnya. lalu di bawahhnya muncul hasil analisanya informi lengkap jumlah meja, jumlah meja yang ada orang dan tidak, meja 1 ada berapa orangnya. meja 2 ternyata belum ada orangnya
- di analaisa yang hanya sudah di mapping saja. jika sudah connect tapi belum di mapping belum bisa di analisa

## cctv
- yang di simpan id devicenya jangan ip. jika ada perubahan ip bisa tidak terload settingan mappingnya
-

## login
- jika di form masukkan pin di masukkan tulisan "reset", maka akan mengarah ke page ganti password,
ada 2 form masukkan password dan konfirmasi password
-

## semua fitur
- setiap proses load data, event dll wajib selalu async awaiat loading waiting disabled untuk menghindari user spam klik dan userfriendly tau bahwa ini sedang proses atau loading.
- sync ke server wajib running di background dan jika gagal sync, jangan sampai spam sehingga makan resource memory
-

## mode online or offline
- perlu ada informai bahwa user saat ini lagi offline. lebih baik informasinya bentuknya seperti apa dan taruh dimana?
- jika kembali online muncul sesaat habis itu hilang smooth, pakai warna hijau bg tulisannya "anda kembali online"
perlu di bikin ui ux seperti di iphone. gunakan skill yang sesuai


## pertanyaan
- apakah dari segi user bisa atur per cctv mau di ataur fpsnya berapa? dan pakai yg resolusi berapa?
- 
