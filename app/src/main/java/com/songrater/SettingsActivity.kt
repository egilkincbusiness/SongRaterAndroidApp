package com.songrater

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sourceText: TextView
    private lateinit var destText: TextView

    private val pickSource =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handle(it, true) }
    private val pickDest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handle(it, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sourceText = findViewById(R.id.sourceText)
        destText = findViewById(R.id.destText)
        findViewById<android.view.View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.sourceButton).setOnClickListener { launchPicker(pickSource) }
        findViewById<android.view.View>(R.id.destButton).setOnClickListener { launchPicker(pickDest) }

        val group = findViewById<RadioGroup>(R.id.themeGroup)
        group.check(
            when (Prefs.theme(this)) {
                Prefs.THEME_LIGHT -> R.id.themeLight
                Prefs.THEME_DARK -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeLight -> Prefs.THEME_LIGHT
                R.id.themeDark -> Prefs.THEME_DARK
                else -> Prefs.THEME_SYSTEM
            }
            Prefs.setTheme(this, theme)
            Prefs.applyTheme(this)      // recreates the screens with the new theme
        }

        updateLabels()
    }

    private fun launchPicker(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        launcher.launch(intent)
    }

    private fun handle(result: ActivityResult, isSource: Boolean) {
        if (result.resultCode != RESULT_OK) return
        val uri = result.data?.data ?: return
        val other = if (isSource) Prefs.dest(this) else Prefs.source(this)
        if (other != null && other == uri) {
            Toast.makeText(this, "The song folder and the destination folder must be different.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Android wouldn't give access to that folder. Try a different one.", Toast.LENGTH_LONG).show()
            return
        }
        if (isSource) Prefs.setSource(this, uri) else Prefs.setDest(this, uri)
        updateLabels()
    }

    private fun updateLabels() {
        sourceText.text = label(Prefs.source(this))
        destText.text = label(Prefs.dest(this))
    }

    private fun label(uri: Uri?): String {
        if (uri == null) return "Not set"
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return uri.toString()
        }
        return if (id.startsWith("primary:")) "Internal storage/" + id.removePrefix("primary:") else id
    }
}
