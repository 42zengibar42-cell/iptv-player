package com.iptv.player

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Uygulama içi kullanım kılavuzu */
class HelpActivity : AppCompatActivity() {

    private val topics = listOf(
        "Ana ekran" to """
# Üst düğmeler
Canlı TV · Filmler · Diziler · ☰ Listem · ★ Favoriler: bölümler arasında geçiş.
Ara: kanal, film ve dizi arar (aşağıda "Arama").
⇅: sıralama şekli (Sıra / A–Z / En yeni).
⟳ Yenile: sağlayıcının güncel listesini indirir. Ayarların bozulmaz.
⚙: Ayarlar ve hesaplar.

# Ekranın düzeni
Solda kategoriler, sağda o kategorinin kanalları.
Canlı TV'de sağda küçük önizleme penceresi: bir kanalın üzerine gelince birkaç saniye içinde oynar, altında "şimdi / sonra" yayın akışı yazar.

# Kumanda (listede)
OK: kanalı, filmi veya diziyi aç.
OK'a basılı tut: menü (Favori, Listem, ikinci kanal, sırala, gizle).
◀: kanallardan kategori listesine dön.
Kategoriye basılı tut: kategori menüsü (kanalları sırala, gizli kanallar, kategoriyi gizle).

# Kanal yanındaki işaretler
★ favori   ☰ Listem'de   ✓ izlendi (film / bölüm)
""",
        "Canlı TV izlerken" to """
# Kanal değiştirme
▲ ▼ veya CH+ / CH−: önceki / sonraki kanal (açtığın kategorinin içinde).
0–9: kanal numarasını yaz (ör. 1-2-5). Sağ üstte numara görünür, 1,5 saniye sonra o kanal açılır.
◀: bir önceki izlediğin kanala dön. Tekrar ◀ ile geri gelirsin (iki kanal arasında gidip gelme).

# Diğer tuşlar
OK: kanal adı ve yayın akışı (şimdi / sonra).
▶: menü: ikinci kanal, ses ve altyazı, uyku zamanlayıcısı.
OK'a basılı tut: görüntü ayarı (aşağıda).
Geri: listeye dön. İkinci kanal açıksa önce o kapanır.

# Görüntü ayarı (siyah boşluklar)
OK'a basılı tuttukça sırayla değişir:
1. Sığdır: orijinal, üstte / altta siyah boşluk kalabilir.
2. Yakınlaştır: tam ekran, kenarlardan biraz kesilir.
3. Ger: tam ekran, görüntü biraz yassılaşır.
Seçimin kaydedilir, diğer kanal ve filmlerde de geçerli olur.
""",
        "Filmler ve diziler" to """
# Film
Bir filme OK: bilgi ekranı açılır (afiş, konu, yıl, tür, süre, puan, oyuncular).
▶ İzle. Yarıda bıraktıysan "▶ Devam et (saat)" ve "↺ Baştan izle" çıkar.
Bilgi ekranından Favori ve Listem'e de ekleyebilirsin.

# Dizi
Diziye OK: solda sezonlar, sağda bölümler. İlk izlenmemiş bölüm seçili gelir.
Bölüm bitince 10 saniye geri sayım, sonra sıradaki bölüm başlar (sezon bitince yeni sezona geçer). OK: hemen başlat, Geri: çık.

# İzlerken
OK: oynat / duraklat.
◀ ▶: geri / ileri sar.
⚙ veya CC düğmesi: ses dili ve altyazı seçimi.
OK'a basılı tut: görüntü ayarı (siyah boşlukları kaldırma).
Menü tuşu (kumandada varsa): uyku zamanlayıcısı.

# İzlemeye devam et
Filmler ve Diziler'de kategorilerin en üstünde "▶ İzlemeye devam et": yarım bıraktıkların.
Filmin %90'ını izleyince "izlendi ✓" sayılır ve bu listeden çıkar.
""",
        "Favoriler ve Listem" to """
# Eklemek
Kanal, film veya dizinin üzerinde OK'a basılı tut → "★ Favorilere ekle" veya "☰ Listeme ekle".
Çıkarmak için aynı menüden "… çıkar".

# Fark nedir?
★ Favoriler: sevdiğin kanallar ve içerikler.
☰ Listem: sonra izlemek istediğin film ve diziler (izleme listesi).
İkisi de istediğin gibi kullanılabilir. Sekmelerin içinde Canlı TV / Filmler / Diziler diye ayrılırlar.
""",
        "Arama ve sıralama" to """
# Arama
Ara kutusuna en az 2 harf yaz: canlı kanallar, filmler ve diziler birlikte çıkar (yanlarında TV / Film / Dizi yazar).
İlk aramada film ve dizi listeleri yüklendiği için birkaç saniye sürebilir.
Gizlediğin kategori ve kanallar aramada çıkmaz.

# ⇅ Sıralama
Her basışta değişir:
• Sıra: sağlayıcının sırası, ya da kendi yaptığın kanal sırası.
• A–Z: alfabetik ("DE - ", "TR | " gibi başlıklar dikkate alınmaz).
• En yeni: yeni eklenen filmler / diziler önce (sadece Filmler ve Diziler'de).
Seçim her bölüm için ayrı kaydedilir.
""",
        "Kategorileri düzenle" to """
# Nerede?
Kategori listesinin en altında: "✎ Kategorileri düzenle". Canlı TV, Filmler ve Diziler'de ayrı ayrı.

# Göster / gizle
Kategori satırına OK: ☑ görünür / ☐ gizli.
"☑ Hepsini göster": bütün gizli kategorileri geri getirir.

# Kategori sırasını değiştirme
Satırdayken ▶ ile ▲ ▼ düğmelerine geç. Her OK bir sıra yukarı / aşağı taşır, odak düğmede kalır, arka arkaya basabilirsin.
"↺ Sağlayıcı sırasına dön": kendi sıranı siler.
Geri: kaydet ve çık.

# Hızlı gizleme
Kategori listesinde kategoriye basılı tut → "🚫 Bu kategoriyi gizle".

# Bilmen gerekenler
Sıralama ve gizleme ⟳ Yenile'de ve güncellemelerde bozulmaz.
Sağlayıcının yeni eklediği kategoriler listenin sonuna gelir.
""",
        "Kanalları sıralama" to """
# Nasıl açılır?
Kategoriye basılı tut → "↕ Kanalları sırala",
veya bir kanala basılı tut → "↕ Bu kategorideki kanalları sırala".

# Taşıma (en kolay yol)
Kanala OK: kanalın başında ⇕ çıkar, "taşıma modu" açılır.
▲ ▼ ile kanalı istediğin yere kaydır. CH+ / CH− 10'ar sıra atlar.
Tekrar OK: bırak.

# Düğmelerle
Satırda ▶ ile düğmelere geç:
⤒ en üste taşı (sevdiğin kanalları başa almanın en hızlı yolu)
▲ ▼ bir sıra yukarı / aşağı.

# Bitince
Geri: kaydedilir.
"↺ Sağlayıcı sırasına dön": kategorinin eski sırasına döner.
Kendi sıran sadece ⇅ "Sıra" modunda görünür. Kanal numaraları da senin sırana göre değişir.
""",
        "Kanal gizleme" to """
# Gizlemek
Kanala (veya filme, diziye) basılı tut → "🚫 Bu kanalı gizle".
Listeden, "Tümü"den ve aramadan kaybolur. Numaralar da kayar.

# Geri getirmek
Gizli kanalın kategorisine basılı tut → "👁 Gizli kanallar (sayı)".
Geri istediğin kanalın işaretini kaldır → Kaydet. Ya da "Hepsini göster".
"Tümü" kategorisinde bu menü bölümdeki bütün gizli kanalları gösterir.
""",
        "İki kanal aynı anda" to """
# Açmak
Yol 1: Bir kanalı tam ekran izle, Geri ile listeye dön, ikinci kanala basılı tut → "📺 Köşede aç" veya "▥ Yan yana aç".
Yol 2: İzlerken ▶ → "İkinci kanal aç (köşede / yan yana)" → hesap → kategori → kanal seç.

# İkinci kanal açıkken ▶ menüsü
⇄ Yer değiştir (köşedeki büyür)
🔊 Sesi ikinci kanala ver / ana kanala ver
▣ Köşeye küçült / ▥ Yan yana göster
İkinci kanalı değiştir / kapat
▲ ▼ her zaman ana (sol / büyük) kanalı değiştirir. Geri önce ikinci kanalı kapatır.

# Bağlantı hakkı
Aynı abonelikten iki kanal için aboneliğin en az 2 bağlantılı olmalı. Tek bağlantılıysa uygulama uyarır.
İki aboneliğin varsa ikinci kanalı diğer hesaptan seç: her abonelik kendi bağlantısını kullanır.
Bazı kutular aynı anda iki HD yayını çözmekte zorlanabilir.
""",
        "TV rehberi" to """
Canlı TV'de kategori listesinin altında: "📅 TV rehberi".
Son seçtiğin kategorinin kanalları, 4 saatlik yayın akışı tablo olarak görünür. Şu an yayında olan program yeşil çerçevelidir.
▲ ▼ kanallar, ◀ ▶ programlar arasında gezinir. Bir programa OK: o kanal açılır.
Yayın akışı sağlayıcıdan gelir. Bazı kanallarda "Yayın akışı yok" yazabilir.
""",
        "Uyku zamanlayıcısı" to """
Canlı izlerken ▶ → "⏾ Uyku zamanlayıcısı". Filmde kumandanın Menü tuşu.
15, 30, 45, 60, 90 veya 120 dakika seç.
Kapanmadan 1 dakika önce ekranda uyarı çıkar.
Menüde kalan süre yazar, oradan iptal de edebilirsin.
""",
        "Hesaplar ve abonelik" to """
# ⚙ Ayarlar ekranı
Her hesabın altında: bitiş tarihi, kalan gün, bağlantı sayısı ve şu an kaç yayın açık olduğu.
7 gün ya da daha az kalınca açılışta günde bir kez uyarı çıkar (⚠). Süre dolduysa ⛔.

# Hesap işlemleri
Hesaba OK: Bu hesaba geç / Düzenle / Sil.
"＋ Yeni hesap ekle": ikinci aboneliğini ekle.
Her hesabın favorileri, Listem'i, sıralama ve gizlemeleri ayrıdır.

# Abonelik yenilendi, kullanıcı adı / şifre değişti
En kolayı: hesabı Düzenle, yeni bilgileri yaz, Kaydet. Bütün ayarların aynen kalır.
Yeni hesap olarak eklersen "Önceki ayarlar aktarılsın mı?" diye sorar, eski hesabı seç.
Aktarma aynı sağlayıcıda tam çalışır. Farklı sağlayıcıda kanal numaraları farklı olduğu için tutmayabilir.

# Giriş ekranı
Sunucu adresini portuyla tek satıra yaz: http://ornek.com:8080
Şifre "👁 Göster" ile görünür, "🙈 Gizle" ile gizlenir.
""",
        "Yedekle / Geri yükle" to """
# Yedekle
⚙ → "💾 Yedekle". Hesaplar, favoriler, Listem, sıralamalar, gizlemeler ve kaldığın yerler tek dosyaya (KcKTV-yedek-….json) kaydedilir.
Kutuda dosya seçici varsa yeri sen seçersin (USB bellek de olur). Yoksa İndirilenler (Download) klasörüne kaydedilir.

# Geri yükle
⚙ → "📂 Geri yükle" → yedek dosyasını seç. Şu anki ayarların yedektekilerle değişir.
Yeni kutuda veya uygulamayı silip kurduktan sonra: giriş ekranında "📂 Yedekten geri yükle".

# Dikkat
Yedek dosyasında IPTV kullanıcı adın ve şifren de var. Kimseyle paylaşma, internete yükleme.
""",
        "Açılış ve güncelleme" to """
# Açılışta son kanal
KcKTV açılınca en son izlediğin kanal doğrudan başlar.
Kapatmak / açmak: ⚙ → "▶ Açılışta son kanal: Açık / Kapalı".

# Güncelleme
Uygulama 6 saatte bir yeni sürüm var mı diye bakar ve sorar: "Güncelle" de.
Elle: ⚙ → "⟳ Güncellemeleri denetle".
Ayarların güncellemede silinmez.
Sadece uygulamanın kendi Güncelle düğmesini kullan. Başka yerden gelen KcKTV dosyalarını kurma.
""",
        "Şifre (PIN)" to """
İsteğe bağlı. Koymazsan hiçbir yerde şifre sorulmaz.
✎ Kategorileri düzenle → "🔒 Şifre koy": 4 rakam.
Şifre varken kategori düzenleme ekranı şifreyle açılır, gizli kategoriler sadece orada görünür.
Unutursan: şifre ekranında "Unuttum" → IPTV hesap şifreni gir → şifre kaldırılır (gizli kategoriler yine gizli kalır).
Kaldırmak için: aynı yerde "🔓 Şifreyi kaldır".
""",
        "Sorun giderme" to """
# Kanal açılmıyor
Başka bir kanalı dene. Hepsi açılmıyorsa: ⚙ → hesabın altındaki abonelik bilgisine bak (süre bitmiş olabilir).
⟳ Yenile ile listeyi yeniden indir.
İkinci kanal açıksa kapat (bağlantı hakkı dolmuş olabilir).

# Görüntü donuyor / takılıyor
İnternet hızını kontrol et. Kutuyu mümkünse kabloyla (LAN) bağla.
Akşam saatlerinde sağlayıcı yoğun olabilir.

# Klavyede rakam yazmıyor
Güncel sürümü kur (⚙ → Güncellemeleri denetle). Sorun sürerse kumandadaki rakam tuşlarını dene.

# Liste boş / "Liste yüklenemedi"
⚙ → Düzenle: sunucu adresi, kullanıcı adı ve şifreyi kontrol et.
Sunucu adresinde port olmalı: http://ornek.com:8080

# Logo boş görünüyor
Kanal logosunu sağlayıcı vermemiş. Uygulama yerine TV simgesi koyar.
""",
    )

    private lateinit var tvBody: TextView
    private lateinit var scroll: ScrollView
    private lateinit var rv: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        tvBody = findViewById(R.id.tvBody)
        scroll = findViewById(R.id.scroll)
        rv = findViewById(R.id.rvTopics)

        val adapter = CategoryAdapter { show(it) }
        adapter.items = topics.mapIndexed { i, t -> "${i + 1}. ${t.first}" }
        adapter.selected = 0
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = adapter
        show(0)
        rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }

        // Metindeyken ◀ ile konulara dön
        scroll.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                rv.findViewHolderForAdapterPosition(adapter.selected)?.itemView?.requestFocus()
                true
            } else {
                false
            }
        }
    }

    /** "# Başlık" satırları yeşil ve kalın, diğerleri normal */
    private fun show(i: Int) {
        val (title, body) = topics.getOrNull(i) ?: return
        val accent = ContextCompat.getColor(this, R.color.accent)
        val sb = SpannableStringBuilder()
        fun styled(text: String, vararg spans: Any) {
            val start = sb.length
            sb.append(text)
            for (s in spans) sb.setSpan(s, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        styled(title + "\n", StyleSpan(android.graphics.Typeface.BOLD), RelativeSizeSpan(1.35f))
        for (line in body.trim().lines()) {
            if (line.startsWith("# ")) {
                sb.append("\n")
                styled(line.removePrefix("# ") + "\n", StyleSpan(android.graphics.Typeface.BOLD), ForegroundColorSpan(accent))
            } else {
                sb.append(line).append("\n")
            }
        }
        tvBody.text = sb
        scroll.scrollTo(0, 0)
    }
}
