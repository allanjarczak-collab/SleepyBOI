package com.txtpodcast

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var voices: Spinner
    private lateinit var playBtn: Button
    private lateinit var progress: TextView
    private var voiceList = listOf<android.speech.tts.Voice>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        voices = findViewById(R.id.voices)
        playBtn = findViewById(R.id.play)
        progress = findViewById(R.id.progress)

        findViewById<Button>(R.id.pick).setOnClickListener { picker.launch(arrayOf("text/plain")) }
        playBtn.setOnClickListener { send(TtsService.ACTION_TOGGLE) }
        findViewById<Button>(R.id.stop).setOnClickListener { send(TtsService.ACTION_STOP) }
        findViewById<Button>(R.id.next).setOnClickListener { send(TtsService.ACTION_NEXT) }
        findViewById<Button>(R.id.prev).setOnClickListener { send(TtsService.ACTION_PREV) }
        findViewById<Button>(R.id.fwd10).setOnClickListener { send(TtsService.ACTION_FWD) }
        findViewById<Button>(R.id.back10).setOnClickListener { send(TtsService.ACTION_BACK) }
        findViewById<SeekBar>(R.id.position).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) findViewById<TextView>(R.id.time).text =
                    "${TtsService.fmt(TtsService.totalMin() * p / 1000f)} / ${TtsService.fmt(TtsService.totalMin())}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, TtsService::class.java)
                    .setAction("seek").putExtra("min", TtsService.totalMin() * s.progress / 1000f))
            }
        })
        findViewById<CheckBox>(R.id.repeat).apply {
            isChecked = TtsService.repeat
            setOnCheckedChangeListener { _, c -> TtsService.repeat = c }
        }
        slider(R.id.speed, R.id.speedLabel) { v -> TtsService.speed = v; "Speed %.2fx".format(v) }
        slider(R.id.pitch, R.id.pitchLabel) { v -> TtsService.pitch = v; "Pitch %.2f (lower = softer)".format(v) }

        TtsService.onUpdate = { runOnUiThread { updateUi() } }
        TtsService.onReady = { runOnUiThread { fillVoices() } }
        startService(Intent(this, TtsService::class.java)) // initializes the TTS engine
        if (TtsService.ready) fillVoices()
        updateUi()
    }

    /** SeekBar 0..150 maps to 0.5..2.0 */
    private fun slider(id: Int, labelId: Int, apply: (Float) -> String) {
        val label = findViewById<TextView>(labelId)
        findViewById<SeekBar>(id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { label.text = apply(0.5f + p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun fillVoices() {
        val def = Locale.getDefault()
        voiceList = (TtsService.tts?.voices ?: emptySet()).sortedByDescending { it.score(def) }
        voices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceList.map { it.label() })
        voiceList.indexOfFirst { it.name == TtsService.voiceName }.takeIf { it >= 0 }?.let { voices.setSelection(it) }
        voices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                TtsService.voiceName = voiceList[pos].name
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun load(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        send(TtsService.ACTION_PAUSE)
        TtsService.chunks = TtsService.split(text)
        TtsService.index = 0
        var name = uri.lastPathSegment ?: "file"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        findViewById<TextView>(R.id.fileName).text = "$name  (${text.length} chars)"
        updateUi()
    }

    private fun send(action: String) =
        ContextCompat.startForegroundService(this, Intent(this, TtsService::class.java).setAction(action))

    private fun updateUi() {
        playBtn.text = if (TtsService.playing) "⏸ Pause" else "▶ Play"
        val total = TtsService.totalMin()
        findViewById<TextView>(R.id.time).text = "${TtsService.fmt(TtsService.elapsedMin())} / ${TtsService.fmt(total)}"
        findViewById<SeekBar>(R.id.position).progress = if (total > 0) (TtsService.elapsedMin() / total * 1000).toInt() else 0
        val c = TtsService.chunks
        progress.text = if (c.isEmpty()) "" else "Part ${TtsService.index + 1} of ${c.size}\n\n" +
            c[TtsService.index].take(400)
    }

    override fun onDestroy() {
        TtsService.onUpdate = null; TtsService.onReady = null
        super.onDestroy()
    }
}
