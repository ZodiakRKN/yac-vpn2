package com.yacvpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yacvpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startTunnel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("config", MODE_PRIVATE)

        // Load saved settings
        binding.etGateway.setText(prefs.getString("gateway", "wss://d5d39r7l30a40c2n6joc.uvah0e6r.apigw.yandexcloud.net/_helper"))
        binding.etToken.setText(prefs.getString("token", "3f4hjfjn"))

        binding.btnConnect.setOnClickListener {
    val prepare = VpnService.prepare(this)
    if (prepare != null) {
        vpnLauncher.launch(prepare)
    } else {
        startTunnel()
    }
}
        }

        binding.btnDisconnect.setOnClickListener {
            stopTunnel()
        }

        // Observe status
        TunnelService.status.observe(this) { status ->
            binding.tvStatus.text = status
            val connected = status.startsWith("✅")
            binding.btnConnect.isEnabled = !connected
            binding.btnDisconnect.isEnabled = connected
        }
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("gateway", binding.etGateway.text.toString().trim())
            .putString("token", binding.etToken.text.toString().trim())
            .apply()
    }

    private fun startTunnel() {
    val gw = binding.etGateway.text.toString().trim()
    val token = binding.etToken.text.toString().trim()
    if (gw.isEmpty() || token.isEmpty()) {
        Toast.makeText(this, "Заполните Gateway и Token", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(this, TunnelService::class.java).apply {
        action = TunnelService.ACTION_START
        putExtra("gateway", gw)
        putExtra("token", token)
    }
    startForegroundService(intent)
}

    private fun stopTunnel() {
        startService(Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        })
    }
}
