package eu.kanade.tachiyomi.extension.en.CloudflareBypass

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

// Cloudflare回避機能を持つ拡張機能のベースクラス
// ConfigurableSourceを実装することで、設定画面を追加できます
class CloudflareBypassSource : ParsedHttpSource(), ConfigurableSource {

    override val name = "Cloudflare Bypass Example"
    // ユーザーがスクレイピングしたいCloudflare保護下のサイトのURLに置き換えてください
    override val baseUrl = "https://your-cloudflare-protected-site.com" 
    override val lang = "en"
    override val supportsLatest = true

    // SharedPreferencesから設定を読み書きするためのオブジェクト
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    // PDFキャッシュディレクトリ
    private val pdfCacheDir: File by lazy {
        File(Injekt.get<Application>().cacheDir, "pdf_cache").apply { mkdirs() }
    }

    // 設定キー
    private val CF_CLEARANCE_KEY = "cf_clearance_cookie"
    private val USER_AGENT_KEY = "user_agent_override"

    // -------------------------------------------------------------------------
    // 1. クッキーをリクエストに適用するロジック
    // -------------------------------------------------------------------------

    // OkHttpClientをカスタマイズし、Interceptorを追加します
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::cookieInterceptor)
        .build()

    private fun cookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cfClearance = preferences.getString(CF_CLEARANCE_KEY, null)
        val userAgent = preferences.getString(USER_AGENT_KEY, null)

        // クッキーとユーザーエージェントをリクエストヘッダーに追加
        val newRequest = request.newBuilder().apply {
            if (!cfClearance.isNullOrBlank()) {
                // クッキーヘッダーを追加
                header("Cookie", "cf_clearance=$cfClearance")
            }
            if (!userAgent.isNullOrBlank()) {
                // ユーザーエージェントを上書き
                header("User-Agent", userAgent)
            }
        }.build()

        val response = chain.proceed(newRequest)

        // ここに、レスポンスがCloudflareのチャレンジページだった場合の処理を追加できます
        // 例: if (response.code == 503 && response.body?.string()?.contains("Cloudflare") == true) {
        //         // WebViewを起動してチャレンジを解決するロジックを呼び出す（mihon本体の機能に依存）
        //     }

        return response
    }

    // -------------------------------------------------------------------------
    // 2. 設定画面の作成ロジック (手動クッキー入力用)
    // -------------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // クッキー入力フィールドの追加
        screen.editTextPreference {
            key = CF_CLEARANCE_KEY
            title = "Cloudflare クッキー (cf_clearance)"
            summary = "外部で取得したcf_clearanceクッキーの値を入力してください。"
            isPersistent = true
        }

        // ユーザーエージェント上書きフィールドの追加
        screen.editTextPreference {
            key = USER_AGENT_KEY
            title = "User-Agent 上書き"
            summary = "必要に応じて使用するUser-Agentを入力してください。"
            isPersistent = true
        }

        // WebViewでチャレンジを解決するためのヒント
        screen.infoPreference {
            title = "Cloudflareチャレンジ解決のヒント"
            summary = "クッキーが失効した場合、このソースのページを開き、メニューから「Open in WebView」を選択してチャレンジを解決してください。解決後、ブラウザからクッキーをコピーして上記フィールドに手動で貼り付けるか、拡張機能が自動でクッキーを取得するロジックを実装してください。"
        }
    }

    // -------------------------------------------------------------------------
    // 3. PDF処理の実装 (スクレイピング + PDFレンダリング)
    // -------------------------------------------------------------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            // チャプターURLにアクセス
            val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
            val body = response.body ?: throw Exception("Null Response Body")
            val contentType = body.contentType()?.toString() ?: ""

            val pdfFile = if (contentType.contains("application/pdf")) {
                // 直接PDFの場合
                savePdf(body.byteStream(), chapter.url)
            } else {
                // HTMLの場合、パースしてPDFリンクを探す
                val html = body.string()
                val document = Jsoup.parse(html, baseUrl + chapter.url)

                // 例: <a>タグのhrefが.pdfで終わるものを探す (実際のサイト構造に合わせて調整が必要)
                // 絶対URLを取得するために attr("abs:href") を使用
                val pdfLink = document.select("a[href$=.pdf]").firstOrNull()?.attr("abs:href")
                    ?: document.select("iframe[src$=.pdf]").firstOrNull()?.attr("abs:src")

                if (pdfLink != null) {
                    val pdfResponse = client.newCall(GET(pdfLink, headers)).execute()
                    savePdf(pdfResponse.body!!.byteStream(), pdfLink)
                } else {
                    // PDFが見つからない場合、親クラス(ParsedHttpSource)のHTML画像抽出ロジックに委譲できるか試みる
                    // しかしここはObservableを返す必要があるため、手動でpageListParseを呼ぶ
                    return@fromCallable pageListParse(document)
                }
            }

            // PDFファイルからページリストを生成
            getPdfPageList(pdfFile)
        }
    }

    override fun fetchImage(page: Page): Observable<Response> {
        return Observable.fromCallable {
            if (page.imageUrl?.startsWith("pdf:") == true) {
                // PDFページの場合、レンダリングして返す
                val args = page.imageUrl!!.split(":")
                // 形式: pdf:<filename>:<pageIndex>
                // filenameがコロンを含む可能性を考慮すべきだが、hashStringで作るので大丈夫
                val filename = args[1]
                val pageIndex = args[2].toInt()
                val pdfFile = File(pdfCacheDir, filename)

                val bitmap = renderPdfPage(pdfFile, pageIndex)
                val output = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                val stream = java.io.ByteArrayInputStream(output.toByteArray())

                Response.Builder()
                    .code(200)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .request(okhttp3.Request.Builder().url(page.url).build())
                    .message("OK")
                    .body(stream.readBytes().toResponseBody("image/jpeg".toMediaTypeOrNull()))
                    .build()
            } else {
                // 通常の画像URLの場合
                super.fetchImage(page).toBlocking().first()
            }
        }
    }

    // PDFをキャッシュに保存する
    private fun savePdf(inputStream: InputStream, identifier: String): File {
        val filename = hashString(identifier) + ".pdf"
        val file = File(pdfCacheDir, filename)

        // 既に存在し、サイズが0でなければ再ダウンロードしない（簡易キャッシュ）
        // ※ 本来はETagや更新日時を確認すべきですが、ここでは簡易実装
        if (file.exists() && file.length() > 0) {
            return file
        }

        FileOutputStream(file).use { output ->
            inputStream.copyTo(output)
        }
        return file
    }

    // PDFファイルを開いてページリストを作成
    private fun getPdfPageList(file: File): List<Page> {
        val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(fileDescriptor)
        val pageCount = pdfRenderer.pageCount
        pdfRenderer.close()
        fileDescriptor.close()

        val pages = mutableListOf<Page>()
        for (i in 0 until pageCount) {
            // URLスキーム: pdf:<filename>:<pageIndex>
            pages.add(Page(i, "", "pdf:${file.name}:$i"))
        }
        return pages
    }

    // 指定されたPDFページをレンダリングする
    private fun renderPdfPage(file: File, pageIndex: Int): Bitmap {
        val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(fileDescriptor)

        val page = pdfRenderer.openPage(pageIndex)
        // 高解像度でレンダリングするためにサイズを調整（例：幅を2倍）
        // デバイスの画面サイズに合わせて調整するのがベストだが、ここでは固定倍率
        val scale = 2
        val width = page.width * scale
        val height = page.height * scale
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        page.close()
        pdfRenderer.close()
        fileDescriptor.close()

        return bitmap
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // 4. その他の必須メソッド (ParsedHttpSource)
    // -------------------------------------------------------------------------

    // 人気のマンガリストを取得するリクエスト
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular?page=$page", headers)
    
    // HTMLからマンガの要素を選択するCSSセレクタ
    override fun popularMangaSelector() = "div.manga-list > div.manga-item"
    
    // 選択された要素からSMangaオブジェクトを作成
    override fun popularMangaFromElement(element: org.jsoup.nodes.Element): SManga {
        // ここに実際のスクレイピングロジックを実装します
        return SManga.create().apply {
            title = element.select("a").attr("title")
            url = element.select("a").attr("href")
        }
    }

    // ... 他の必須メソッドも同様に実装する必要があります ...
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Not implemented")
    override fun latestUpdatesSelector() = throw UnsupportedOperationException("Not implemented")
    override fun latestUpdatesFromElement(element: org.jsoup.nodes.Element) = throw UnsupportedOperationException("Not implemented")
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException("Not implemented")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException("Not implemented")
    override fun searchMangaSelector() = throw UnsupportedOperationException("Not implemented")
    override fun searchMangaFromElement(element: org.jsoup.nodes.Element) = throw UnsupportedOperationException("Not implemented")
    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not implemented")
    override fun mangaDetailsParse(document: Document) = throw UnsupportedOperationException("Not implemented")
    override fun chapterListSelector() = throw UnsupportedOperationException("Not implemented")
    override fun chapterFromElement(element: org.jsoup.nodes.Element) = throw UnsupportedOperationException("Not implemented")
    override fun pageListParse(document: Document) = throw UnsupportedOperationException("Not implemented")
    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not implemented")
}
