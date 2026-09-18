package com.example.divarcrm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var input: EditText
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var targetUrl = ""
    private var extractionStarted = false
    private var contactClicked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        input = findViewById(R.id.urlInput)
        button = findViewById(R.id.importButton)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        web = findViewById(R.id.web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.userAgentString = web.settings.userAgentString + " DivarCRMAndroid/1.0"
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                status.text = "وضعیت: در حال باز کردن دیوار…"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.postDelayed({ inspectAndExtract() }, 1200)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }

        button.setOnClickListener { startImport() }
    }

    private fun startImport() {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) { status.text = "وضعیت: لینک آگهی را وارد کنید"; return }
        targetUrl = raw
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            status.text = "وضعیت: لینک معتبر نیست"; return
        }
        extractionStarted = false
        contactClicked = false
        result.text = ""
        status.text = "وضعیت: در حال دریافت اطلاعات…"
        web.visibility = View.VISIBLE
        web.loadUrl(raw)
    }

    private fun inspectAndExtract() {
        if (targetUrl.isEmpty()) return
        val js = """
        (function(){
          function txt(e){return e ? (e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim() : '';}
          function clickContact(){
            const keys=['اطلاعات تماس','نمایش شماره','شماره تماس','تماس با فروشنده','مشاهده شماره'];
            const els=[...document.querySelectorAll('button,a,[role=button],div')];
            for(const e of els){const t=txt(e); if(t && keys.some(k=>t.includes(k))){try{e.click();return true;}catch(_){}}}
            return false;
          }
          function phoneText(){
            const body=txt(document.body);
            const m=body.match(/(?:\\+98|0098|98|0)?9\\d{9}/g)||[];
            return [...new Set(m.map(x=>{x=x.replace(/\\D/g,''); if(x.startsWith('98')&&x.length===12)x='0'+x.slice(2); if(x.length===10&&x.startsWith('9'))x='0'+x; return x;}))];
          }
          function imgs(){
            const out=[]; const seen=new Set();
            const nodes=[...document.querySelectorAll('img,source')];
            for(const n of nodes){
              let u=n.currentSrc||n.src||n.getAttribute('data-src')||n.getAttribute('srcset')||'';
              if(u.includes(',')) u=u.split(',')[0].trim().split(' ')[0];
              if(!u || !u.includes('divarcdn.com')) continue;
              u=u.replace(/[?&](width|height|quality|resize|fit)=[^&]*/gi,'');
              if(!seen.has(u)){seen.add(u);out.push(u);}
            }
            return out;
          }
          function declaredCount(){
            const t=txt(document.body);
            let m=t.match(/تصویر\\s*\\d+\\s*از\\s*(\\d+)/); if(m)return +m[1];
            m=t.match(/(\\d+)\\s*(?:تصویر|عکس)/); if(m)return +m[1];
            return 0;
          }
          function field(label){
            const body=txt(document.body); const re=new RegExp(label+'\\s*[:：]?\\s*([^\\n]{1,80})','i'); const m=body.match(re); return m?m[1].trim():'';
          }
          const title=txt(document.querySelector('h1'));
          const body=txt(document.body);
          const price=(body.match(/(?:قیمت|قیمت کل)\\s*[:：]?\\s*([\\d۰-۹.,،]+)\\s*(?:تومان|میلیارد|میلیون)?/)||[])[1]||'';
          const area=(body.match(/(?:متراژ|متراژ:|زیربنا)\\s*[:：]?\\s*(\\d+(?:[.,]\\d+)?)\\s*متر/)||[])[1]||'';
          const rooms=(body.match(/(?:اتاق|خواب)\\s*[:：]?\\s*(\\d+)/)||[])[1]||'';
          const year=(body.match(/(?:سال ساخت|ساخت)\\s*[:：]?\\s*(\\d{4})/)||[])[1]||'';
          const floor=(body.match(/(?:طبقه)\\s*[:：]?\\s*([^\\n]{1,30})/)||[])[1]||'';
          const phones=phoneText();
          const images=imgs();
          return JSON.stringify({url:location.href,title,price,area,rooms,year,floor,phones,declared:declaredCount(),images,body:body.slice(0,12000),login:/login|ورود|شماره موبایل|کد تایید/i.test(location.href+' '+body)});
        })();
        """.trimIndent()
        web.evaluateJavascript(js) { value ->
            try {
                val json = JSONObject(value.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", " "))
                val login = json.optBoolean("login", false)
                val phones = json.optJSONArray("phones")
                val phone = if (phones != null && phones.length() > 0) phones.getString(0) else ""
                if (login && !phone.isNullOrBlank()) {
                    showResult(json, phone)
                    return@evaluateJavascript
                }
                if (login) {
                    status.text = "وضعیت: اگر دیوار ورود یا CAPTCHA خواست، همین صفحه را تکمیل کنید…"
                    web.visibility = View.VISIBLE
                    return@evaluateJavascript
                }
                if (!contactClicked) {
                    contactClicked = true
                    status.text = "وضعیت: در حال باز کردن اطلاعات تماس…"
                    web.evaluateJavascript("(function(){const keys=['اطلاعات تماس','نمایش شماره','شماره تماس','تماس با فروشنده','مشاهده شماره']; for(const e of [...document.querySelectorAll('button,a,[role=button],div')]){const t=(e.innerText||'').replace(/\\s+/g,' ').trim(); if(t && keys.some(k=>t.includes(k))){try{e.click();return 'clicked'}catch(_){}}} return 'notfound'})();") { handler.postDelayed({ inspectAndExtract() }, 1800) }
                } else {
                    showResult(json, phone)
                }
            } catch (e: Exception) {
                status.text = "وضعیت: خطا در خواندن اطلاعات صفحه؛ دوباره تلاش کنید"
            }
        }
    }

    private fun showResult(json: JSONObject, phone: String) {
        val images = json.optJSONArray("images")
        val declared = json.optInt("declared", 0)
        val count = images?.length() ?: 0
        val shown = if (declared > 0) minOf(declared, count) else count
        val sb = StringBuilder()
        sb.append("عنوان: ").append(json.optString("title")).append('\n')
        sb.append("قیمت: ").append(json.optString("price")).append('\n')
        sb.append("متراژ: ").append(json.optString("area")).append('\n')
        sb.append("تعداد خواب: ").append(json.optString("rooms")).append('\n')
        sb.append("سال ساخت: ").append(json.optString("year")).append('\n')
        sb.append("طبقه: ").append(json.optString("floor")).append('\n')
        sb.append("شماره تماس: ").append(if (phone.isBlank()) "در صفحه فعلی قابل استخراج نبود" else phone).append('\n')
        sb.append("عکس‌های گالری: ").append(shown).append(if (declared>0) " از $declared" else "").append('\n')
        sb.append("\nآدرس آگهی:\n").append(json.optString("url"))
        result.text = sb.toString()
        status.text = "وضعیت: استخراج انجام شد"
        web.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) { web.goBack() } else super.onBackPressed()
    }
}
