package de.dhde.wifilogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.dhde.wifilogger.databinding.ActivityInfoBinding

class InfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Dynamische Version aus dem Gradle Build anzeigen
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "0.1.5"
        }
        binding.txtVersion.text = "Version $versionName"

        binding.btnGithub.setOnClickListener {
            openUrl("https://github.com/dhde/android-wifi-logger")
        }

        binding.btnCoffee.setOnClickListener {
            openUrl("https://www.buymeacoffee.com/dhde")
        }

        binding.btnPrivacy.setOnClickListener {
            openUrl("https://dhde.github.io/android-wifi-logger/privacy-policy")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // Falls kein Browser installiert ist
        }
    }
}
