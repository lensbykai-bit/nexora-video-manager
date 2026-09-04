package ai.nexora.videomanager

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.nexora.videomanager.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.DataOutputStream
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
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
            b.fileText.text = "បានជ្រើស៖ ${uri.lastPathSegment ?: "media"}"
            toast("បានជ្រើស Video/Audio រួចរាល់")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ArrayAdapter.createFromResource(this,R.array.tts_models,android.R.layout.simple_spinner_item).also{
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            b.modelSpinner.adapter=it
        }

        b.pickButton.setOnClickListener { picker.launch(arrayOf("video/*","audio/*")) }
        b.transcribeButton.setOnClickListener { toast("កំពុងចាប់ផ្តើម Auto Script…"); runTranscribe() }
        b.voicesButton.setOnClickListener { toast("កំពុង Load Voices…"); loadVoicesAndAssign() }
        b.translateButton.setOnClickListener { toast("កំពុងបកប្រែ…"); translateKhmer() }
        b.voiceAllButton.setOnClickListener { toast("កំពុង Generate Voice All…"); generateAll() }
    }

    private fun runTranscribe() {
        val key=b.elevenKey.text.toString().trim(); val uri=mediaUri
        if(key.isBlank()||uri==null){status("⚠️ សូមដាក់ ElevenLabs API Key និងជ្រើសវីដេអូ/អូឌីយ៉ូជាមុន។");toast("ត្រូវមាន API Key និង Video/Audio");return}
        b.transcribeButton.isEnabled=false
        status("⏳ កំពុង Upload និង Auto ចាប់ Script + តួអង្គ…")
        Thread{
            try{
                val boundary="----MovieScript${System.currentTimeMillis()}"
                val mime=contentResolver.getType(uri) ?: "application/octet-stream"
                val conn=(URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection() as HttpURLConnection).apply{
                    requestMethod="POST"; doOutput=true
                    connectTimeout=30000; readTimeout=300000
                    setRequestProperty("xi-api-key",key)
                    setRequestProperty("Accept","application/json")
                    setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
                }
                DataOutputStream(conn.outputStream).use{out->
                    fun field(name:String,value:String){ out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n") }
                    field("model_id","scribe_v2")
                    field("diarize","true")
                    field("timestamps_granularity","word")
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"media\"\r\nContent-Type: $mime\r\n\r\n")
                    contentResolver.openInputStream(uri)?.use{it.copyTo(out)} ?: throw Exception("មិនអាចអាន file បាន")
                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }
                val code=conn.responseCode
                val raw=(if(code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) throw Exception("ElevenLabs HTTP $code: ${raw.take(500)}")
                val j=JSONObject(raw); val words=j.optJSONArray("words")
                val result=mutableListOf<Seg>(); var cur:Seg?=null
                if(words!=null) for(i in 0 until words.length()){
                    val w=words.getJSONObject(i); if(w.optString("type")!="word") continue
                    val sp=w.optString("speaker_id","speaker_0").ifBlank{"speaker_0"}; val st=w.optDouble("start",0.0); val en=w.optDouble("end",st); val tx=w.optString("text")
                    if(cur==null||cur!!.speaker!=sp||st-cur!!.end>1.2){ if(cur!=null) result.add(cur!!); cur=Seg(sp,st,en,tx) }
                    else cur=cur!!.copy(end=en,text=(cur!!.text+" "+tx).trim())
                }
                if(cur!=null) result.add(cur!!)
                segments=result
                runOnUiThread{
                    renderScript(); status("✅ Script រួចរាល់ • ${segments.size} segments • ${segments.map{it.speaker}.distinct().size} តួអង្គ")
                    toast("Auto Script រួចរាល់")
                    b.transcribeButton.isEnabled=true
                }
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message ?: "មានបញ្ហា"}");toast("Auto Script មានបញ្ហា");b.transcribeButton.isEnabled=true}}
        }.start()
    }

    private fun loadVoicesAndAssign(){
        val key=b.elevenKey.text.toString().trim(); if(key.isBlank()){status("⚠️ សូមដាក់ ElevenLabs API Key ជាមុន។");return}
        b.voicesButton.isEnabled=false
        status("⏳ កំពុង Load Voices និង Auto Assign…")
        Thread{
            try{
                val c=(URL("https://api.elevenlabs.io/v2/voices?page_size=100").openConnection() as HttpURLConnection).apply{connectTimeout=30000;readTimeout=60000;setRequestProperty("xi-api-key",key);setRequestProperty("Accept","application/json")}
                val code=c.responseCode; val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) throw Exception("ElevenLabs HTTP $code: ${raw.take(500)}")
                val arr=JSONObject(raw).getJSONArray("voices"); val list=mutableListOf<Voice>()
                for(i in 0 until arr.length()){val v=arr.getJSONObject(i);list.add(Voice(v.getString("voice_id"),v.getString("name")))}; voices=list
                val speakers=segments.map{it.speaker}.distinct(); speakerVoice.clear(); speakers.forEachIndexed{i,s-> if(voices.isNotEmpty()) speakerVoice[s]=voices[i%voices.size].id }
                runOnUiThread{b.mappingText.text=if(speakers.isEmpty())"⚠️ Auto Script ជាមុន" else speakers.joinToString("\n"){s->"$s → ${voices.find{it.id==speakerVoice[s]}?.name ?: "-"}"};status("✅ Load ${voices.size} voices • Auto Assign រួចរាល់");b.voicesButton.isEnabled=true}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}");b.voicesButton.isEnabled=true}}
        }.start()
    }

    private fun translateKhmer(){
        val key=b.geminiKey.text.toString().trim(); if(key.isBlank()||segments.isEmpty()){status("⚠️ ត្រូវមាន Gemini API Key និង Script ជាមុន។");return}
        b.translateButton.isEnabled=false; status("⏳ កំពុងបកប្រែទៅខ្មែរ…")
        Thread{
            try{
                val prompt="Translate each line to natural Khmer dubbing. Keep speaker labels and timestamps. Return plain lines only.\n"+segments.joinToString("\n"){"${it.speaker}|${it.start}|${it.end}|${it.text}"}
                val body=JSONObject().put("contents",org.json.JSONArray().put(JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",prompt))))).toString()
                val c=(URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=30000;readTimeout=120000;setRequestProperty("Content-Type","application/json")}
                c.outputStream.use{it.write(body.toByteArray())}; val code=c.responseCode; val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                if(code !in 200..299) throw Exception("Gemini HTTP $code: ${raw.take(500)}")
                val j=JSONObject(raw); val out=j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val lines=out.lines().filter{it.contains("|")}; lines.take(segments.size).forEachIndexed{i,l->segments[i].text=l.substringAfterLast("|").trim()}
                runOnUiThread{renderScript();status("✅ បកប្រែខ្មែររួចរាល់");b.translateButton.isEnabled=true}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}");b.translateButton.isEnabled=true}}
        }.start()
    }

    private fun generateAll(){
        val key=b.elevenKey.text.toString().trim(); if(key.isBlank()||segments.isEmpty()||speakerVoice.isEmpty()){status("⚠️ ត្រូវមាន Script និង Auto Assign Voice ជាមុន។");return}
        val model=when(b.modelSpinner.selectedItemPosition){1->"eleven_flash_v2_5";2->"eleven_turbo_v2_5";else->"eleven_multilingual_v2"}
        b.voiceAllButton.isEnabled=false;status("⏳ កំពុង Generate Voice All…")
        Thread{
            try{
                segments.forEachIndexed{i,s->
                    val vid=speakerVoice[s.speaker]?:return@forEachIndexed
                    val body=JSONObject().put("text",s.text).put("model_id",model).toString()
                    val c=(URL("https://api.elevenlabs.io/v1/text-to-speech/$vid?output_format=mp3_44100_128").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=30000;readTimeout=120000;setRequestProperty("xi-api-key",key);setRequestProperty("Content-Type","application/json")}
                    c.outputStream.use{it.write(body.toByteArray())}; val code=c.responseCode
                    if(code !in 200..299){val er=c.errorStream?.bufferedReader()?.readText().orEmpty();throw Exception("TTS HTTP $code: ${er.take(400)}")}
                    val bytes=c.inputStream.readBytes(); saveMp3("segment_${String.format("%04d",i+1)}_${s.speaker}.mp3",bytes)
                    runOnUiThread{status("⏳ Voice All ${i+1}/${segments.size}…")}
                }
                runOnUiThread{status("✅ Voice All រួចរាល់។ MP3 នៅ Music/MovieScriptDubAI");toast("Voice All រួចរាល់");b.voiceAllButton.isEnabled=true}
            }catch(e:Exception){runOnUiThread{status("❌ ${e.message}");b.voiceAllButton.isEnabled=true}}
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
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
}
