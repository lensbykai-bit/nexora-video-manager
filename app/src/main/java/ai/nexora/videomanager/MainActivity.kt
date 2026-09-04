package ai.nexora.videomanager

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.nexora.videomanager.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var mediaUri: Uri? = null
    private var segments = mutableListOf<Seg>()
    private var voices = mutableListOf<Voice>()
    private val speakerVoice = mutableMapOf<String,String>()

    data class Seg(val speaker:String,val start:Double,val end:Double,var text:String)
    data class Voice(val id:String,val name:String)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            mediaUri = uri
            b.fileText.text = "បានជ្រើស៖ ${uri.lastPathSegment ?: "media"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ArrayAdapter.createFromResource(this,R.array.tts_models,android.R.layout.simple_spinner_item).also{
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); b.modelSpinner.adapter=it
        }

        b.pickButton.setOnClickListener { picker.launch(arrayOf("video/*","audio/*")) }
        b.transcribeButton.setOnClickListener { runTranscribe() }
        b.voicesButton.setOnClickListener { loadVoicesAndAssign() }
        b.translateButton.setOnClickListener { translateKhmer() }
        b.voiceAllButton.setOnClickListener { generateAll() }
    }

    private fun runTranscribe() {
        val key=b.elevenKey.text.toString().trim(); val uri=mediaUri
        if(key.isBlank()||uri==null){status("សូមដាក់ ElevenLabs API Key និងជ្រើសវីដេអូ/អូឌីយ៉ូជាមុន។");return}
        status("កំពុង Auto ចាប់ Script + តួអង្គ…")
        Thread{
            try{
                val boundary="----MovieScript${System.currentTimeMillis()}"
                val conn=(URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection() as HttpURLConnection).apply{
                    requestMethod="POST"; doOutput=true; setRequestProperty("xi-api-key",key); setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
                }
                DataOutputStream(conn.outputStream).use{out->
                    fun field(name:String,value:String){ out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n") }
                    field("model_id","scribe_v2"); field("diarize","true"); field("timestamps_granularity","word")
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"media\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    contentResolver.openInputStream(uri)!!.use{it.copyTo(out)}
                    out.writeBytes("\r\n--$boundary--\r\n")
                }
                val text=conn.inputStream.bufferedReader().readText(); val j=JSONObject(text); val words=j.optJSONArray("words")
                val result=mutableListOf<Seg>(); var cur:Seg?=null
                if(words!=null) for(i in 0 until words.length()){
                    val w=words.getJSONObject(i); if(w.optString("type")!="word") continue
                    val sp=w.optString("speaker_id","speaker_0"); val st=w.optDouble("start",0.0); val en=w.optDouble("end",st); val tx=w.optString("text")
                    if(cur==null||cur!!.speaker!=sp||st-cur!!.end>1.2){ if(cur!=null) result.add(cur!!); cur=Seg(sp,st,en,tx) }
                    else { cur=cur!!.copy(end=en,text=(cur!!.text+" "+tx).trim()) }
                }
                if(cur!=null) result.add(cur!!); segments=result
                runOnUiThread{renderScript();status("✅ Script រួចរាល់ • ${segments.size} segments • ${segments.map{it.speaker}.distinct().size} តួអង្គ")}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}")}}
        }.start()
    }

    private fun loadVoicesAndAssign(){
        val key=b.elevenKey.text.toString().trim(); if(key.isBlank()){status("សូមដាក់ ElevenLabs API Key ជាមុន។");return}
        status("កំពុង Load Voices និង Auto Assign…")
        Thread{
            try{
                val c=(URL("https://api.elevenlabs.io/v2/voices?page_size=100").openConnection() as HttpURLConnection).apply{setRequestProperty("xi-api-key",key)}
                val arr=JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("voices"); val list=mutableListOf<Voice>()
                for(i in 0 until arr.length()){val v=arr.getJSONObject(i);list.add(Voice(v.getString("voice_id"),v.getString("name")))}; voices=list
                val speakers=segments.map{it.speaker}.distinct(); speakerVoice.clear(); speakers.forEachIndexed{i,s-> if(voices.isNotEmpty()) speakerVoice[s]=voices[i%voices.size].id }
                runOnUiThread{b.mappingText.text=speakers.joinToString("\n"){s->"$s → ${voices.find{it.id==speakerVoice[s]}?.name ?: "-"}"};status("✅ Auto Assign Voice រួចរាល់")}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}")}}
        }.start()
    }

    private fun translateKhmer(){
        val key=b.geminiKey.text.toString().trim(); if(key.isBlank()||segments.isEmpty()){status("ត្រូវមាន Gemini API Key និង Script ជាមុន។");return}
        status("កំពុងបកប្រែទៅខ្មែរ…")
        Thread{
            try{
                val prompt="Translate each line to natural Khmer dubbing. Keep speaker labels and timestamps. Return plain lines only.\n"+segments.joinToString("\n"){"${it.speaker}|${it.start}|${it.end}|${it.text}"}
                val body=JSONObject().put("contents",org.json.JSONArray().put(JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",prompt))))).toString()
                val c=(URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;setRequestProperty("Content-Type","application/json")}
                c.outputStream.use{it.write(body.toByteArray())}; val j=JSONObject(c.inputStream.bufferedReader().readText()); val out=j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val lines=out.lines().filter{it.contains("|")}; lines.take(segments.size).forEachIndexed{i,l->segments[i].text=l.substringAfterLast("|").trim()}
                runOnUiThread{renderScript();status("✅ បកប្រែខ្មែររួចរាល់")}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}")}}
        }.start()
    }

    private fun generateAll(){
        val key=b.elevenKey.text.toString().trim(); if(key.isBlank()||segments.isEmpty()||speakerVoice.isEmpty()){status("ត្រូវមាន Script និង Auto Assign Voice ជាមុន។");return}
        val model=when(b.modelSpinner.selectedItemPosition){1->"eleven_flash_v2_5";2->"eleven_turbo_v2_5";else->"eleven_multilingual_v2"}
        status("កំពុង Generate Voice All…")
        Thread{
            try{
                segments.forEachIndexed{i,s->
                    val vid=speakerVoice[s.speaker]?:return@forEachIndexed
                    val body=JSONObject().put("text",s.text).put("model_id",model).toString()
                    val c=(URL("https://api.elevenlabs.io/v1/text-to-speech/$vid?output_format=mp3_44100_128").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;setRequestProperty("xi-api-key",key);setRequestProperty("Content-Type","application/json")}
                    c.outputStream.use{it.write(body.toByteArray())}; val bytes=c.inputStream.readBytes(); saveMp3("segment_${String.format("%04d",i+1)}_${s.speaker}.mp3",bytes)
                    runOnUiThread{status("Voice All ${i+1}/${segments.size}…")}
                }
                runOnUiThread{status("✅ Voice All រួចរាល់។ MP3 រក្សាទុកក្នុង Music/MovieScriptDubAI")}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}")}}
        }.start()
    }

    private fun saveMp3(name:String,bytes:ByteArray){
        val values=ContentValues().apply{put(MediaStore.Audio.Media.DISPLAY_NAME,name);put(MediaStore.Audio.Media.MIME_TYPE,"audio/mpeg");put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/MovieScriptDubAI")}
        val uri=contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)?:return
        contentResolver.openOutputStream(uri)?.use{it.write(bytes)}
    }

    private fun renderScript(){b.scriptText.setText(segments.joinToString("\n"){"[${time(it.start)}] ${it.speaker}: ${it.text}"})}
    private fun time(v:Double):String{val t=v.toInt();return String.format("%02d:%02d:%02d",t/3600,(t%3600)/60,t%60)}
    private fun status(s:String){b.statusText.text=s}
}
