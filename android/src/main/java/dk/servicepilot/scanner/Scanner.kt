package dk.servicepilot.scanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.*
import com.google.mlkit.genai.prompt.*
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.*
import org.json.JSONArray
import java.security.MessageDigest

class Scanner : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val model by lazy { Generation.getClient() }
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var scan: Button
    private lateinit var rotate: Button
    private lateinit var pick: Button
    private var photo: Bitmap? = null
    private var key = ""
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(layout)
        fun button(title: String, action: () -> Unit) = Button(this).apply { text=title; layout.addView(this); setOnClickListener { action() } }
        status = TextView(this).also { layout.addView(it); it.text="Vælg et foto af fejlsedlen." }
        pick = button("Vælg foto") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 1) }
        rotate = button("Drej 90°") { photo?.let { old -> photo=Bitmap.createBitmap(old,0,0,old.width,old.height,Matrix().apply {postRotate(90f)},true); preview.setImageBitmap(photo) } }
        scan = button("Aflæs med Gemini Nano") { recognize() }
        scan.isEnabled=false
        preview=ImageView(this).also { layout.addView(it); it.adjustViewBounds=true }
    }
    override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        val uri=data?.data ?: return
        if(request!=1 || result!=RESULT_OK)return
        runCatching {
            photo=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder, info, _ ->
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                val scale=minOf(1.0,2400.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
            }
            val digest=MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)!!.use { stream -> val buf=ByteArray(65536); while(true){val n=stream.read(buf);if(n<0)break;digest.update(buf,0,n)} }
            key=digest.digest().joinToString(""){"%02x".format(it)}
            preview.setImageBitmap(photo); scan.isEnabled=true;status.text="Kontrollér læseretningen."
        }.onFailure { scan.isEnabled=false;status.text="Fotoet kunne ikke åbnes." }
    }
    private fun recognize() {
        val bitmap=photo ?: return
        scan.isEnabled=false;pick.isEnabled=false;rotate.isEnabled=false
        scope.launch {
            try {
                when(model.checkStatus()){
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {status.text="Henter Androids lokale model…";model.download().collect {}}
                }
                require(model.checkStatus()==FeatureStatus.AVAILABLE){"Gemini Nano er ikke tilgængelig på denne enhed."}
                status.text="Læser fejl og kvitteringer…"
                val prompt="Read this Danish maintenance table row by row. Return only a JSON array of objects with places (array of exact room numbers or area names as strings), fault (original Danish description and remarks), status (open if OK column is clearly empty, closed for checkmark or initials, unclear otherwise). Split 371+285 into two places. For illegible places use an empty array. Never invent missing characters."
                val answer=model.generateContent(generateContentRequest(ImagePart(bitmap),TextPart(prompt)) { temperature=0.1f })
                val raw=answer.candidates.first().text ?: error("Tomt svar")
                val rows=JSONArray(raw.substringAfter('[').substringBeforeLast(']').let {"["+it+"]"})
                require(rows.length() in 1..100){"Ingen læsbare rækker"}
                val target=if(intent.data?.getQueryParameter("preview")=="1") "https://raw.githack.com/Emilvernerhansen-gif/servicepilot-enkel/digital-fejlmappe/index.html" else "https://emilvernerhansen-gif.github.io/servicepilot-enkel/"
                val result=org.json.JSONObject().put("key",key).put("rows",rows)
                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(target+"#native="+Uri.encode(result.toString()))))
                finish()
            }catch(e:Exception){status.text=e.localizedMessage ?: "Aflæsning mislykkedes"}
            finally{scan.isEnabled=true;pick.isEnabled=true;rotate.isEnabled=true}
        }
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}